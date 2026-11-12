
package com.giraffix.product.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.giraffix.brand.entity.Brand;
import com.giraffix.brand.repository.BrandRepository;
import com.giraffix.config.ClaudeProperties;
import com.giraffix.product.dto.*;
import com.giraffix.product.entity.Product;
import com.giraffix.product.repository.ProductRepository;
import com.giraffix.user.entity.User;
import com.giraffix.user.service.UserService;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final ClaudeProperties claudeProperties;
    private final RestClient restClient;
    private final Resource productAnalysisPrompt;
    private final Resource productAnalysisSchema;
    private final S3ImageStorage storage;
    @Value("${giraffix.ai.max-images:5}")
    private int maxImages = 5;
    @Value("${giraffix.ai.max-total-image-size-bytes:20971520}")
    private long maxTotalImageSizeBytes = 20971520;

    public ProductService(
            ProductRepository productRepository,
            BrandRepository brandRepository,
            UserService userService,
            ObjectMapper objectMapper,
            ClaudeProperties claudeProperties,
            RestClient.Builder restClientBuilder,
            @Value("classpath:prompts/product-analysis.txt")
            Resource productAnalysisPrompt,
            @Value("classpath:schemas/product-visual-extraction.json")
            Resource productAnalysisSchema,
            S3ImageStorage storage) {

        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.claudeProperties = claudeProperties;
        this.productAnalysisPrompt = productAnalysisPrompt;
        this.productAnalysisSchema = productAnalysisSchema;
        this.storage = storage;

        this.restClient = restClientBuilder.clone()
                .baseUrl(claudeProperties.baseUrl())
                .defaultHeader(
                        "x-api-key",
                        claudeProperties.apiKey()
                )
                .defaultHeader(
                        "anthropic-version",
                        claudeProperties.apiVersion()
                )
                .build();
    }


    // ========================================
    // AI PRODUCT IMAGE ANALYSIS - NEW
    // ========================================

    public ProductAnalysisResponse analyzeProduct(
            MultipartFile image) {
        return analyzeProductImages(java.util.Collections.singletonList(image));
    }

    public ProductAnalysisResponse analyzeProductImages(List<MultipartFile> images) {
        String requestId = MDC.get("analysisId");
        String analysisId = requestId != null ? requestId : UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("analysisId={} stage=analysis_started imageCount={}", analysisId, images == null ? 0 : images.size());
        try {
            ProductAnalysisResponse result = analyzeProductImages(images, analysisId);
            log.info("analysisId={} stage=analysis_suggestions_returned persistence=none nextAction=user_review_and_confirm", analysisId);
            log.info("analysisId={} stage=analysis_completed status=200 durationMs={}", analysisId, elapsed(started));
            return result;
        } catch (ResponseStatusException ex) {
            log.warn("analysisId={} stage=analysis_failed status={} reason={} durationMs={}",
                    analysisId, ex.getStatusCode().value(), safeLog(ex.getReason()), elapsed(started));
            throw ex;
        } catch (RuntimeException ex) {
            log.error("analysisId={} stage=analysis_failed status=500 errorType={} durationMs={}",
                    analysisId, ex.getClass().getSimpleName(), elapsed(started));
            throw ex;
        }
    }

    private ProductAnalysisResponse analyzeProductImages(List<MultipartFile> images, String analysisId) {
        if (images == null || images.isEmpty() || images.size() > maxImages) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload between 1 and " + maxImages + " product images");
        }
        long uploadBytes = 0;
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every uploaded image must be nonempty");
            }
            if (image.getSize() > claudeProperties.maxImageSizeBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the allowed size");
            }
            uploadBytes += image.getSize();
        }
        if (uploadBytes > maxTotalImageSizeBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Combined images exceed the allowed total size");
        }
        List<Map<String, Object>> content = new java.util.ArrayList<>();
        long preparedBytes = 0;
        for (int index = 0; index < images.size(); index++) {
            MultipartFile image = images.get(index);
            log.info("analysisId={} stage=image_received imageIndex={} filename={} declaredType={} sizeBytes={}",
                    analysisId, index + 1, safeLog(image.getOriginalFilename()), safeLog(image.getContentType()), image.getSize());
            byte[] bytes;
            try {
                bytes = image.getBytes();
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read image " + (index + 1), ex);
            }
            var prepared = ProductImageProcessor.prepare(bytes, claudeProperties.maxImageSizeBytes());
            preparedBytes += prepared.bytes().length;
            if (preparedBytes > maxTotalImageSizeBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Converted images exceed the allowed total size");
            }
            log.info("analysisId={} stage=upload_validated imageIndex={} sourceFormat={} detectedType={} converted={} sizeBytes={}",
                    analysisId, index + 1, prepared.sourceFormat(), prepared.mediaType(), prepared.converted(), prepared.bytes().length);
            content.add(Map.of("type", "image", "source", Map.of("type", "base64",
                    "media_type", prepared.mediaType(), "data", Base64.getEncoder().encodeToString(prepared.bytes()))));
        }

        String prompt;
        JsonNode schema;

        try (var inputStream =
                     productAnalysisPrompt.getInputStream();
             var schemaStream = productAnalysisSchema.getInputStream()) {

            prompt = new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
            schema = objectMapper.readTree(schemaStream);

        } catch (IOException | JacksonException ex) {
            log.error("analysisId={} stage=resources_failed errorType={}", analysisId, ex.getClass().getSimpleName());
            throw new IllegalStateException(
                    "Unable to load product analysis resources",
                    ex
            );
        }
        log.debug("analysisId={} stage=resources_loaded prompt={} promptChars={} schema={} schemaFields={}",
                analysisId, productAnalysisPrompt.getFilename(), prompt.length(),
                productAnalysisSchema.getFilename(), schema.path("properties").size());

        content.add(Map.of("type", "text", "text", prompt));

        // Claude Messages API request
        Map<String, Object> requestBody = Map.of(
                "model", claudeProperties.model(),
                "max_tokens", claudeProperties.maxTokens(),
                "output_config", Map.of("format", Map.of(
                        "type", "json_schema", "schema", schema)),
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", content
                        )
                )
        );

        String response;
        long apiStarted = System.nanoTime();
        log.info("analysisId={} stage=model_api_call_started method=POST endpoint={}/messages model={} maxTokens={} imageCount={} totalImageBytes={} apiVersion={}",
                analysisId, claudeProperties.baseUrl(), claudeProperties.model(), claudeProperties.maxTokens(),
                images.size(), preparedBytes, claudeProperties.apiVersion());
        try {
            var apiResponse = restClient.post()
                    .uri("/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);
            response = apiResponse.getBody();
            log.info("analysisId={} stage=model_api_response_received status={} providerRequestId={} durationMs={} responseChars={}",
                    analysisId, apiResponse.getStatusCode().value(),
                    safeLog(apiResponse.getHeaders().getFirst("request-id")), elapsed(apiStarted),
                    response == null ? 0 : response.length());
        } catch (RestClientResponseException ex) {
            log.error("analysisId={} stage=model_api_http_error status={} providerRequestId={} durationMs={}",
                    analysisId, ex.getStatusCode().value(), ex.getResponseHeaders() == null ? "none" :
                            safeLog(ex.getResponseHeaders().getFirst("request-id")), elapsed(apiStarted));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude image analysis failed", ex);
        } catch (RestClientException ex) {
            log.error("analysisId={} stage=model_api_transport_error errorType={} durationMs={}",
                    analysisId, ex.getClass().getSimpleName(), elapsed(apiStarted));
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Claude image analysis failed",
                    ex
            );
        }

        if (response == null || response.isBlank()) {
            log.warn("analysisId={} stage=model_response_empty", analysisId);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Empty response from Claude"
            );
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            log.info("analysisId={} stage=model_response_parsed messageId={} model={} stopReason={} inputTokens={} outputTokens={}",
                    analysisId, safeLog(root.path("id").asText()), safeLog(root.path("model").asText()),
                    safeLog(root.path("stop_reason").asText()), root.path("usage").path("input_tokens").asInt(0),
                    root.path("usage").path("output_tokens").asInt(0));

            // Reject truncated AI responses
            if (!"end_turn".equals(
                    root.path("stop_reason").asText())) {
                throw new IllegalStateException(
                        "Claude response was incomplete"
                );
            }

            JsonNode blocks = root.path("content");

            if (!blocks.isArray()) {
                throw new IllegalStateException(
                        "Unexpected Claude response"
                );
            }

            StringBuilder text = new StringBuilder();

            for (JsonNode block : blocks) {
                if ("text".equals(
                        block.path("type").asText())) {
                    text.append(
                            block.path("text").asText()
                    );
                }
            }

            String json = text.toString().trim();
            log.debug("analysisId={} stage=model_text_extracted blocks={} textChars={}", analysisId, blocks.size(), json.length());

            // Allow a fenced JSON response
            if (json.startsWith("```")) {
                json = json.replaceFirst(
                        "^```(?:json)?\\s*", ""
                ).replaceFirst("\\s*```$", "");
            }

            JsonNode extraction = objectMapper.readTree(json);
            validateProductExtraction(extraction);
            ProductAnalysisResponse result = objectMapper.treeToValue(
                    extraction, ProductAnalysisResponse.class);
            List<String> populated = new java.util.ArrayList<>();
            for (String field : List.of("name", "category", "description", "keyFeatures", "targetAudience", "imageUrl")) {
                if (!extraction.path(field).isNull()) {
                    populated.add(field);
                    if (!"imageUrl".equals(field)) {
                        log.debug("analysisId={} stage=extracted_field field={} value={}",
                                analysisId, field, safeLog(extraction.path(field).asText()));
                    }
                }
            }
            log.info("analysisId={} stage=extraction_validated populatedFields={} nullFieldCount={}",
                    analysisId, populated, 6 - populated.size());
            if (populated.isEmpty()) {
                log.warn("analysisId={} stage=all_fields_null source=model_response action=returned_as_received", analysisId);
            }

            // Return suggestions only; no database save
            return result;

        } catch (JacksonException |
                 IllegalStateException ex) {
            log.warn("analysisId={} stage=model_response_invalid errorType={}", analysisId, ex.getClass().getSimpleName());
            if (ex instanceof IllegalStateException) {
                log.debug("analysisId={} stage=validation_detail reason={}", analysisId, safeLog(ex.getMessage()));
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Invalid product analysis response",
                    ex
            );
        }
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String safeLog(String value) {
        if (value == null) return "none";
        String sanitized = value;
        if (claudeProperties.apiKey() != null && !claudeProperties.apiKey().isEmpty()) {
            sanitized = sanitized.replace(claudeProperties.apiKey(), "[REDACTED]");
        }
        sanitized = sanitized.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        return sanitized.length() > 200 ? sanitized.substring(0, 200) + "..." : sanitized;
    }

    private void validateProductExtraction(JsonNode extraction) {
        List<String> fields = List.of("name", "category", "description", "keyFeatures",
                "targetAudience", "imageUrl");
        if (extraction == null || !extraction.isObject() || extraction.size() != fields.size()) {
            throw new IllegalStateException("Expected exactly six product extraction fields");
        }
        for (String field : fields) {
            JsonNode value = extraction.get(field);
            if (value == null || !(value.isNull() || value.isString())) {
                throw new IllegalStateException("Expected a string or null for " + field);
            }
        }
        for (var limit : Map.of("name", 150, "category", 100, "targetAudience", 255).entrySet()) {
            JsonNode value = extraction.get(limit.getKey());
            if (value.isString() && value.asText().length() > limit.getValue()) {
                throw new IllegalStateException("Product field exceeds maximum length: " + limit.getKey());
            }
        }
        for (String field : List.of("name", "category")) {
            JsonNode value = extraction.get(field);
            if (value.isString() && value.asText().isBlank()) {
                throw new IllegalStateException("Product field cannot be blank: " + field);
            }
        }
    }


    // ========================================
    // EXISTING - CREATE PRODUCT
    // ========================================

    @Transactional
    public ProductResponse createProduct(
            String googleId,
            UUID brandId,
            ProductRequest request) {

        User user = userService.getUserByGoogleId(googleId);

        Brand brand = brandRepository
                .findByIdAndCreatedBy_Id(brandId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Brand not found"
                ));

        Product product = new Product();
        product.setBrand(brand);
        product.setName(request.name().trim());
        product.setCategory(request.category().trim());
        product.setDescription(request.description());
        product.setKeyFeatures(request.keyFeatures());
        product.setTargetAudience(request.targetAudience());
        product.setImageUrl(request.imageUrl());

        return ProductResponse.from(
                productRepository.saveAndFlush(product)
        );
    }

    // ========================================
    // EXISTING - GET BRAND PRODUCTS
    // ========================================

    @Transactional(readOnly = true)
    public List<ProductResponse> getBrandProducts(
            String googleId,
            UUID brandId) {

        User user = userService.getUserByGoogleId(googleId);

        brandRepository
                .findByIdAndCreatedBy_Id(brandId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Brand not found"
                ));

        return productRepository
                .findAllByBrand_IdOrderByCreatedAtDesc(brandId)
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    // ========================================
    // EXISTING - GET PRODUCT
    // ========================================

    @Transactional(readOnly = true)
    public ProductResponse getProduct(
            String googleId,
            UUID productId) {

        User user = userService.getUserByGoogleId(googleId);

        return ProductResponse.from(
                findOwnedProduct(productId, user.getId())
        );
    }

    // ========================================
    // EXISTING - UPDATE PRODUCT
    // ========================================

    @Transactional
    public ProductResponse updateProduct(
            String googleId,
            UUID productId,
            ProductRequest request) {

        User user = userService.getUserByGoogleId(googleId);

        Product product = findOwnedProduct(
                productId, user.getId()
        );

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Product name cannot be blank"
                );
            }
            product.setName(request.name().trim());
        }

        if (request.category() != null) {
            if (request.category().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Category cannot be blank"
                );
            }
            product.setCategory(request.category().trim());
        }

        if (request.description() != null)
            product.setDescription(request.description());

        if (request.keyFeatures() != null)
            product.setKeyFeatures(request.keyFeatures());

        if (request.targetAudience() != null)
            product.setTargetAudience(request.targetAudience());

        if (request.imageUrl() != null)
            product.setImageUrl(request.imageUrl());

        return ProductResponse.from(
                productRepository.saveAndFlush(product)
        );
    }

    // ========================================
    // EXISTING - DELETE PRODUCT
    // ========================================

    @Transactional
    public void deleteProduct(
            String googleId,
            UUID productId) {

        User user = userService.getUserByGoogleId(googleId);

        Product product = findOwnedProduct(
                productId, user.getId()
        );

        storage.deleteProductImages(productId,
                product.getImages().stream().map(image -> image.getStorageUrl()).toList());
        productRepository.delete(product);
        productRepository.flush();
        log.info("stage=product_deleted productId={}", productId);
    }

    // ========================================
    // OWNERSHIP VALIDATION
    // ========================================

    private Product findOwnedProduct(
            UUID productId,
            UUID userId) {

        return productRepository
                .findByIdAndBrand_CreatedBy_Id(productId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Product not found"
                ));
    }
}
