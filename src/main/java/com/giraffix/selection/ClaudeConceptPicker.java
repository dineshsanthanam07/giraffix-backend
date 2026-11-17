package com.giraffix.selection;

import com.giraffix.config.ClaudeProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.giraffix.selection.SelectionModels.*;

/** Executes the versioned picker prompt; backend code validates and chooses the winner. */
public class ClaudeConceptPicker {
    private final RestClient client;
    private final JsonSupport json;
    private final SelectionAssets assets;
    private final JsonNode schema;
    private final String prompt;

    public ClaudeConceptPicker(RestClient.Builder builder, ClaudeProperties config, JsonSupport json,
                               SelectionAssets assets) throws IOException {
        this.json = json;
        this.assets = assets;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(25));
        client = builder.clone().requestFactory(factory).baseUrl(config.baseUrl())
                .defaultHeader("x-api-key", config.apiKey()).defaultHeader("anthropic-version", config.apiVersion()).build();
        try (var input = new ClassPathResource("schemas/concept-picker.json").getInputStream()) {
            schema = json.mapper.readTree(input);
        }
        try (var input = new ClassPathResource("prompts/concept-picker.txt").getInputStream()) {
            prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public Evaluated evaluate(Context context, List<Candidate> candidates, SelectionPolicy.Snapshot policy) {
        var facts = json.mapper.readTree(context.factsJson());
        var product = json.mapper.createObjectNode();
        for (String field : List.of("name", "category", "description", "keyFeatures", "targetAudience"))
            product.set(field, facts.path(field));
        var brand = json.mapper.createObjectNode();
        Map.of("name", "brandName", "description", "brandDescription", "industry", "brandIndustry",
                "targetAudience", "brandAudience", "primaryColor", "primaryColor", "secondaryColor", "secondaryColor")
                .forEach((field, source) -> brand.set(field, facts.path(source)));
        var cards = candidates.stream().map(candidate -> {
            var card = json.mapper.readTree(candidate.analysisJson()).path("concept").deepCopy();
            if (!card.isObject()) throw new IllegalArgumentException("Concept card must be an object");
            ((tools.jackson.databind.node.ObjectNode) card).put("conceptId", candidate.conceptId().toString());
            return card;
        }).toList();
        String instructions = prompt.replace("{{MINIMUM_COMPATIBILITY_SCORE}}", Integer.toString(policy.threshold()));
        String data = json.write(Map.of("product", product, "brand", brand, "concepts", cards,
                "requestContext", Map.of("intent", context.intent(), "format", context.format(),
                        "controls", Objects.toString(context.controls(), ""))));
        long inputBound = instructions.getBytes(StandardCharsets.UTF_8).length + data.getBytes(StandardCharsets.UTF_8).length
                + json.write(schema).getBytes(StandardCharsets.UTF_8).length + context.images().size() * 16000L + 1000;
        if (inputBound > policy.maxInputTokens()) throw new ClaudeSelectionEvaluator.EnvelopeExceeded();

        var content = new ArrayList<Map<String, Object>>();
        for (var asset : context.images()) content.add(Map.of("type", "image", "source", Map.of("type", "base64",
                "media_type", asset.mediaType(), "data", Base64.getEncoder().encodeToString(assets.read(asset)))));
        content.add(Map.of("type", "text", "text", data));
        String raw = client.post().uri("/messages").contentType(MediaType.APPLICATION_JSON).body(Map.of(
                "model", policy.model(), "max_tokens", policy.maxOutputTokens(), "system", instructions,
                "output_config", Map.of("format", Map.of("type", "json_schema", "schema", schema)),
                "messages", List.of(Map.of("role", "user", "content", content)))).retrieve().body(String.class);
        var root = json.mapper.readTree(raw == null ? "" : raw);
        if (root == null || !"end_turn".equals(root.path("stop_reason").asText()) || !root.path("content").isArray())
            throw new IllegalArgumentException("Incomplete concept picker response");
        var text = new StringBuilder();
        for (var block : root.path("content")) if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        var body = json.mapper.readTree(text.toString());
        ClaudeSelectionEvaluator.validateShape(body, schema);
        var evaluations = new ArrayList<Evaluation>();
        for (var item : body.path("evaluations")) {
            UUID id = UUID.fromString(item.path("conceptId").asText());
            var candidate = candidates.stream().filter(c -> c.conceptId().equals(id)).findFirst().orElseThrow();
            evaluations.add(new Evaluation(id, candidate.revision(), item.path("adaptationDirection").asText(), null,
                    List.of(), List.of(), List.of(), false, item.path("compatibilityScore").asInt(), item.path("classification").asText()));
        }
        var exclusions = new ArrayList<UUID>();
        for (var id : body.path("notSuitableConceptIds")) exclusions.add(UUID.fromString(id.asText()));
        long input = root.path("usage").path("input_tokens").asLong(-1), output = root.path("usage").path("output_tokens").asLong(-1);
        if (input < 0 || output < 0) throw new IllegalArgumentException("Missing concept picker usage");
        var result = new Evaluated(evaluations, new Usage(input, output, root.path("id").asText()), exclusions);
        ConceptPickerRules.validate(candidates, result, policy.threshold());
        return result;
    }
}
