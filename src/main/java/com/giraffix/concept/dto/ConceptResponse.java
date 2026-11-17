package com.giraffix.concept.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.giraffix.concept.model.ConceptAnalysis;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConceptResponse(
        UUID id,
        @JsonProperty("image_url") String imageUrl,
        @JsonProperty("created_at") Instant createdAt,
        boolean usable,
        @JsonProperty("unusable_reason") String unusableReason,
        ConceptAnalysis.Details concept) {}
