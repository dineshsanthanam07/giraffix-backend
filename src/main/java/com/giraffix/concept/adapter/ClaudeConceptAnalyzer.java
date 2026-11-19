package com.giraffix.concept.adapter;

import com.giraffix.concept.model.ConceptAnalysis;
import com.giraffix.concept.port.ConceptAnalyzer;
import com.giraffix.concept.service.ConceptAnalysisValidator;
import com.giraffix.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class ClaudeConceptAnalyzer implements ConceptAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(ClaudeConceptAnalyzer.class);
    private final RestClient client;
    private final String model;
    private final ObjectMapper mapper;
    private final ConceptAnalysisValidator validator;
    private final String prompt;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.giraffix.prompt.PromptTemplates templates;
    private final int maxTokens;

    public ClaudeConceptAnalyzer(RestClient.Builder builder, ClaudeProperties properties, ObjectMapper mapper,
                                 ConceptAnalysisValidator validator,
                                 @Value("classpath:prompts/concept-analysis.txt") Resource prompt,
                                 @Value("${giraffix.concept.model:claude-sonnet-5-5}") String model,
                                 @Value("${giraffix.concept.max-tokens:2048}") int maxTokens) throws IOException {
        this.model = model;
        this.mapper = mapper;
        this.validator = validator;
        this.maxTokens = maxTokens;
        try (var stream = prompt.getInputStream()) {
            this.prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        client = builder.clone().baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.apiKey())
                .defaultHeader("anthropic-version", properties.apiVersion()).build();
    }

    @Override
    public ConceptAnalysis analyze(byte[] image, String mediaType) {
        String prompt=templates==null?this.prompt:templates.current(com.giraffix.prompt.PromptTemplates.Kind.CONCEPT).content();
        var body = Map.of("model", model, "max_tokens", maxTokens,
                "output_config", Map.of("format", Map.of("type", "json_schema", "schema", validator.providerSchema())),
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "image", "source", Map.of("type", "base64", "media_type", mediaType,
                                "data", Base64.getEncoder().encodeToString(image))),
                        Map.of("type", "text", "text", prompt)))));
        String response;
        log.info("stage=concept_model_started model={}", model);
        try {
            com.giraffix.usage.UsageService.providerStarted(model);
            response = client.post().uri("/messages").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
        } catch (RestClientException ex) {
            if(ex instanceof RestClientResponseException http)com.giraffix.usage.UsageService.providerRejected(http.getStatusCode().value());
            log.error("stage=concept_model_failed errorType={} status={}", ex.getClass().getSimpleName(),
                    ex instanceof RestClientResponseException http ? http.getStatusCode().value() : 0);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Concept analysis provider failed");
        }
        try {
            var root = mapper.readTree(response == null ? "" : response);
            if(root!=null)com.giraffix.usage.UsageService.providerUsage(model,root.path("id").asText(null),
                    root.path("usage").path("input_tokens").asLong(-1),root.path("usage").path("output_tokens").asLong(-1));
            if (!"end_turn".equals(root.path("stop_reason").asText()) || !root.path("content").isArray()) {
                throw new IllegalArgumentException("Incomplete concept analysis");
            }
            var text = new StringBuilder();
            for (var block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            }
            var result = mapper.readTree(text.toString());
            validator.validate(result);
            log.info("stage=concept_model_completed usable={}", result.path("usable").asBoolean());
            return mapper.treeToValue(result, ConceptAnalysis.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            log.warn("stage=concept_model_invalid errorType={}", ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid concept analysis response");
        }
    }
}
