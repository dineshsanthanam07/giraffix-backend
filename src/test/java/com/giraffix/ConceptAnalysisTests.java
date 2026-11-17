package com.giraffix;

import com.giraffix.concept.adapter.ClaudeConceptAnalyzer;
import com.giraffix.concept.service.ConceptAnalysisValidator;
import com.giraffix.config.ClaudeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ConceptAnalysisTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private ConceptAnalysisValidator validator;
    private ClaudeConceptAnalyzer analyzer;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() throws Exception {
        validator = new ConceptAnalysisValidator(mapper);
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        analyzer = new ClaudeConceptAnalyzer(builder,
                new ClaudeProperties("test-only", "https://example.invalid/v1", "test-model", "2023-06-01", 1024, 5242880),
                mapper, validator, new ClassPathResource("prompts/concept-analysis.txt"), "claude-sonnet-5-5", 2048);
    }

    @Test
    void sendsImageAndProviderCompatibleSchemaAndReturnsExactFields() throws Exception {
        var image = ProductImageProcessorTests.fixture("png");
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.max_tokens").value(2048))
                .andExpect(jsonPath("$.messages[0].content[0].source.data")
                        .value(java.util.Base64.getEncoder().encodeToString(image)))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andRespond(withSuccess(envelope(ConceptFixtures.VALID, "end_turn"), MediaType.APPLICATION_JSON));
        var result = analyzer.analyze(image, "image/png");
        tools.jackson.databind.JsonNode resultJson = mapper.valueToTree(result);
        assertThat(resultJson).isEqualTo(mapper.readTree(ConceptFixtures.VALID));
        assertThat(validator.providerSchema().toString()).doesNotContain("\"minItems\"", "\"maxItems\"", "\"minimum\"", "\"maximum\"");
        server.verify();
    }

    @Test
    void acceptsAndPreservesUnusableResults() {
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andRespond(withSuccess(envelope(ConceptFixtures.UNUSABLE, "end_turn"), MediaType.APPLICATION_JSON));
        var result = analyzer.analyze(new byte[]{1}, "image/png");
        assertThat(result.usable()).isFalse();
        assertThat(result.concept()).isNull();
        assertThat(result.unusableReason()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra", "missing", "null_concept", "reason", "mechanism", "score", "fraction",
            "preserve_count", "adapt_count", "fits_count", "overlap", "example_count",
            "example_duplicate", "example_not_fit", "title", "idea", "blank"})
    void rejectsSchemaAndCrossFieldViolations(String mutation) {
        ObjectNode root = (ObjectNode) mapper.readTree(ConceptFixtures.VALID);
        ObjectNode concept = (ObjectNode) root.path("concept");
        switch (mutation) {
            case "extra" -> concept.put("extra", "unwanted");
            case "missing" -> concept.remove("mechanism");
            case "null_concept" -> root.putNull("concept");
            case "reason" -> root.put("unusable_reason", "Unexpected");
            case "mechanism" -> concept.put("mechanism", "invented");
            case "score" -> concept.put("clarity_score", 101);
            case "fraction" -> concept.put("originality_score", 2.5);
            case "preserve_count" -> concept.putArray("preserve").add("One");
            case "adapt_count" -> concept.putArray("adapt");
            case "fits_count" -> concept.putArray("fits_forms").add("footwear").add("furniture");
            case "overlap" -> concept.putArray("bad_fits").add("footwear");
            case "example_count" -> concept.putArray("transfer_examples");
            case "example_duplicate" -> ((ObjectNode) concept.path("transfer_examples").get(1)).put("product_form", "footwear");
            case "example_not_fit" -> ((ObjectNode) concept.path("transfer_examples").get(1)).put("product_form", "vehicle");
            case "title" -> concept.put("concept_name", "Short");
            case "idea" -> concept.put("idea", "word ".repeat(45));
            case "blank" -> concept.put("idea", " ");
        }
        assertThatThrownBy(() -> validator.validate(root)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidUnusableBranch() {
        for (String json : new String[]{
                "{\"usable\":false,\"unusable_reason\":null,\"concept\":null}",
                ConceptFixtures.VALID.replace("\"usable\":true", "\"usable\":false")}) {
            assertThatThrownBy(() -> validator.validate(mapper.readTree(json))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsTruncatedAndInvalidModelOutput() {
        server.expect(requestTo("https://example.invalid/v1/messages"))
                .andRespond(withSuccess(envelope(ConceptFixtures.VALID, "max_tokens"), MediaType.APPLICATION_JSON));
        assertBadGateway(() -> analyzer.analyze(new byte[]{1}, "image/png"));
    }

    @Test
    void handlesProviderFailure() {
        server.expect(requestTo("https://example.invalid/v1/messages")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertBadGateway(() -> analyzer.analyze(new byte[]{1}, "image/png"));
    }

    private String envelope(String json, String stop) {
        return mapper.writeValueAsString(java.util.Map.of("stop_reason", stop,
                "content", java.util.List.of(java.util.Map.of("type", "text", "text", json))));
    }
    private void assertBadGateway(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
