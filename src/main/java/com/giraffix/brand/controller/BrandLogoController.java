package com.giraffix.brand.controller;

import com.giraffix.brand.dto.BrandRequest;
import com.giraffix.brand.dto.BrandResponse;
import com.giraffix.brand.service.BrandLogoService;
import com.giraffix.validation.Create;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/brands")
public class BrandLogoController {
    private final BrandLogoService logos;

    public BrandLogoController(BrandLogoService logos) { this.logos = logos; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a brand with a logo",
            description = "Send brand as an application/json part and logo as one image file (5 MB maximum). "
                    + "Stores the image in S3 and its URL and metadata with the brand.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            encoding = @Encoding(name = "brand", contentType = MediaType.APPLICATION_JSON_VALUE))))
    public BrandResponse create(@AuthenticationPrincipal OidcUser user,
                                @Validated(Create.class) @RequestPart("brand") BrandRequest brand,
                                @RequestPart("logo") MultipartFile logo) {
        return logos.create(user.getSubject(), brand, logo);
    }

    @PatchMapping(value = "/{brandId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Update brand details and replace its logo",
            description = "Send brand as an application/json part ({} for logo only) and logo as one image file. "
                    + "The previous logo is deleted after the replacement commits.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            encoding = @Encoding(name = "brand", contentType = MediaType.APPLICATION_JSON_VALUE))))
    public BrandResponse update(@AuthenticationPrincipal OidcUser user, @PathVariable UUID brandId,
                                @Valid @RequestPart("brand") BrandRequest brand,
                                @RequestPart("logo") MultipartFile logo) {
        return logos.update(user.getSubject(), brandId, brand, logo);
    }
}
