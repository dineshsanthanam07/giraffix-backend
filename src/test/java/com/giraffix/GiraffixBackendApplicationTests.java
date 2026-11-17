package com.giraffix;

import com.giraffix.brand.dto.BrandRequest;
import com.giraffix.brand.service.BrandService;
import com.giraffix.product.dto.ProductRequest;
import com.giraffix.product.service.ProductService;
import com.giraffix.user.entity.User;
import com.giraffix.user.repository.UserRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:giraffix;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "giraffix.ai.api-key=test-only",
        "spring.jpa.show-sql=false"
})
@Transactional
class GiraffixBackendApplicationTests {
    @Autowired UserRepository users;
    @Autowired BrandService brands;
    @Autowired ProductService products;
    @Autowired Validator validator;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    @Test
    void swaggerDocumentsSelectionRequestFieldsInsteadOfJsonNodeInternals() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext).build();
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        var operation = objectMapper.readTree(response).at("/paths/~1api~1v1~1selection-requests/post");
        var content = operation.at("/requestBody/content/application~1json");
        var schema = content.path("schema");
        var doc = objectMapper.readTree(response);
        if (schema.has("$ref")) schema = doc.at(schema.path("$ref").asText().substring(1));
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "productId", "intent", "format", "controls", "conceptId", "aspectRatio", "quality");
        assertThat(schema.path("required").size()).isEqualTo(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("productId");
        var example = content.at("/examples/automaticSelection/value");
        assertThat(example.path("productId").asText()).isNotBlank();
        assertThat(example.size()).isEqualTo(1);
    }

    @Test
    void swaggerDocumentsUploadPartsAndProductJsonEncoding() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext).build();
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        var doc = objectMapper.readTree(response);
        var schema = doc.path("paths").path("/api/v1/analyze").path("post")
                .path("requestBody").path("content").path("multipart/form-data").path("schema");
        if (schema.has("$ref")) schema = doc.at(schema.path("$ref").asText().substring(1));
        assertThat(schema.path("properties").size()).isEqualTo(1);
        assertThat(schema.path("properties").has("images")).isTrue();
        assertThat(schema.path("required").toString()).contains("images");
        var images = schema.path("properties").path("images");
        assertThat(images.path("type").asText()).isEqualTo("array");
        assertThat(images.path("items").path("type").asText()).isEqualTo("string");
        assertThat(images.path("items").path("format").asText()).isEqualTo("binary");
        var multipart = doc.path("paths").path("/api/v1/brands/{brandId}/products").path("post")
                .path("requestBody").path("content").path("multipart/form-data");
        assertThat(multipart.path("encoding").path("product").path("contentType").asText())
                .isEqualTo("application/json");
        var createSchema = multipart.path("schema");
        if (createSchema.has("$ref")) createSchema = doc.at(createSchema.path("$ref").asText().substring(1));
        assertThat(createSchema.path("properties").has("product")).isTrue();
        assertThat(createSchema.path("properties").has("images")).isTrue();
        var conceptUpload = doc.path("paths").path("/api/v1/concepts").path("post")
                .path("requestBody").path("content").path("multipart/form-data").path("schema");
        if (conceptUpload.has("$ref")) conceptUpload = doc.at(conceptUpload.path("$ref").asText().substring(1));
        assertThat(conceptUpload.path("properties").path("image").path("format").asText()).isEqualTo("binary");
        assertThat(conceptUpload.path("required").toString()).contains("image");
    }

    @Test
    void migratedSchemaSupportsOnboardingAndProductLifecycle() {
        User owner = user("owner");
        var brand = brands.createBrand(owner.getGoogleId(),
                new BrandRequest("Example", null, null, null, "#123456", null));
        assertThat(users.findById(owner.getId()).orElseThrow().isOnboardingCompleted()).isTrue();

        var product = products.createProduct(owner.getGoogleId(), brand.id(),
                new ProductRequest("Bottle", "Drinkware", null, null, null, null));
        var updated = products.updateProduct(owner.getGoogleId(), product.id(),
                new ProductRequest(null, null, "Reusable bottle", null, null, null));
        assertThat(updated.name()).isEqualTo("Bottle");
        assertThat(updated.description()).isEqualTo("Reusable bottle");
        assertThat(products.getBrandProducts(owner.getGoogleId(), brand.id())).hasSize(1);
        products.deleteProduct(owner.getGoogleId(), product.id());
        assertThat(products.getBrandProducts(owner.getGoogleId(), brand.id())).isEmpty();
    }

    @Test
    void otherUsersCannotAccessOrChangeOwnedResources() {
        User owner = user("owner");
        User stranger = user("stranger");
        var brand = brands.createBrand(owner.getGoogleId(),
                new BrandRequest("Private", null, null, null, null, null));
        var product = products.createProduct(owner.getGoogleId(), brand.id(),
                new ProductRequest("Bottle", "Drinkware", null, null, null, null));

        assertNotFound(() -> brands.getBrand(stranger.getGoogleId(), brand.id()));
        assertNotFound(() -> products.getBrandProducts(stranger.getGoogleId(), brand.id()));
        assertNotFound(() -> products.getProduct(stranger.getGoogleId(), product.id()));
        assertNotFound(() -> products.updateProduct(stranger.getGoogleId(), product.id(),
                new ProductRequest(null, null, "Changed", null, null, null)));
        assertNotFound(() -> products.deleteProduct(stranger.getGoogleId(), product.id()));
        assertThat(products.getProduct(owner.getGoogleId(), product.id()).description()).isNull();
    }

    private User user(String subject) {
        User user = new User();
        user.setGoogleId(subject);
        user.setEmail(subject + "@example.com");
        return users.saveAndFlush(user);
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
