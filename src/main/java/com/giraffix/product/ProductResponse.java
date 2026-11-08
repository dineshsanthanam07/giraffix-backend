package com.giraffix.product;



import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID brandId,
        String name,
        String category,
        String description,
        String keyFeatures,
        String targetAudience,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getBrand().getId(),
                product.getName(),
                product.getCategory(),
                product.getDescription(),
                product.getKeyFeatures(),
                product.getTargetAudience(),
                product.getImageUrl(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}