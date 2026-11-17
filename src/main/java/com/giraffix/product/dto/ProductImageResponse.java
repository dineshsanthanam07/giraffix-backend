package com.giraffix.product.dto;

import com.giraffix.product.entity.ProductImage;
import java.time.Instant;
import java.util.UUID;

public record ProductImageResponse(UUID id, String filename, String mediaType, long sizeBytes,
                                   int position, String url, Instant createdAt) {
    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(image.getId(), image.getFilename(), image.getMediaType(),
                image.getSizeBytes(), image.getPosition(), image.getStorageUrl(), image.getCreatedAt());
    }
}
