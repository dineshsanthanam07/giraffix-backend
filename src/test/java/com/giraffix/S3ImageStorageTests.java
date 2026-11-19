package com.giraffix;

import com.giraffix.config.S3Properties;
import com.giraffix.product.service.S3ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class S3ImageStorageTests {
    private S3Client client;
    private S3ImageStorage storage;

    @BeforeEach
    void setup() {
        client = mock(S3Client.class);
        when(client.utilities()).thenReturn(S3Utilities.builder().region(Region.US_EAST_1).build());
        storage = new S3ImageStorage(client, new S3Properties("test-images", "us-east-1", null, null, null));
    }

    @Test
    void uploadsBytesAndReturnsTheS3Url(CapturedOutput output) throws Exception {
        byte[] bytes = ProductImageProcessorTests.fixture("png");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            assertThat(request.bucket()).isEqualTo("test-images");
            assertThat(request.contentType()).isEqualTo("image/png");
            assertThat(request.contentLength()).isEqualTo(bytes.length);
            assertThat(request.acl()).isNull();
            RequestBody body = call.getArgument(1);
            try (var stream = body.contentStreamProvider().newStream()) { assertThat(stream.readAllBytes()).isEqualTo(bytes); }
            return PutObjectResponse.builder().build();
        });
        UUID productId = UUID.randomUUID();
        String url = storage.upload(productId, "image/png", bytes);
        assertThat(url).startsWith("https://test-images.s3.").contains("products/" + productId + "/").endsWith(".png")
                .doesNotContain("X-Amz", "Signature", "Expires");
        assertThat(output.getOut()).contains("stage=s3_upload_started", "stage=s3_upload_completed").doesNotContain("iVBORw0KGgo");
    }

    @Test
    void rejectsMissingBucketWithoutUploading() {
        var unconfigured = new S3ImageStorage(client, new S3Properties("", "us-east-1", null, null, null));
        assertThatThrownBy(() -> unconfigured.upload(UUID.randomUUID(), "image/png", new byte[]{1}))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void logsUploadFailureWithoutProviderSecrets(CapturedOutput output) {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("DO_NOT_LOG_AWS_SECRET").build());
        assertThatThrownBy(() -> storage.upload(UUID.randomUUID(), "image/png", new byte[]{1}))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(output.getOut()).contains("stage=s3_upload_failed", "status=403").doesNotContain("DO_NOT_LOG_AWS_SECRET");
    }

    @Test
    void deletesOnlyStoredObjectsAndCanRetryAlreadyDeletedKeys() {
        UUID id = UUID.randomUUID();
        String key = "products/" + id + "/photo.png";
        String url = client.utilities().getUrl(GetUrlRequest.builder().bucket("test-images").key(key).build()).toExternalForm();
        storage.deleteProductImages(id, List.of(url));
        storage.deleteProductImages(id, List.of(url));
        verify(client, times(2)).deleteObject(DeleteObjectRequest.builder().bucket("test-images").key(key).build());
    }

    @Test
    void validatesAllUrlsBeforeDeletingAnyObjects() {
        UUID id = UUID.randomUUID();
        String key = "products/" + id + "/photo.png";
        String url = client.utilities().getUrl(GetUrlRequest.builder().bucket("test-images").key(key).build()).toExternalForm();
        for (String invalid : List.of("https://other.example/" + key,
                url.replace(id.toString(), UUID.randomUUID().toString()), "invalid URL")) {
            assertThatThrownBy(() -> storage.deleteProductImages(id, List.of(url, invalid)))
                    .isInstanceOf(ResponseStatusException.class);
        }
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void handlesMissingBucketAndProductsWithoutStoredImages() {
        var unconfigured = new S3ImageStorage(client, new S3Properties("", "us-east-1", null, null, null));
        unconfigured.deleteProductImages(UUID.randomUUID(), List.of());
        assertThatThrownBy(() -> unconfigured.deleteProductImages(UUID.randomUUID(), List.of("https://example.com/image.png")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void durableCleanupCannotTargetAnotherBucketOrProduct() {
        UUID product = UUID.randomUUID();
        for (var object : List.of(
                new S3ImageStorage.StoredObject("other-bucket", "products/" + product + "/photo.png"),
                new S3ImageStorage.StoredObject("test-images", "products/" + UUID.randomUUID() + "/photo.png"))) {
            assertThatThrownBy(() -> storage.deleteProductObject(product, object)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void logsDeleteFailureWithoutProviderSecrets(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        String url = client.utilities().getUrl(GetUrlRequest.builder().bucket("test-images")
                .key("products/" + id + "/photo.png").build()).toExternalForm();
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("DO_NOT_LOG_AWS_SECRET").build());
        assertThatThrownBy(() -> storage.deleteProductImages(id, List.of(url)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(output.getOut()).contains("stage=s3_delete_failed", "status=403").doesNotContain("DO_NOT_LOG_AWS_SECRET");
    }
}
