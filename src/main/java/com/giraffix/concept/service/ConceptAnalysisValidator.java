package com.giraffix.concept.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Validates the complete contract locally, including rules unsupported by the provider. */
@Component
public class ConceptAnalysisValidator {
    private final JsonNode schema;

    public ConceptAnalysisValidator(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("schemas/concept-extraction.json").getInputStream()) {
            schema = mapper.readTree(stream);
        }
    }

    public JsonNode providerSchema() {
        JsonNode copy = schema.deepCopy();
        stripUnsupportedConstraints(copy);
        return copy;
    }

    private void stripUnsupportedConstraints(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("minItems");
            object.remove("maxItems");
            object.remove("minimum");
            object.remove("maximum");
            for (JsonNode child : object) stripUnsupportedConstraints(child);
        } else if (node.isArray()) {
            for (JsonNode child : node) stripUnsupportedConstraints(child);
        }
    }

    public void validate(JsonNode value) {
        check(value, schema, "$");
        boolean usable = value.path("usable").asBoolean();
        JsonNode concept = value.path("concept");
        if (!usable) {
            require(concept.isNull(), "Unusable images must have concept=null");
            require(value.path("unusable_reason").isString()
                    && !value.path("unusable_reason").asText().isBlank(), "Unusable images need a reason");
            return;
        }
        require(value.path("unusable_reason").isNull(), "Usable images must have unusable_reason=null");
        require(concept.isObject(), "Usable images need a concept");
        int titleWords = words(concept.path("concept_name").asText());
        require(titleWords >= 3 && titleWords <= 6, "concept_name must have 3-6 words");
        require(words(concept.path("idea").asText()) < 45, "idea must be under 45 words");
        Set<String> fits = strings(concept.path("fits_forms"));
        Set<String> bad = strings(concept.path("bad_fits"));
        require(java.util.Collections.disjoint(fits, bad), "fits_forms and bad_fits overlap");
        Set<String> examples = new HashSet<>();
        for (JsonNode example : concept.path("transfer_examples")) {
            String form = example.path("product_form").asText();
            require(fits.contains(form), "Example form must come from fits_forms");
            require(examples.add(form), "Example forms must be distinct");
        }
    }

    private Set<String> strings(JsonNode array) {
        Set<String> result = new HashSet<>();
        for (JsonNode item : array) require(result.add(item.asText()), "Array entries must be distinct");
        return result;
    }

    private int words(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private void check(JsonNode value, JsonNode rule, String path) {
        if (rule.has("anyOf")) {
            for (JsonNode option : rule.path("anyOf")) {
                try { check(value, option, path); return; } catch (IllegalArgumentException ignored) {}
            }
            throw new IllegalArgumentException("Invalid value at " + path);
        }
        JsonNode types = rule.path("type");
        boolean correctType = false;
        if (types.isArray()) {
            for (JsonNode type : types) correctType |= matches(value, type.asText());
        } else {
            correctType = matches(value, types.asText());
        }
        require(correctType, "Invalid type at " + path);
        if (value.isNull()) return;
        if (rule.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : rule.path("enum")) found |= allowed.equals(value);
            require(found, "Invalid enum at " + path);
        }
        if (value.isObject()) {
            JsonNode properties = rule.path("properties");
            require(value.size() == properties.size(), "Unexpected or missing fields at " + path);
            for (String name : properties.propertyNames()) {
                require(value.has(name), "Missing field at " + path + "." + name);
                check(value.path(name), properties.path(name), path + "." + name);
            }
        } else if (value.isArray()) {
            if (rule.has("minItems")) require(value.size() >= rule.path("minItems").asInt(), "Too few items at " + path);
            if (rule.has("maxItems")) require(value.size() <= rule.path("maxItems").asInt(), "Too many items at " + path);
            for (JsonNode item : value) check(item, rule.path("items"), path + "[]");
        } else if (value.isIntegralNumber()) {
            require(value.canConvertToInt(), "Integer out of range at " + path);
            if (rule.has("minimum")) require(value.asInt() >= rule.path("minimum").asInt(), "Score below minimum at " + path);
            if (rule.has("maximum")) require(value.asInt() <= rule.path("maximum").asInt(), "Score above maximum at " + path);
        } else if (value.isString()) {
            require(!value.asText().isBlank(), "Blank text at " + path);
        }
    }

    private boolean matches(JsonNode value, String type) {
        if (value == null) return false;
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
