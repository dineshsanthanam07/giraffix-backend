package com.giraffix.brand.dto;




import com.giraffix.brand.entity.Brand;

import java.time.Instant;
import java.util.UUID;

public record BrandResponse(
        UUID id,
        String name,
        String description,
        String industry,
        String targetAudience,
        String primaryColor,
        String secondaryColor,
        Instant createdAt,
        Instant updatedAt
) {
    public static BrandResponse from(Brand brand) {
        return new BrandResponse(
                brand.getId(),
                brand.getName(),
                brand.getDescription(),
                brand.getIndustry(),
                brand.getTargetAudience(),
                brand.getPrimaryColor(),
                brand.getSecondaryColor(),
                brand.getCreatedAt(),
                brand.getUpdatedAt()
        );
    }
}
