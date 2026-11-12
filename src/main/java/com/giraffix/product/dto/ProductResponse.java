package com.giraffix.product.dto;



import com.giraffix.product.entity.Product;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record ProductResponse(
        UUID id,
        UUID brandId,
        String name,
        String category,
        String description,
        String keyFeatures,
        String targetAudience,
        String imageUrl,
        List<ProductImageResponse> images,
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
                product.getImages().stream().map(ProductImageResponse::from).toList(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
