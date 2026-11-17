package com.giraffix.selection;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SelectionModels {
    private SelectionModels() {}
    public record Start(@NotNull UUID productId, @Pattern(regexp="(?s).*\\S.*") @Size(max=2000) String intent,
                        @Pattern(regexp="(?s).*\\S.*") @Size(max=500) String format, @Size(max=2000) String controls,
                        UUID conceptId,
                        @JsonInclude(JsonInclude.Include.NON_NULL)
                        @Pattern(regexp="1:1|4:5|3:4|16:9|9:16") String aspectRatio,
                        @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp="low|medium|high") String quality) {
        public static final String DEFAULT_INTENT = "Create a distinctive advertisement for this product";
        public static final String DEFAULT_FORMAT = "square social advertisement";

        public Start {
            if (intent == null) intent = DEFAULT_INTENT;
            if (format == null) format = aspectRatio == null ? DEFAULT_FORMAT : "Advertisement composed for aspect ratio " + aspectRatio;
            // High was the pre-quality behavior; preserve existing idempotency hashes.
            if ("high".equals(quality)) quality = null;
        }
        public Start(UUID productId, String intent, String format, String controls, UUID conceptId) {
            this(productId, intent, format, controls, conceptId, null, null);
        }
        public Start(UUID productId, String intent, String format, String controls, UUID conceptId, String aspectRatio) {
            this(productId, intent, format, controls, conceptId, aspectRatio, null);
        }
    }
    public record Asset(String bucket, String key, String mediaType, String sha256) {}
    public record Context(UUID ownerId, UUID productId, String factsJson, String intent, String format,
                          String controls, List<Asset> images,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String aspectRatio,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Asset brandLogo,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String quality) {
        public Context(UUID ownerId, UUID productId, String factsJson, String intent, String format,
                       String controls, List<Asset> images) {
            this(ownerId, productId, factsJson, intent, format, controls, images, null, null, null);
        }
        public Context(UUID ownerId, UUID productId, String factsJson, String intent, String format,
                       String controls, List<Asset> images, String aspectRatio, Asset brandLogo) {
            this(ownerId, productId, factsJson, intent, format, controls, images, aspectRatio, brandLogo, null);
        }
    }
    public record Candidate(UUID conceptId, UUID revisionId, int revision, String analysisJson, Asset reference) {}
    public record Reason(String dimension, String code, List<String> evidenceRefs) {}
    public record Ratings(Integer conceptFidelity, Integer productFidelity, Integer intentFit,
                          Integer visualClarity, Integer renderability) {
        public List<Integer> values() {
            return java.util.Arrays.asList(conceptFidelity, productFidelity, intentFit, visualClarity, renderability);
        }
    }
    public record Evaluation(UUID conceptId, int conceptVersion, String adaptation, Ratings ratings,
                             List<String> hardViolations, List<String> uncertainties, List<Reason> reasons,
                             boolean conceptLevelMismatch,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer compatibilityScore,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String classification) {
        public Evaluation(UUID conceptId, int conceptVersion, String adaptation, Ratings ratings,
                          List<String> hardViolations, List<String> uncertainties, List<Reason> reasons,
                          boolean conceptLevelMismatch) {
            this(conceptId, conceptVersion, adaptation, ratings, hardViolations, uncertainties, reasons,
                    conceptLevelMismatch, null, null);
        }
    }
    public record Verdict(Integer numerator, boolean passes) {
        public Double score() { return numerator == null ? null : numerator / 4.0; }
    }
    public record Usage(long inputTokens, long outputTokens, String providerId) {}
    public record Evaluated(List<Evaluation> candidates, Usage usage,
                            @JsonInclude(JsonInclude.Include.NON_NULL) List<UUID> notSuitableConceptIds) {
        public Evaluated(List<Evaluation> candidates, Usage usage) { this(candidates, usage, null); }
    }
    public record GenerationLimits(long maxCostMicros, long reviewReserveMicros, int deadlineSeconds, long promptReserveMicros) {
        public GenerationLimits(long maxCostMicros, long reviewReserveMicros, int deadlineSeconds) {
            this(maxCostMicros, reviewReserveMicros, deadlineSeconds, 0);
        }
    }
    public record Brief(Context context, Candidate concept, Evaluation evaluation,
                        SelectionPolicy.Snapshot selectionPolicy, GenerationLimits generationLimits,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String imagePrompt) {
        public Brief(Context context, Candidate concept, Evaluation evaluation,
                     SelectionPolicy.Snapshot selectionPolicy, GenerationLimits generationLimits) {
            this(context, concept, evaluation, selectionPolicy, generationLimits, null);
        }
        public Brief withPrompt(String prompt) {
            return new Brief(context, concept, evaluation, selectionPolicy, generationLimits, prompt);
        }
    }
    public record Status(UUID id, String status, int rounds, int verificationCalls, long costMicros,
                         Evaluation selected, UUID generationJobId, String generationStatus, Instant createdAt,
                         boolean fallbackUsed) {
        public Status(UUID id, String status, int rounds, int verificationCalls, long costMicros,
                      Evaluation selected, UUID generationJobId, String generationStatus, Instant createdAt) {
            this(id, status, rounds, verificationCalls, costMicros, selected, generationJobId, generationStatus, createdAt, false);
        }
    }
}
