package com.giraffix.concept.adapter;

import com.giraffix.concept.port.ConceptImageStorage;
import com.giraffix.config.S3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.util.Map;
import java.util.UUID;

@Component
public class S3ConceptImageStorage implements ConceptImageStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ConceptImageStorage.class);
    private final S3Client client;
    private final S3Properties properties;

    public S3ConceptImageStorage(S3Client client, S3Properties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public StoredImage upload(UUID conceptId, byte[] image, String mediaType) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure the S3 bucket before saving concepts");
        }
        String extension = Map.of("image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp").get(mediaType);
        if (extension == null) throw new IllegalArgumentException("Unprepared concept image");
        String key = "concept/" + conceptId + "/" + UUID.randomUUID() + "." + extension;
        String url = client.utilities().getUrl(GetUrlRequest.builder().bucket(properties.bucket()).key(key).build()).toExternalForm();
        var stored = new StoredImage(properties.bucket(), key, url, mediaType, image.length);
        try {
            client.putObject(PutObjectRequest.builder().bucket(stored.bucket()).key(key)
                    .contentType(mediaType).contentLength((long) image.length).build(), RequestBody.fromBytes(image));
            log.info("stage=concept_image_uploaded conceptId={} key={}", conceptId, key);
            return stored;
        } catch (SdkException ex) {
            // A timed-out PUT may have succeeded remotely. Best-effort cleanup uses the unique key.
            try { delete(stored); } catch (RuntimeException cleanup) {
                log.error("stage=concept_image_cleanup_failed conceptId={} key={}", conceptId, key);
            }
            log.error("stage=concept_image_upload_failed conceptId={} errorType={}", conceptId, ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Concept image upload failed");
        }
    }

    @Override
    public void delete(StoredImage image) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(image.bucket()).key(image.key()).build());
        } catch (SdkException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Concept image cleanup failed");
        }
    }
}
