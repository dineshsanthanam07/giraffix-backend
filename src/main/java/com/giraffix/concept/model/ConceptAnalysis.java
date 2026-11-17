package com.giraffix.concept.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConceptAnalysis(boolean usable, @JsonProperty("unusable_reason") String unusableReason,
                              Details concept) {
    public record Details(
            @JsonProperty("concept_name") String conceptName,
            String mechanism, String idea, List<String> preserve, List<String> adapt,
            @JsonProperty("fits_forms") List<String> fitsForms,
            @JsonProperty("bad_fits") List<String> badFits,
            @JsonProperty("transfer_examples") List<TransferExample> transferExamples,
            @JsonProperty("transferability_score") int transferabilityScore,
            @JsonProperty("originality_score") int originalityScore,
            @JsonProperty("clarity_score") int clarityScore) {}
    public record TransferExample(@JsonProperty("product_form") String productForm, String example) {}
}

