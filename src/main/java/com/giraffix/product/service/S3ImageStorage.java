package com.giraffix.product.service;

import com.giraffix.config.S3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.net.URI;

@Service
public class S3ImageStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ImageStorage.class);
    private final S3Client client;
    private final S3Properties properties;

    public S3ImageStorage(S3Client client, S3Properties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void deleteProductImages(UUID productId, List<String> urls) {
        List<String> storedUrls = urls.stream().filter(url -> url != null && !url.isBlank()).toList();
        if (storedUrls.isEmpty()) return;
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Configure giraffix.storage.s3.bucket before deleting product images");
        }
        // Validate every URL before deleting anything. Only this product's objects in
        // the configured bucket may be removed; legacy external imageUrl is not used.
        List<String> keys = storedUrls.stream().map(url -> {
            try {
                String path = URI.create(url).getPath();
                String key = path == null || !path.startsWith("/") ? "" : path.substring(1);
                String expected = client.utilities().getUrl(GetUrlRequest.builder()
                        .bucket(properties.bucket()).key(key).build()).toExternalForm();
                if (!key.startsWith("products/" + productId + "/") || !expected.equals(url)) {
                    throw new IllegalArgumentException("Unexpected stored image URL");
                }
                return key;
            } catch (IllegalArgumentException ex) {
                log.error("stage=s3_delete_rejected productId={} reason=invalid_storage_url", productId);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Stored product image URL does not match configured S3 storage", ex);
            }
        }).distinct().toList();
        for (String key : keys) {
            log.info("stage=s3_delete_started productId={} key={}", productId, key);
            try {
                // DeleteObject succeeds for an already absent key, making retries safe.
                client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
                log.info("stage=s3_delete_completed productId={} key={}", productId, key);
            } catch (SdkException ex) {
                log.error("stage=s3_delete_failed productId={} key={} errorType={} status={}", productId, key,
                        ex.getClass().getSimpleName(), ex instanceof S3Exception s3 ? s3.statusCode() : 0);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "S3 image deletion failed; product retained. Retry deleting the product", ex);
            }
        }
    }

    public String upload(UUID productId, String mediaType, byte[] bytes) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            log.error("stage=s3_upload_rejected reason=bucket_not_configured productId={}", productId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure giraffix.storage.s3.bucket before saving product images");
        }
        String extension = Map.of("image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp").get(mediaType);
        if (extension == null) throw new IllegalArgumentException("Unprepared image format");
        String key = "products/" + productId + "/" + UUID.randomUUID() + "." + extension;
        String url = client.utilities().getUrl(GetUrlRequest.builder().bucket(properties.bucket()).key(key).build()).toExternalForm();
        long started = System.nanoTime();
        log.info("stage=s3_upload_started productId={} key={} sizeBytes={}", productId, key, bytes.length);
        try {
            var response = client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                    .contentType(mediaType).contentLength((long) bytes.length).build(), RequestBody.fromBytes(bytes));
            log.info("stage=s3_upload_completed productId={} key={} providerRequestId={} durationMs={}", productId, key,
                    response.responseMetadata() == null ? "none" : response.responseMetadata().requestId(),
                    (System.nanoTime() - started) / 1_000_000);
            return url;
        } catch (SdkException ex) {
            log.error("stage=s3_upload_failed productId={} key={} errorType={} status={}", productId, key,
                    ex.getClass().getSimpleName(), ex instanceof S3Exception s3 ? s3.statusCode() : 0);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "S3 image upload failed", ex);
        }
    }
}
