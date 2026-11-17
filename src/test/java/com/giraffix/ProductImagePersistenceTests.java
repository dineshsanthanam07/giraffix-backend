package com.giraffix;

import com.giraffix.brand.dto.BrandRequest;
import com.giraffix.brand.service.BrandService;
import com.giraffix.product.controller.ProductImageController;
import com.giraffix.product.dto.ProductRequest;
import com.giraffix.product.repository.ProductRepository;
import com.giraffix.product.service.ProductImageService;
import com.giraffix.product.service.ProductService;
import com.giraffix.user.entity.User;
import com.giraffix.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.context.transaction.TestTransaction;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:giraffix;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "giraffix.ai.api-key=test-only",
        "spring.jpa.show-sql=false", "giraffix.storage.s3.bucket=test-product-images"
})
@Transactional
class ProductImagePersistenceTests {
    @Autowired ProductImageService images;
    @Autowired ProductService products;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository users;
    @Autowired BrandService brands;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean S3Client s3;
    private final java.util.Map<String, byte[]> uploaded = new java.util.HashMap<>();
    private MockMvc mvc;
    private UUID brandId;
    private final String owner = "image-owner";
    private final ProductRequest details = new ProductRequest("Bottle", "Drinkware", "User-edited description", null, null, null);

    @BeforeEach
    void setup() throws Exception {
        when(s3.utilities()).thenReturn(S3Utilities.builder().region(Region.US_EAST_1).build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            try (var stream = body.contentStreamProvider().newStream()) { uploaded.put(request.key(), stream.readAllBytes()); }
            return PutObjectResponse.builder().build();
        });
        uploaded.clear();
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            DeleteObjectRequest request = call.getArgument(0);
            uploaded.remove(request.key());
            return DeleteObjectResponse.builder().build();
        });
        user(owner);
        brandId = brands.createBrand(owner, new BrandRequest("Example", null, null, null, null, null)).id();
        OidcUser principal = mock(OidcUser.class);
        when(principal.getSubject()).thenReturn(owner);
        mvc = MockMvcBuilders.standaloneSetup(new ProductImageController(images))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == OidcUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c,
                            NativeWebRequest r, WebDataBinderFactory f) { return principal; }
                }).build();
    }

    @Test
    void confirmationUploadsEveryImageAndStoresOnlyS3Urls() throws Exception {
        byte[] front = ProductImageProcessorTests.fixture("png");
        byte[] back = ProductImageProcessorTests.fixture("jpeg");
        var response = mvc.perform(multipart("/api/v1/brands/" + brandId + "/products")
                        .file(new MockMultipartFile("product", "", "application/json", JsonMapper.builder().build().writeValueAsBytes(details)))
                        .file(new MockMultipartFile("images", "front.png", "image/png", front))
                        .file(new MockMultipartFile("images", "back.jpg", "image/jpeg", back)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.images.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID productId = UUID.fromString(JsonMapper.builder().build().readTree(response).path("id").asText());
        entityManager.flush();
        entityManager.clear();
        var saved = products.getProduct(owner, productId);
        assertThat(saved.description()).isEqualTo("User-edited description");
        assertThat(saved.images()).hasSize(2);
        assertThat(saved.images()).extracting(image -> image.position()).containsExactly(0, 1);
        assertThat(jdbc.queryForList("SELECT storage_url FROM product_images WHERE product_id = ? ORDER BY position", String.class, productId))
                .containsExactly(saved.images().get(0).url(), saved.images().get(1).url());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images WHERE product_id = ? AND image_data IS NOT NULL", Long.class, productId)).isZero();
        assertThat(uploaded.get(java.net.URI.create(saved.images().get(0).url()).getPath().substring(1))).isEqualTo(front);
        assertThat(uploaded.get(java.net.URI.create(saved.images().get(1).url()).getPath().substring(1))).isEqualTo(back);
    }

    @Test
    void appendingAndEditingPreserveExistingImageUrls() throws Exception {
        var saved = images.createWithImages(owner, brandId, details, List.of(file("png")));
        var appended = images.addImages(owner, saved.id(), List.of(file("bmp")));
        assertThat(appended.images()).hasSize(2);
        assertThat(appended.images().get(0).url()).isEqualTo(saved.images().get(0).url());
        assertThat(appended.images().get(1).url()).endsWith(".png");
        products.updateProduct(owner, saved.id(), new ProductRequest(null, null, "Updated", null, null, null));
        entityManager.flush();
        entityManager.clear();
        assertThat(products.getProduct(owner, saved.id()).images()).hasSize(2);
    }

    @Test
    void otherUsersCannotSaveImagesUnderYourProductOrBrand() throws Exception {
        user("stranger");
        var saved = images.createWithImages(owner, brandId, details, List.of(file("png")));
        assertNotFound(() -> images.addImages("stranger", saved.id(), List.of(file("png"))));
        assertNotFound(() -> images.createWithImages("stranger", brandId, details, List.of(file("png"))));
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void invalidImagesDoNotCreateAProductOrCallS3() throws Exception {
        long before = productRepository.count();
        assertThatThrownBy(() -> images.createWithImages(owner, brandId, details,
                List.of(file("png"), new MockMultipartFile("images", "broken".getBytes()))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(productRepository.count()).isEqualTo(before);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void s3FailureRollsBackTheDatabaseSave() throws Exception {
        long before = productRepository.count();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(() -> images.createWithImages(owner, brandId, details, List.of(file("png"))))
                .isInstanceOf(ResponseStatusException.class);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThat(productRepository.count()).isEqualTo(before);
    }

    @Test
    void imageCountLimitIncludesPreviouslySavedImages() throws Exception {
        var saved = images.createWithImages(owner, brandId, details, java.util.Collections.nCopies(5, file("png")));
        assertThatThrownBy(() -> images.addImages(owner, saved.id(), List.of(file("png"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(products.getProduct(owner, saved.id()).images()).hasSize(5);
    }

    @Test
    void deletingAProductRemovesItsImageRowsAndS3Objects() throws Exception {
        var saved = images.createWithImages(owner, brandId, details, List.of(file("png"), file("jpeg")));
        products.deleteProduct(owner, saved.id());
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images WHERE product_id = ?", Long.class, saved.id())).isZero();
        assertThat(productRepository.existsById(saved.id())).isFalse();
        assertThat(uploaded).isEmpty();
        verify(s3, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void partialS3DeletionFailureRetainsProductAndAllowsRetry() throws Exception {
        var saved = images.createWithImages(owner, brandId, details, List.of(file("png"), file("jpeg")));
        // Commit the fixture so rollback can be checked outside the failed transaction.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        var failedKey = java.net.URI.create(saved.images().get(1).url()).getPath().substring(1);
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            DeleteObjectRequest request = call.getArgument(0);
            if (request.key().equals(failedKey) && failOnce.getAndSet(false)) {
                throw S3Exception.builder().statusCode(503).build();
            }
            uploaded.remove(request.key());
            return DeleteObjectResponse.builder().build();
        });
        assertThatThrownBy(() -> products.deleteProduct(owner, saved.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(productRepository.existsById(saved.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images WHERE product_id = ?", Long.class, saved.id())).isEqualTo(2);
        assertThat(uploaded).hasSize(1);
        products.deleteProduct(owner, saved.id());
        assertThat(productRepository.existsById(saved.id())).isFalse();
        assertThat(uploaded).isEmpty();
        TestTransaction.start();
        jdbc.update("DELETE FROM brands WHERE id = ?", brandId);
        jdbc.update("DELETE FROM users WHERE google_id = ?", owner);
        TestTransaction.flagForCommit();
    }

    @Test
    void otherUsersCannotDeleteYourProductImages() throws Exception {
        user("stranger");
        var saved = images.createWithImages(owner, brandId, details, List.of(file("png")));
        assertNotFound(() -> products.deleteProduct("stranger", saved.id()));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(uploaded).hasSize(1);
    }

    @Test
    void productDetailsAreValidatedBeforeUploading() throws Exception {
        mvc.perform(multipart("/api/v1/brands/" + brandId + "/products")
                        .file(new MockMultipartFile("product", "", "application/json", "{}".getBytes())).file(file("png")))
                .andExpect(status().isBadRequest());
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    private MockMultipartFile file(String format) throws Exception {
        return new MockMultipartFile("images", "photo." + format, "application/octet-stream", ProductImageProcessorTests.fixture(format));
    }

    private void user(String subject) {
        User user = new User();
        user.setGoogleId(subject);
        user.setEmail(subject + "@example.com");
        users.saveAndFlush(user);
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
