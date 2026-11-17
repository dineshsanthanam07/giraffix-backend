package com.giraffix;

import com.giraffix.config.S3Config;
import com.giraffix.config.S3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.assertj.core.api.Assertions.*;

class S3ConfigTests {
    @Test
    void usesCredentialsFromProperties() {
        var properties = new S3Properties("test-bucket", "us-east-1", "test-key", "test-secret", "");
        try (var client = new S3Config().s3Client(properties)) {
            var provider = (StaticCredentialsProvider) client.serviceClientConfiguration().credentialsProvider();
            var credentials = provider.resolveCredentials();
            assertThat(credentials.accessKeyId()).isEqualTo("test-key");
            assertThat(credentials.secretAccessKey()).isEqualTo("test-secret");
            assertThat(credentials).isNotInstanceOf(AwsSessionCredentials.class);
        }
        assertThat(properties.toString()).doesNotContain("test-key", "test-secret");
    }

    @Test
    void supportsTemporaryCredentialsFromProperties() {
        var properties = new S3Properties("test-bucket", "us-east-1", "test-key", "test-secret", "test-token");
        try (var client = new S3Config().s3Client(properties)) {
            var provider = (StaticCredentialsProvider) client.serviceClientConfiguration().credentialsProvider();
            var credentials = provider.resolveCredentials();
            assertThat(credentials).isInstanceOfSatisfying(AwsSessionCredentials.class,
                    session -> assertThat(session.sessionToken()).isEqualTo("test-token"));
        }
        assertThat(properties.toString()).doesNotContain("test-token");
    }

    @Test
    void rejectsIncompleteCredentials() {
        for (var properties : java.util.List.of(
                new S3Properties("test", "us-east-1", "test-key", "", ""),
                new S3Properties("test", "us-east-1", "", "test-secret", ""),
                new S3Properties("test", "us-east-1", "", "", "test-token"))) {
            assertThatThrownBy(() -> new S3Config().s3Client(properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Configure both");
        }
    }
}
