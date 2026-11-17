package com.giraffix;

import com.giraffix.brand.controller.BrandController;
import com.giraffix.brand.controller.BrandLogoController;
import com.giraffix.brand.dto.BrandRequest;
import com.giraffix.brand.dto.BrandResponse;
import com.giraffix.brand.entity.Brand;
import com.giraffix.brand.repository.BrandRepository;
import com.giraffix.brand.service.BrandLogoService;
import com.giraffix.brand.service.BrandService;
import com.giraffix.user.entity.User;
import com.giraffix.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:brand_logos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "giraffix.ai.api-key=test-only",
        "spring.jpa.show-sql=false", "giraffix.storage.s3.bucket=test-brand-logos",
        "giraffix.selection.worker-enabled=false"
})
class BrandLogoTests {
    @Autowired BrandLogoService logos;
    @Autowired BrandService brands;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext webContext;
    @MockitoSpyBean BrandRepository repository;
    @MockitoBean S3Client s3;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final BrandRequest details = new BrandRequest("Example", "Description", "Retail", null, "#123456", null);
    private String subject;
    private User owner;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        subject = "logo-owner-" + UUID.randomUUID();
        owner = new User();
        owner.setGoogleId(subject);
        owner.setEmail(subject + "@example.com");
        owner = users.saveAndFlush(owner);
        objects.clear();
        when(s3.utilities()).thenReturn(S3Utilities.builder().region(Region.US_EAST_1).build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            try (var input = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), input.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            DeleteObjectRequest request = call.getArgument(0);
            objects.remove(request.key());
            return DeleteObjectResponse.builder().build();
        });
        var principal = mock(OidcUser.class);
        when(principal.getSubject()).thenReturn(subject);
        mvc = MockMvcBuilders.standaloneSetup(new BrandController(brands), new BrandLogoController(logos))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == OidcUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c,
                                                  NativeWebRequest r, WebDataBinderFactory f) { return principal; }
                }).build();
    }

    @Test
    void multipartCreateStoresS3BytesAndDatabaseMetadataAndReturnsLogoEverywhere() throws Exception {
        byte[] image = ProductImageProcessorTests.fixture("png");
        var response = mvc.perform(multipart("/api/v1/brands").file(brandPart(details))
                        .file(new MockMultipartFile("logo", "logo.png", "image/png", image)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.logoUrl").isString())
                .andReturn().getResponse().getContentAsString();
        var saved = mapper.readValue(response, BrandResponse.class);
        assertThat(saved.name()).isEqualTo("Example");
        assertThat(saved.logoUrl()).contains("/brands/logos/").endsWith(".png");
        var metadata = db.queryForMap("SELECT logo_url,logo_bucket,logo_key,logo_media_type,logo_size_bytes FROM brands WHERE id=?", saved.id());
        assertThat(metadata.get("logo_url")).isEqualTo(saved.logoUrl());
        assertThat(metadata.get("logo_bucket")).isEqualTo("test-brand-logos");
        assertThat(metadata.get("logo_media_type")).isEqualTo("image/png");
        assertThat(((Number) metadata.get("logo_size_bytes")).longValue()).isEqualTo(image.length);
        assertThat(objects.get((String) metadata.get("logo_key"))).isEqualTo(image);
        assertThat(users.findById(owner.getId()).orElseThrow().isOnboardingCompleted()).isTrue();
        mvc.perform(get("/api/v1/brands/" + saved.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(saved.logoUrl()));
        mvc.perform(get("/api/v1/brands")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].logoUrl").value(saved.logoUrl()));
        verify(s3).putObject(argThat((PutObjectRequest p) -> p.contentType().equals("image/png")
                && p.key().startsWith("brands/logos/")), any(RequestBody.class));
    }

    @Test
    void multipartPatchReplacesLogoAfterCommitAndTextPatchPreservesIt() throws Exception {
        var saved = logos.create(subject, details, image("png"));
        String previousKey = logoKey(saved.id());
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            // Deletion must observe the committed replacement, not the previous DB state.
            assertThat(logoKey(saved.id())).isNotEqualTo(previousKey);
            objects.remove(((DeleteObjectRequest) call.getArgument(0)).key());
            return DeleteObjectResponse.builder().build();
        });
        var response = mvc.perform(multipart(HttpMethod.PATCH, "/api/v1/brands/" + saved.id())
                        .file(brandPart(new BrandRequest(null, "Updated", null, null, null, null))).file(image("jpeg")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Updated"))
                .andReturn().getResponse().getContentAsString();
        var updated = mapper.readValue(response, BrandResponse.class);
        assertThat(updated.logoUrl()).isNotEqualTo(saved.logoUrl());
        assertThat(objects).hasSize(1).doesNotContainKey(previousKey);
        mvc.perform(patch("/api/v1/brands/" + saved.id()).contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.logoUrl").value(updated.logoUrl()));
    }

    @Test
    void canAttachLogoToAnExistingJsonBrandAndConvertBmp() throws Exception {
        var saved = brands.createBrand(subject, details);
        assertThat(saved.logoUrl()).isNull();
        var updated = logos.update(subject, saved.id(), new BrandRequest(null, null, null, null, null, null), image("bmp"));
        assertThat(updated.logoUrl()).endsWith(".png");
        assertThat(db.queryForObject("SELECT logo_media_type FROM brands WHERE id=?", String.class, saved.id())).isEqualTo("image/png");
    }

    @Test
    void invalidMetadataMissingEmptyOversizedAndNonImageUploadsNeverWriteS3() throws Exception {
        mvc.perform(multipart("/api/v1/brands").file(brandPart(new BrandRequest(null, null, null, null, null, null)))
                .file(image("png"))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/brands").file(brandPart(details))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/brands").file(brandPart(details))
                .file(new MockMultipartFile("logo", new byte[0]))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/brands").file(brandPart(details))
                .file(new MockMultipartFile("logo", new byte[5242881]))).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/v1/brands").file(brandPart(details))
                .file(new MockMultipartFile("logo", "not-image".getBytes()))).andExpect(status().isBadRequest());
        assertThat(brands.getMyBrands(subject)).isEmpty();
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void anotherAccountCannotUploadOrReplaceLogo() throws Exception {
        var saved = logos.create(subject, details, image("png"));
        User stranger = new User();
        stranger.setGoogleId("stranger-" + UUID.randomUUID());
        stranger.setEmail("stranger@example.com");
        users.saveAndFlush(stranger);
        clearInvocations(s3);
        assertThatThrownBy(() -> logos.update(stranger.getGoogleId(), saved.id(), details, image("png")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThat(brands.getBrand(subject, saved.id()).logoUrl()).isEqualTo(saved.logoUrl());
        verifyNoInteractions(s3);
    }

    @Test
    void uncertainS3FailureCleansNewObjectAndPreservesBrandAndOnboarding() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            objects.put(((PutObjectRequest) call.getArgument(0)).key(), new byte[]{1});
            throw S3Exception.builder().statusCode(503).build();
        });
        assertThatThrownBy(() -> logos.create(subject, details, image("png")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThat(objects).isEmpty();
        assertThat(brands.getMyBrands(subject)).isEmpty();
        assertThat(users.findById(owner.getId()).orElseThrow().isOnboardingCompleted()).isFalse();
    }

    @Test
    void databaseFailureRollsBackBrandAndOnboardingAndDeletesUploadedObject() throws Exception {
        doThrow(new IllegalStateException("Simulated database failure")).when(repository).saveAndFlush(any(Brand.class));
        assertThatThrownBy(() -> logos.create(subject, details, image("png"))).isInstanceOf(IllegalStateException.class);
        assertThat(objects).isEmpty();
        assertThat(brands.getMyBrands(subject)).isEmpty();
        assertThat(users.findById(owner.getId()).orElseThrow().isOnboardingCompleted()).isFalse();
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void failedReplacementRollsBackTextAndRetainsPreviousLogo() throws Exception {
        var saved = logos.create(subject, details, image("png"));
        String previousKey = logoKey(saved.id());
        doThrow(new IllegalStateException("Simulated database failure")).when(repository).saveAndFlush(any(Brand.class));
        assertThatThrownBy(() -> logos.update(subject, saved.id(),
                new BrandRequest(null, "Must roll back", null, null, null, null), image("png")))
                .isInstanceOf(IllegalStateException.class);
        var retained = brands.getBrand(subject, saved.id());
        assertThat(retained.logoUrl()).isEqualTo(saved.logoUrl());
        assertThat(retained.description()).isEqualTo(details.description());
        assertThat(objects).hasSize(1).containsKey(previousKey);
    }

    @Test
    void oldObjectCleanupFailureDoesNotUndoCommittedReplacement() throws Exception {
        var saved = logos.create(subject, details, image("png"));
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        var updated = logos.update(subject, saved.id(), details, image("png"));
        assertThat(updated.logoUrl()).isNotEqualTo(saved.logoUrl());
        assertThat(brands.getBrand(subject, saved.id()).logoUrl()).isEqualTo(updated.logoUrl());
    }

    @Test
    void concurrentReplacementsKeepOnlyTheCommittedLogoObject() throws Exception {
        var saved = logos.create(subject, details, image("png"));
        var gate = new java.util.concurrent.CyclicBarrier(2);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            try (var input = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), input.readAllBytes());
            }
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return PutObjectResponse.builder().build();
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> logos.update(subject, saved.id(), details, image("png")));
            var second = pool.submit(() -> logos.update(subject, saved.id(), details, image("jpeg")));
            first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(objects).hasSize(1).containsKey(logoKey(saved.id()));
    }

    @Test
    void jsonCreateStillWorksWithoutALogo() throws Exception {
        mvc.perform(post("/api/v1/brands").contentType("application/json").content(mapper.writeValueAsBytes(details)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.logoUrl").doesNotExist());
        verifyNoInteractions(s3);
    }

    @Test
    void swaggerDescribesLogoAndBrandPartsForCreationAndReplacement() throws Exception {
        var docsMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String response = docsMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var doc = mapper.readTree(response);
        for (String pointer : java.util.List.of("/paths/~1api~1v1~1brands/post",
                "/paths/~1api~1v1~1brands~1{brandId}/patch")) {
            var content = doc.at(pointer).at("/requestBody/content/multipart~1form-data");
            assertThat(content.at("/encoding/brand/contentType").asText()).isEqualTo("application/json");
            var schema = content.path("schema");
            if (schema.has("$ref")) schema = doc.at(schema.path("$ref").asText().substring(1));
            assertThat(schema.path("properties").has("brand")).isTrue();
            assertThat(schema.path("properties").path("logo").path("format").asText()).isEqualTo("binary");
            assertThat(schema.path("required").toString()).contains("brand", "logo");
        }
    }

    @Test
    void logoRoutesRequireAuthenticationAndCsrf() throws Exception {
        var securityMvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(webContext.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        securityMvc.perform(get("/api/v1/brands")).andExpect(status().isUnauthorized());
        securityMvc.perform(multipart("/api/v1/brands").file(brandPart(details)).file(image("png")))
                .andExpect(status().isForbidden());
        securityMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/brands/" + UUID.randomUUID())
                .file(brandPart(details)).file(image("png"))).andExpect(status().isForbidden());
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    private String logoKey(UUID id) {
        return db.queryForObject("SELECT logo_key FROM brands WHERE id=?", String.class, id);
    }

    private MockMultipartFile brandPart(BrandRequest request) {
        return new MockMultipartFile("brand", "brand.json", "application/json", mapper.writeValueAsBytes(request));
    }

    private MockMultipartFile image(String format) throws Exception {
        return new MockMultipartFile("logo", "logo." + format, "application/octet-stream", ProductImageProcessorTests.fixture(format));
    }
}
