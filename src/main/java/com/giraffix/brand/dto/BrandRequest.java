package com.giraffix.brand.dto;

import com.giraffix.validation.Create;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BrandRequest(

        @NotBlank(groups = Create.class)
        @Pattern(regexp = "(?s).*\\S.*", message = "Brand name cannot be blank")
        @Size(max = 150)
        String name,

        String description,
        @Size(max = 255) String industry,
        @Size(max = 255) String targetAudience,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
        String primaryColor,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
        String secondaryColor

) {}
