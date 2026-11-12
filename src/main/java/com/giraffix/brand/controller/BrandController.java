package com.giraffix.brand.controller;



import com.giraffix.brand.service.BrandService;
import com.giraffix.brand.dto.*;
import com.giraffix.validation.Create;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/brands")
public class BrandController {

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrandResponse create(
            @AuthenticationPrincipal OidcUser user,
            @Validated(Create.class) @RequestBody BrandRequest request) {

        return brandService.createBrand(
                user.getSubject(), request
        );
    }

    @GetMapping
    public List<BrandResponse> getAll(
            @AuthenticationPrincipal OidcUser user) {

        return brandService.getMyBrands(user.getSubject());
    }

    @GetMapping("/{brandId}")
    public BrandResponse getOne(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID brandId) {

        return brandService.getBrand(
                user.getSubject(), brandId
        );
    }

    @PatchMapping("/{brandId}")
    public BrandResponse update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID brandId,
            @Valid @RequestBody BrandRequest request) {

        return brandService.updateBrand(
                user.getSubject(), brandId, request
        );
    }
}
