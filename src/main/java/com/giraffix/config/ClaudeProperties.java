package com.giraffix.config;



import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "giraffix.ai")
public record ClaudeProperties(
        String apiKey,
        String baseUrl,
        String model,
        String apiVersion,
        int maxTokens,
        long maxImageSizeBytes
) {
}
