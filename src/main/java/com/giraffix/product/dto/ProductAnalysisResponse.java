
package com.giraffix.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProductAnalysisResponse(
        String name,
        String category,
        String description,
        String keyFeatures,
        String targetAudience,
        String imageUrl
) {}
