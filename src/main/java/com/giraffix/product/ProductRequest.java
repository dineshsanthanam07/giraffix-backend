package com.giraffix.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(

        @NotBlank @Size(max = 150)
        String name,

        @NotBlank @Size(max = 100)
        String category,

        String description,
        String keyFeatures,

        @Size(max = 255)
        String targetAudience,
        @NotBlank
        String imageUrl
) {}