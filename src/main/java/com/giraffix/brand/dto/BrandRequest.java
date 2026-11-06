package com.giraffix.brand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BrandRequest(

        @NotBlank
        @Size(max = 150)
        String name,

        String description,
        String industry,
        String targetAudience,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
        String primaryColor,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
        String secondaryColor

) {}