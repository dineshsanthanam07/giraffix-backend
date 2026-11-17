package com.giraffix.brand.service;

import com.giraffix.config.S3Properties;
import com.giraffix.shared.image.ImageProcessor.PreparedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Map;
import java.util.UUID;

@Service
public class S3BrandLogoStorage {
    private static final Logger log = LoggerFactory.getLogger(S3BrandLogoStorage.class);
    private final S3Client s3;
    private final S3Properties properties;

    public S3BrandLogoStorage(S3Client s3, S3Properties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    public record Logo(String url, String bucket, String key, String mediaType, long sizeBytes) {}

    public Logo upload(PreparedImage image) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Configure the S3 bucket before saving a brand logo");
        }
        String extension = Map.of("image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp")
                .get(image.mediaType());
        if (extension == null) throw new IllegalArgumentException("Unprepared logo image");
        String key = "brands/logos/" + UUID.randomUUID() + "." + extension;
        String url = s3.utilities().getUrl(GetUrlRequest.builder().bucket(properties.bucket()).key(key).build())
                .toExternalForm();
        Logo logo = new Logo(url, properties.bucket(), key, image.mediaType(), image.bytes().length);
        try {
            s3.putObject(PutObjectRequest.builder().bucket(logo.bucket()).key(key).contentType(logo.mediaType())
                    .contentLength(logo.sizeBytes()).build(), RequestBody.fromBytes(image.bytes()));
            return logo;
        } catch (SdkException ex) {
            // A timed-out PUT may have succeeded remotely.
            cleanup(logo);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Brand logo upload failed");
        }
    }

    public void cleanup(Logo logo) {
        if (logo == null) return;
        try {
            if (!properties.bucket().equals(logo.bucket()) || !logo.key().startsWith("brands/logos/")) {
                throw new IllegalArgumentException("Unexpected brand logo storage location");
            }
            s3.deleteObject(DeleteObjectRequest.builder().bucket(logo.bucket()).key(logo.key()).build());
        } catch (RuntimeException ex) {
            // The committed logo remains usable even if deleting the previous object fails.
            log.error("stage=brand_logo_cleanup_failed bucket={} key={} errorType={}",
                    logo.bucket(), logo.key(), ex.getClass().getSimpleName());
        }
    }
}
