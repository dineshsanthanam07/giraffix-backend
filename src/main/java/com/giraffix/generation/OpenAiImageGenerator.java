package com.giraffix.generation;

import com.giraffix.selection.JsonSupport;
import com.giraffix.selection.SelectionAssets;
import com.giraffix.selection.SelectionModels.*;
import com.giraffix.shared.image.ImageProcessor;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Direct OpenAI Images API. No automatic retry of possibly billed requests. */
public class OpenAiImageGenerator implements ImageGenerator {
    private final RestClient.Builder builder;
    private final JsonSupport json;
    private final SelectionAssets assets;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long textInputRate;
    private final long imageInputRate;
    private final long outputRate;

    public OpenAiImageGenerator(RestClient.Builder builder, JsonSupport json, SelectionAssets assets,
                               String baseUrl, String apiKey, String model,
                               long textInputRate, long imageInputRate, long outputRate) {
        this.builder = builder;
        this.json = json;
        this.assets = assets;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        if (textInputRate < 0 || imageInputRate < 0 || outputRate < 0) throw new IllegalArgumentException("Invalid image cost rates");
        this.textInputRate = textInputRate;
        this.imageInputRate = imageInputRate;
        this.outputRate = outputRate;
    }

    public boolean available() { return apiKey != null && !apiKey.isBlank(); }

    public Result generate(UUID jobId, Brief brief, Budget budget) {
        if (!available()) throw new IllegalStateException("Configure the OpenAI image API key");
        if (brief.imagePrompt() == null || brief.imagePrompt().isBlank() || brief.context().aspectRatio() == null)
            throw new IllegalArgumentException("A completed image prompt and aspect ratio are required");
        if (budget.maxCostMicros() < 1) throw new IllegalArgumentException("No image generation budget");
        String quality = Objects.toString(brief.context().quality(), "high");
        if (!Set.of("low","medium","high").contains(quality)) throw new IllegalArgumentException("Invalid image quality");
        List<Map<String,Object>> references = new ArrayList<>();
        for (var product : brief.context().images()) references.add(reference(product));
        if (references.isEmpty()) throw new IllegalArgumentException("Product photos are required");
        if (brief.context().brandLogo() != null) references.add(reference(brief.context().brandLogo()));
        String roles = "The first " + brief.context().images().size() + " reference image(s) show the actual product from different views. "
                + "Preserve that product's identity, shape, packaging, colours and labels. "
                + (brief.context().brandLogo() == null ? "There is no separate brand logo. Do not invent one. "
                : "The LAST reference is the official brand logo, separate from the product. Incorporate it faithfully without redesign. ")
                + "Compose the entire ad for exactly " + brief.context().aspectRatio() + ".\n\n";
        Duration remaining = Duration.between(Instant.now(), budget.deadline());
        if (remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("Generation deadline exceeded");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(remaining.compareTo(Duration.ofSeconds(240)) < 0 ? remaining : Duration.ofSeconds(240));
        var client = builder.clone().requestFactory(factory).baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
        var response = client.post().uri("/images/edits").header("X-Client-Request-Id", jobId.toString())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of(
                        "model", model, "prompt", roles + brief.imagePrompt(), "images", references,
                        "size", ImageAspectRatios.size(brief.context().aspectRatio()), "n", 1, "quality", quality, "output_format", "png"))
                .retrieve().toEntity(String.class);
        var root = json.mapper.readTree(response.getBody() == null ? "" : response.getBody());
        if (root == null || !root.path("data").isArray() || root.path("data").size() != 1)
            throw new IllegalArgumentException("Expected exactly one generated image");
        String encoded = root.at("/data/0/b64_json").asText();
        // Reject oversized payloads before decoding; do not fetch arbitrary provider URLs.
        if (encoded.isBlank() || encoded.length() > 6990508) throw new IllegalArgumentException("Missing or oversized image output");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        var prepared = ImageProcessor.prepare(bytes, 5242880);
        if (!"image/png".equals(prepared.mediaType())) throw new IllegalArgumentException("Expected a PNG image");
        // Missing usage keeps the conservative image reservation rather than fabricating zero cost.
        // Rates estimate cost from provider tokens; cached input discounts are deliberately not assumed.
        long cost = budget.maxCostMicros();
        if (root.path("usage").isObject()) {
            long input = root.at("/usage/input_tokens").asLong(-1), output = root.at("/usage/output_tokens").asLong(-1);
            if (input < 0 || output < 0) throw new IllegalArgumentException("Invalid image usage");
            long text = root.at("/usage/input_tokens_details/text_tokens").asLong(-1);
            long image = root.at("/usage/input_tokens_details/image_tokens").asLong(-1);
            long inputCost;
            if (text >= 0 && image >= 0 && Math.addExact(text,image)==input) {
                inputCost = Math.addExact(Math.multiplyExact(text,textInputRate), Math.multiplyExact(image,imageInputRate));
            } else {
                inputCost = Math.multiplyExact(input, Math.max(textInputRate,imageInputRate));
            }
            cost = Math.addExact(Math.addExact(inputCost,Math.multiplyExact(output,outputRate)),999999) / 1000000;
        }
        if (cost > budget.maxCostMicros()) throw new IllegalArgumentException("Image generation cost exceeded budget");
        String operation = root.path("id").asText();
        if (operation.isBlank()) operation = Objects.toString(response.getHeaders().getFirst("x-request-id"), jobId.toString());
        return new Result(bytes, prepared.mediaType(), operation, cost);
    }

    private Map<String,Object> reference(Asset asset) {
        String data = "data:" + asset.mediaType() + ";base64," + Base64.getEncoder().encodeToString(assets.read(asset));
        return Map.of("image_url", data);
    }
}
