package com.giraffix.concept.port;

import java.util.UUID;

public interface ConceptImageStorage {
    StoredImage upload(UUID conceptId, byte[] image, String mediaType);
    void delete(StoredImage image);

    record StoredImage(String bucket, String key, String url, String mediaType, long sizeBytes) {}
}

