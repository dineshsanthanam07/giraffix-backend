package com.giraffix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "giraffix.storage.s3")
public record S3Properties(String bucket, String region, String accessKeyId,
                           String secretAccessKey, String sessionToken) {
    @Override
    public String toString() {
        return "S3Properties[bucket=" + bucket + ", region=" + region + ", credentials=[REDACTED]]";
    }
}
