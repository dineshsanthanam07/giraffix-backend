package com.giraffix.product.controller;


import com.giraffix.product.dto.ProductAnalysisResponse;
import com.giraffix.product.dto.ProductAnalysisUpload;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import com.giraffix.validation.Create;
import org.springframework.validation.annotation.Validated;
import com.giraffix.product.dto.ProductRequest;
import com.giraffix.product.service.ProductService;
import com.giraffix.product.dto.ProductResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="04 Products")
@RequestMapping("/api/v1")
public class ProductController {

    private final ProductService productService;
    private final com.giraffix.usage.UsageService usage;

    public ProductController(ProductService productService,com.giraffix.usage.UsageService usage) {
        this.productService = productService;
        this.usage=usage;
    }

    @PostMapping(value = "/brands/{brandId}/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID brandId,
            @Validated(Create.class) @RequestBody ProductRequest request) {

        return productService.createProduct(
                user.getSubject(), brandId, request
        );
    }

    @GetMapping("/brands/{brandId}/products")
    public List<ProductResponse> getBrandProducts(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID brandId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {

        return productService.getBrandProducts(
                user.getSubject(), brandId,page,size
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
    @PostMapping(
            value = "/analyze",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @io.swagger.v3.oas.annotations.Operation(summary = "Analyze multiple images of one product",
            description = "Upload 1–5 files using images, with 5 MB per file and 20 MB total. "
                    + "All images are analyzed together in one model call and return one product description. "
                    + "Accepts JPEG, PNG, GIF, WebP, BMP, TIFF and other installed ImageIO formats. "
                    + "BMP/TIFF are converted to PNG (first page only). "
                    + "HEIC/HEIF, AVIF, SVG and RAW need an additional decoder or conversion first.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ProductAnalysisUpload.class))))
    public ProductAnalysisResponse analyze(
            @AuthenticationPrincipal OidcUser user,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) MultipartHttpServletRequest request) {
        List<MultipartFile> legacyImages = request.getFiles("image");
        if (images != null && !legacyImages.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Use either images or image, not both");
        }
        return usage.execute(user.getSubject(),com.giraffix.usage.UsageService.Kind.PRODUCT_ANALYSIS,
                ()->productService.analyzeProductImages(images != null ? images : legacyImages));
    }

}
