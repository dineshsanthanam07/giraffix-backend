package com.giraffix.product.service;

import com.giraffix.shared.image.ImageProcessor;

/** Compatibility facade for the product module's shared image preparation. */
public final class ProductImageProcessor {
    private ProductImageProcessor() {}
    public record PreparedImage(byte[] bytes, String mediaType, String sourceFormat, boolean converted) {}
    public static PreparedImage prepare(byte[] bytes, long maxBytes) {
        var image = ImageProcessor.prepare(bytes, maxBytes);
        return new PreparedImage(image.bytes(), image.mediaType(), image.sourceFormat(), image.converted());
    }
}
