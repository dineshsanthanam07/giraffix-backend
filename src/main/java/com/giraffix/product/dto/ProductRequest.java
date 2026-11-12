
package com.giraffix.product.dto;

import com.giraffix.validation.Create;
import jakarta.validation.constraints.Pattern;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(

        @NotBlank(message = "Product name is required", groups = Create.class)
        @Pattern(regexp = "(?s).*\\S.*", message = "Product name cannot be blank")
        @Size(max = 150)
        String name,

        @NotBlank(message = "Product category is required", groups = Create.class)
        @Pattern(regexp = "(?s).*\\S.*", message = "Category cannot be blank")
        @Size(max = 100)
        String category,

        String description,

        String keyFeatures,

        @Size(max = 255)
        String targetAudience,

        String imageUrl

) {
}
