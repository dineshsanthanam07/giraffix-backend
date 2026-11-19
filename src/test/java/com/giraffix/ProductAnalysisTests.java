package com.giraffix;

import com.giraffix.brand.repository.BrandRepository;
import com.giraffix.config.ClaudeProperties;
import com.giraffix.product.controller.ProductController;
import com.giraffix.product.repository.ProductRepository;
import com.giraffix.product.service.ProductService;
import com.giraffix.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class ProductAnalysisTests {
    private ProductService service;
    private ProductRepository products;
    private MockRestServiceServer server;
    private MockMvc mvc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        products = mock(ProductRepository.class);
        service = new ProductService(products, mock(BrandRepository.class), mock(UserService.class),
                mapper, new ClaudeProperties("test-key", "https://example.invalid/v1",
                        "test-model", "2023-06-01", 1024, 5242880), builder,
                new ClassPathResource("prompts/product-analysis.txt"),
                new ClassPathResource("schemas/product-visual-extraction.json"),
                mock(com.giraffix.product.service.ProductImageDeletionQueue.class));
        var usage=mock(com.giraffix.usage.UsageService.class);
        when(usage.execute(anyString(),any(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(2)).get());
        var user=mock(org.springframework.security.oauth2.core.oidc.user.OidcUser.class);when(user.getSubject()).thenReturn("analysis-owner");
        mvc = MockMvcBuilders.standaloneSetup(new ProductController(service,usage))
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(org.springframework.core.MethodParameter p){return p.getParameterType()==org.springframework.security.oauth2.core.oidc.user.OidcUser.class;}
                    public Object resolveArgument(org.springframework.core.MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer m,
                            org.springframework.web.context.request.NativeWebRequest r,org.springframework.web.bind.support.WebDataBinderFactory f){return user;}
                }).build();
    }

    @Test
    void requestsStructuredOutputAndReturnsProductFields(CapturedOutput output) throws Exception {
        String extraction = """
                {"name":"Bottle","category":"Drinkware","description":"Reusable bottle",
                 "keyFeatures":"Screw cap","targetAudience":null,"imageUrl":null}
                """;
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.output_config.format.schema.required").value(
                        org.hamcrest.Matchers.contains("name", "category", "description",
                                "keyFeatures", "targetAudience", "imageUrl")))
                .andExpect(jsonPath("$.messages[0].content[0].source.media_type").value("image/png"))
                .andRespond(withSuccess(envelope(extraction), MediaType.APPLICATION_JSON));

        String response = mvc.perform(multipart("/api/v1/analyze").file(image()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response)).isEqualTo(mapper.readTree(extraction));
        verifyNoInteractions(products);
        server.verify();
        assertThat(output.getOut()).contains("stage=model_api_call_started", "stage=model_api_response_received status=200",
                "stage=extraction_validated", "stage=analysis_completed status=200");
        assertThat(output.getAll()).doesNotContain("test-key", "iVBORw0KGgo");
    }

    @Test
    void preservesAllRequiredNullFields(CapturedOutput output) throws Exception {
        String extraction = """
                {"name":null,"category":null,"description":null,
                 "keyFeatures":null,"targetAudience":null,"imageUrl":null}
                """;
        respond(extraction);
        String response = mvc.perform(multipart("/api/v1/analyze").file(image()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response)).isEqualTo(mapper.readTree(extraction));
        server.verify();
        assertThat(output.getOut()).contains("stage=all_fields_null source=model_response");
    }

    @Test
    void logsProviderHttpFailureWithoutLeakingResponseOrCredentials(CapturedOutput output) {
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header("request-id", "provider-request-test")
                        .body("provider-private-error test-key").contentType(MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> service.analyzeProduct(image()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(output.getOut()).contains("stage=model_api_http_error status=401",
                "providerRequestId=provider-request-test", "stage=analysis_failed status=502");
        assertThat(output.getAll()).doesNotContain("provider-private-error", "test-key", "iVBORw0KGgo");
        server.verify();
    }

    @Test
    void logsRejectedUploadsWithoutCallingProvider(CapturedOutput output) {
        assertThatThrownBy(() -> service.analyzeProduct(new MockMultipartFile(
                "image", "bad\nfilename.jpg", "image/jpeg", "not an image".getBytes())))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(output.getOut()).contains("filename=bad filename.jpg", "stage=analysis_failed status=400")
                .doesNotContain("stage=model_api_call_started");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":null,\"category\":null,\"description\":null,\"keyFeatures\":null,\"targetAudience\":null}",
            "{\"name\":23,\"category\":null,\"description\":null,\"keyFeatures\":null,\"targetAudience\":null,\"imageUrl\":null}",
            "{\"name\":null,\"category\":null,\"description\":null,\"keyFeatures\":[],\"targetAudience\":null,\"imageUrl\":null}",
            "{\"name\":null,\"category\":null,\"description\":null,\"keyFeatures\":null,\"targetAudience\":null,\"imageUrl\":null,\"primary_colour\":\"Blue\"}",
            "{\"name\":\"  \",\"category\":null,\"description\":null,\"keyFeatures\":null,\"targetAudience\":null,\"imageUrl\":null}",
            "{\"product_name\":null,\"product_variant\":null,\"primary_colour\":null,\"secondary_colours\":[]}",
            "null"
    })
    void rejectsPayloadsOutsideTheSchema(String extraction) {
        respond(extraction);
        assertThatThrownBy(() -> service.analyzeProduct(image()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verifyNoInteractions(products);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "category", "targetAudience"})
    void rejectsSuggestionsExceedingProductFieldLimits(String field) {
        var extraction = mapper.createObjectNode();
        for (String key : List.of("name", "category", "description", "keyFeatures", "targetAudience", "imageUrl")) {
            extraction.putNull(key);
        }
        int maxLength = Map.of("name", 150, "category", 100, "targetAudience", 255).get(field);
        extraction.put(field, "x".repeat(maxLength + 1));
        respond(mapper.writeValueAsString(extraction));
        assertThatThrownBy(() -> service.analyzeProduct(image()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        server.verify();
    }

    private void respond(String extraction) {
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andRespond(withSuccess(envelope(extraction), MediaType.APPLICATION_JSON));
    }

    @Test
    void sendsAllViewsInOneModelCallAndReturnsOneCombinedProduct(CapturedOutput output) throws Exception {
        byte[] front = ProductImageProcessorTests.fixture("jpeg");
        byte[] back = ProductImageProcessorTests.fixture("gif");
        String combined = """
                {"name":"Bottle","category":"Drinkware","description":"Bottle with screw cap and visible label",
                 "keyFeatures":"Screw cap; readable label","targetAudience":null,"imageUrl":null}
                """;
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].content.length()").value(3))
                .andExpect(jsonPath("$.messages[0].content[0].source.media_type").value("image/jpeg"))
                .andExpect(jsonPath("$.messages[0].content[0].source.data").value(java.util.Base64.getEncoder().encodeToString(front)))
                .andExpect(jsonPath("$.messages[0].content[1].source.media_type").value("image/gif"))
                .andExpect(jsonPath("$.messages[0].content[1].source.data").value(java.util.Base64.getEncoder().encodeToString(back)))
                .andExpect(jsonPath("$.messages[0].content[2].text").value(org.hamcrest.Matchers.containsString("ONE combined result")))
                .andRespond(withSuccess(envelope(combined), MediaType.APPLICATION_JSON));
        String response = mvc.perform(multipart("/api/v1/analyze")
                        .file(new MockMultipartFile("images", "front.jpg", "image/jpeg", front))
                        .file(new MockMultipartFile("images", "back.gif", "image/gif", back)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response)).isEqualTo(mapper.readTree(combined));
        assertThat(output.getOut()).contains("stage=analysis_started imageCount=2", "imageIndex=1", "imageIndex=2", "imageCount=2 totalImageBytes=");
        verifyNoInteractions(products);
        server.verify();
    }

    @Test
    void rejectsMissingImagesMixedAliasesEmptyFilesAndTooManyFiles() throws Exception {
        mvc.perform(multipart("/api/v1/analyze")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/analyze").file(image()).file(new MockMultipartFile(
                "images", "new.png", "image/png", image().getBytes()))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/analyze").file(new MockMultipartFile(
                "images", "empty.jpg", "image/jpeg", new byte[0]))).andExpect(status().isBadRequest());
        var request = multipart("/api/v1/analyze");
        for (int i = 0; i < 6; i++) request.file(new MockMultipartFile("images", image().getBytes()));
        mvc.perform(request).andExpect(status().isBadRequest());
        server.verify();
    }

    @Test
    void rejectsOneInvalidViewWithoutMakingAPartialModelCall() throws Exception {
        mvc.perform(multipart("/api/v1/analyze")
                        .file(new MockMultipartFile("images", image().getBytes()))
                        .file(new MockMultipartFile("images", "bad.jpg", "image/jpeg", "bad data".getBytes())))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test
    void enforcesAggregateUploadLimitBeforeReadingFiles() throws Exception {
        MultipartFile large = mock(MultipartFile.class);
        when(large.getSize()).thenReturn(5242880L);
        when(large.isEmpty()).thenReturn(false);
        assertThatThrownBy(() -> service.analyzeProductImages(java.util.Collections.nCopies(5, large)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        verify(large, never()).getBytes();
        server.verify();
    }

    @Test
    void enforcesPerImageLimitBeforeReadingFiles() throws Exception {
        MultipartFile large = mock(MultipartFile.class);
        when(large.getSize()).thenReturn(5242881L);
        when(large.isEmpty()).thenReturn(false);
        assertThatThrownBy(() -> service.analyzeProductImages(List.of(large)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        verify(large, never()).getBytes();
        server.verify();
    }

    @Test
    void enforcesAggregateLimitAfterImageConversion() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxTotalImageSizeBytes", 20L);
        byte[] compactImage = ProductImageProcessorTests.fixture("wbmp");
        assertThat(compactImage.length).isLessThanOrEqualTo(20);
        assertThatThrownBy(() -> service.analyzeProductImages(List.of(new MockMultipartFile("images", compactImage))))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(ex.getReason()).contains("Converted");
                });
        server.verify();
    }

    @Test
    void sendsConvertedBmpAsPngEvenWithIncorrectFilenameAndMimeType() throws Exception {
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andExpect(jsonPath("$.messages[0].content[0].source.media_type").value("image/png"))
                .andExpect(jsonPath("$.messages[0].content[0].source.data").value(
                        org.hamcrest.Matchers.startsWith("iVBORw0KGgo")))
                .andRespond(withSuccess(envelope("""
                        {"name":null,"category":null,"description":null,
                         "keyFeatures":null,"targetAudience":null,"imageUrl":null}
                        """), MediaType.APPLICATION_JSON));
        mvc.perform(multipart("/api/v1/analyze").file(new MockMultipartFile(
                "image", "renamed.jpg", "image/jpeg", ProductImageProcessorTests.fixture("bmp"))))
                .andExpect(status().isOk());
        server.verify();
    }

    private String envelope(String extraction) {
        return mapper.writeValueAsString(Map.of("stop_reason", "end_turn", "content",
                List.of(Map.of("type", "text", "text", extraction))));
    }

    @Test void usesPublishedProductInstructionsWithoutChangingOutputSchema() {
        var templates=mock(com.giraffix.prompt.PromptTemplates.class);
        var kind=com.giraffix.prompt.PromptTemplates.Kind.PRODUCT;
        when(templates.current(kind)).thenReturn(new com.giraffix.prompt.PromptTemplates.Revision(kind.key,2,"ADMIN PRODUCT INSTRUCTIONS","admin",java.time.Instant.now()));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"prompts",templates);
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andExpect(jsonPath("$.messages[0].content[1].text").value("ADMIN PRODUCT INSTRUCTIONS"))
                .andExpect(jsonPath("$.output_config.format.schema.additionalProperties").value(false))
                .andRespond(withSuccess(envelope("{\"name\":null,\"category\":null,\"description\":null,\"keyFeatures\":null,\"targetAudience\":null,\"imageUrl\":null}"),MediaType.APPLICATION_JSON));
        service.analyzeProduct(image());server.verify();
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "product.png", "image/png",
                java.util.Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII="));
    }
}
