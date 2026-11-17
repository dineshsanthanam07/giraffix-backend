package com.giraffix.generation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GenerationConfiguration {
    @Bean
    @ConditionalOnMissingBean(ImageGenerator.class)
    public ImageGenerator imageGenerator(org.springframework.web.client.RestClient.Builder builder,
            com.giraffix.selection.JsonSupport json, com.giraffix.selection.SelectionAssets assets,
            org.springframework.core.env.Environment environment) {
        return new OpenAiImageGenerator(builder, json, assets,
                environment.getProperty("giraffix.generation.openai.base-url", "https://api.openai.com/v1"),
                environment.getProperty("giraffix.generation.openai.api-key", ""),
                environment.getProperty("giraffix.generation.openai.model", "gpt-image-2"),
                environment.getProperty("giraffix.generation.openai.text-input-micros-per-million", Long.class, 5000000L),
                environment.getProperty("giraffix.generation.openai.image-input-micros-per-million", Long.class, 8000000L),
                environment.getProperty("giraffix.generation.openai.output-micros-per-million", Long.class, 30000000L));
    }
}
