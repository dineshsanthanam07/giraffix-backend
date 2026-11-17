package com.giraffix.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.time.Duration;

@Configuration
public class S3Config {
    @Bean(destroyMethod = "close")
    public S3Client s3Client(S3Properties properties) {
        boolean hasAccessKey = properties.accessKeyId() != null && !properties.accessKeyId().isBlank();
        boolean hasSecretKey = properties.secretAccessKey() != null && !properties.secretAccessKey().isBlank();
        boolean hasSessionToken = properties.sessionToken() != null && !properties.sessionToken().isBlank();
        if (hasAccessKey != hasSecretKey || (hasSessionToken && !hasAccessKey)) {
            throw new IllegalArgumentException("Configure both giraffix.storage.s3.access-key-id and secret-access-key");
        }
        var builder = S3Client.builder().region(Region.of(properties.region()))
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)));
        if (hasAccessKey) {
            var credentials = hasSessionToken
                    ? AwsSessionCredentials.create(properties.accessKeyId(), properties.secretAccessKey(), properties.sessionToken())
                    : AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey());
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }
        return builder.build();
    }
}
