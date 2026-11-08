package com.giraffix.product;


import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/brands/{brandId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID brandId,
            @Valid @RequestBody ProductRequest request) {

        return productService.createProduct(
                user.getSubject(), brandId, request
        );
    }

    @GetMapping("/brands/{brandId}/products")
    public List<ProductResponse> getBrandProducts(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID brandId) {

        return productService.getBrandProducts(
                user.getSubject(), brandId
        );
    }

    @GetMapping("/products/{productId}")
    public ProductResponse getProduct(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID productId) {

        return productService.getProduct(
                user.getSubject(), productId
        );
    }

    @PatchMapping("/products/{productId}")
    public ProductResponse update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID productId,
            @Valid @RequestBody ProductRequest request) {

        return productService.updateProduct(
                user.getSubject(), productId, request
        );
    }


    @DeleteMapping("/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID productId) {

        productService.deleteProduct(
                user.getSubject(), productId
        );
    }

}
