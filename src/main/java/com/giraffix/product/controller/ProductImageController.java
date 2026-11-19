package com.giraffix.product.controller;

import com.giraffix.product.dto.ProductRequest;
import com.giraffix.product.dto.ProductResponse;
import com.giraffix.product.service.ProductImageService;
import com.giraffix.validation.Create;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="04 Products")
@RequestMapping("/api/v1")
public class ProductImageController {
    private final ProductImageService images;

    public ProductImageController(ProductImageService images) { this.images = images; }

    @PostMapping(value = "/brands/{brandId}/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save product details and all product images",
            description = "Send product as an application/json part and images as 1-5 file parts. "
                    + "Confirmation uploads images to S3 and stores the edited details and image URLs in the database. "
                    + "Limits: 5 MB each, 20 MB total. "
                    + "Reattach the same files submitted to analysis after reviewing its suggestions.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            encoding = @Encoding(name = "product", contentType = MediaType.APPLICATION_JSON_VALUE))))
    public ProductResponse create(@AuthenticationPrincipal OidcUser user, @PathVariable UUID brandId,
                                  @Validated(Create.class) @RequestPart("product") ProductRequest product,
                                  @RequestPart("images") List<MultipartFile> files) {
        return images.createWithImages(user.getSubject(), brandId, product, files);
    }

    @PostMapping(value = "/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Append images to an existing product",
            description = "The saved product may contain at most 5 images and 20 MB in total.")
    public ProductResponse add(@AuthenticationPrincipal OidcUser user, @PathVariable UUID productId,
                               @RequestPart("images") List<MultipartFile> files) {
        return images.addImages(user.getSubject(), productId, files);
    }

}
