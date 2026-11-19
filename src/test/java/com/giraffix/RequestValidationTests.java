package com.giraffix;

import com.giraffix.brand.controller.BrandController;
import com.giraffix.brand.service.BrandService;
import com.giraffix.product.controller.ProductController;
import com.giraffix.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestValidationTests {
    private MockMvc mvc;
    private BrandService brands;
    private ProductService products;
    private static final String ID = "12345678-1234-1234-1234-123456789abc";

    @BeforeEach
    void setup() {
        brands = mock(BrandService.class);
        products = mock(ProductService.class);
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn("owner");
        mvc = MockMvcBuilders.standaloneSetup(new BrandController(brands), new ProductController(products,mock(com.giraffix.usage.UsageService.class)))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == OidcUser.class;
                    }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return user;
                    }
                }).build();
    }

    @Test
    void patchAcceptsOmittedRequiredCreateFields() throws Exception {
        mvc.perform(patch("/api/v1/brands/" + ID).contentType("application/json")
                .content("{\"description\":\"Updated\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/products/" + ID).contentType("application/json")
                .content("{\"description\":\"Updated\"}")).andExpect(status().isOk());
        verify(brands).updateBrand(eq("owner"), any(), any());
        verify(products).updateProduct(eq("owner"), any(), any());
    }

    @Test
    void createStillRequiresNamesAndProductCategory() throws Exception {
        mvc.perform(post("/api/v1/brands").contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/brands/" + ID + "/products").contentType("application/json")
                .content("{\"name\":\"Bottle\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(brands, products);
    }

    @Test
    void patchRejectsBlankNamesAndInvalidColors() throws Exception {
        mvc.perform(patch("/api/v1/brands/" + ID).contentType("application/json")
                .content("{\"name\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/brands/" + ID).contentType("application/json")
                .content("{\"primaryColor\":\"red\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/products/" + ID).contentType("application/json")
                .content("{\"category\":\"  \"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(brands, products);
    }
}
