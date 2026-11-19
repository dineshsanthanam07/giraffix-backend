package com.giraffix;

import com.giraffix.concept.controller.ConceptController;
import com.giraffix.concept.entity.Concept;
import com.giraffix.concept.model.ConceptAnalysis;
import com.giraffix.concept.port.ConceptAnalyzer;
import com.giraffix.concept.repository.ConceptRepository;
import com.giraffix.concept.service.ConceptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:giraffix;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "giraffix.ai.api-key=test-only",
        "spring.jpa.show-sql=false", "giraffix.storage.s3.bucket=test-concept-images"
})
@Transactional
class ConceptPersistenceTests {
    @Autowired ConceptService service;
    @Autowired ConceptRepository repository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ConceptAnalyzer analyzer;
    @MockitoBean S3Client s3;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(s3.utilities()).thenReturn(S3Utilities.builder().region(Region.US_EAST_1).build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(analyzer.analyze(any(byte[].class), anyString())).thenReturn(ConceptFixtures.analysis());
        OidcUser principal = mock(OidcUser.class);
        when(principal.getSubject()).thenReturn("concept-admin");
        var usage=mock(com.giraffix.usage.UsageService.class);
        when(usage.execute(anyString(),any(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(2)).get());
        mvc = MockMvcBuilders.standaloneSetup(new ConceptController(service,usage))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == OidcUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c,
                            NativeWebRequest r, WebDataBinderFactory f) { return principal; }
                }).build();
    }

    @Test
    void createsConceptUploadsToOwnPrefixAndPersistsCompleteAnalysis() throws Exception {
        byte[] bytes = ProductImageProcessorTests.fixture("png");
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            assertThat(request.bucket()).isEqualTo("test-concept-images");
            assertThat(request.key()).startsWith("concept/").endsWith(".png");
            assertThat(request.contentType()).isEqualTo("image/png");
            try (var input = body.contentStreamProvider().newStream()) {
                assertThat(input.readAllBytes()).isEqualTo(bytes);
            }
            return PutObjectResponse.builder().build();
        });
        String response = mvc.perform(multipart("/api/v1/concepts")
                        .file(new MockMultipartFile("image", "reference.png", "image/png", bytes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.concept.concept_name").value("Floating Frame Of Possibility"))
                .andExpect(jsonPath("$.image_url").isString()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(response).path("id").asText());
        String storedJson = jdbc.queryForObject("SELECT analysis_json FROM concepts WHERE id = ?", String.class, id);
        assertThat(mapper.readTree(storedJson)).isEqualTo(mapper.readTree(ConceptFixtures.VALID));
        assertThat(jdbc.queryForObject("SELECT created_by FROM concepts WHERE id = ?", String.class, id)).isEqualTo("concept-admin");
        mvc.perform(get("/api/v1/concepts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.concept.transfer_examples.length()").value(3));
        mvc.perform(get("/api/v1/concepts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));
    }

    @Test
    void savesUnusableResultWithImageAndReason() throws Exception {
        when(analyzer.analyze(any(byte[].class), anyString()))
                .thenReturn(mapper.readValue(ConceptFixtures.UNUSABLE, ConceptAnalysis.class));
        var saved = service.create("concept-admin", image("png"));
        assertThat(saved.usable()).isFalse();
        assertThat(saved.concept()).isNull();
        assertThat(saved.unusableReason()).isNotBlank();
        assertThat(saved.imageUrl()).contains("/concept/");
        assertThat(jdbc.queryForObject("SELECT usable FROM concepts WHERE id = ?", Boolean.class, saved.id())).isFalse();
    }

    @Test
    void convertsBmpBeforeBothAnalysisAndUpload() throws Exception {
        service.create("concept-admin", image("bmp"));
        verify(analyzer).analyze(any(byte[].class), eq("image/png"));
        verify(s3).putObject(argThat((PutObjectRequest request) -> request.contentType().equals("image/png")),
                any(RequestBody.class));
    }

    @Test
    void s3FailureNeverCreatesDatabaseRowAndAttemptsCleanup() throws Exception {
        long before = repository.count();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(() -> service.create("concept-admin", image("png")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(repository.count()).isEqualTo(before);
        verify(s3).deleteObject(argThat((DeleteObjectRequest request) -> request.key().startsWith("concept/")));
    }

    @Test
    void rejectsMissingImageOversizedInputAndInvalidPagination() throws Exception {
        mvc.perform(multipart("/api/v1/concepts")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/concepts")
                        .file(new MockMultipartFile("image", new byte[5242881])))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/api/v1/concepts?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/concepts?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/concepts/" + UUID.randomUUID())).andExpect(status().isNotFound());
        verifyNoInteractions(analyzer);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    private MockMultipartFile image(String format) throws Exception {
        return new MockMultipartFile("image", "reference." + format, "application/octet-stream",
                ProductImageProcessorTests.fixture(format));
    }
}
