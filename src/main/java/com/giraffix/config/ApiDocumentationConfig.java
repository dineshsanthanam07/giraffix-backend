package com.giraffix.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.*;
import java.util.List;

@Configuration
public class ApiDocumentationConfig {
    @Bean public OpenAPI giraffixApi(){
        return new OpenAPI().info(new Info().title("Giraffix API").version("v1")
                .description("Session-authenticated APIs. Obtain CSRF through Security before mutations. Use Ads for the complete generation pipeline; admin endpoints require the configured subject allowlist."))
                .tags(List.of(tag("01 Identity","Current user and onboarding"),tag("02 Security","CSRF token and session protection"),
                        tag("03 Brands","Owned brands and logos"),tag("04 Products","Owned products, photos and analysis"),
                        tag("05 Ads","Submit and poll the complete ad pipeline"),tag("06 Creations","Saved creation history and private images"),
                        tag("07 Concepts","Shared concept reference library"),tag("08 Admin - Concepts","Concept revision approval and moderation"),
                        tag("09 Admin - Prompts","Prompt drafts, publication and audit"),
                        tag("10 Admin - Usage","Token, model, user and estimated cost analytics"),
                        tag("90 Legacy - Selection","Optional compatibility API; prefer Ads")));
    }
    private Tag tag(String name,String description){return new Tag().name(name).description(description);}
}
