package com.giraffix;

import com.giraffix.concept.model.ConceptAnalysis;
import tools.jackson.databind.json.JsonMapper;

final class ConceptFixtures {
    static final String VALID = """
            {"usable":true,"unusable_reason":null,"concept":{
              "concept_name":"Floating Frame Of Possibility",
              "mechanism":"levitation",
              "idea":"An object floats inside an open frame, suggesting effortless freedom.",
              "preserve":["Floating focal object","Open surrounding frame","Directional soft light"],
              "adapt":["Object silhouette","Contextual background","Supporting props"],
              "fits_forms":["rigid_container","footwear","handheld_device","furniture"],
              "bad_fits":["digital_service"],
              "transfer_examples":[
                {"product_form":"footwear","example":"A shoe floats within a luminous frame."},
                {"product_form":"handheld_device","example":"A phone hovers inside a minimal frame."},
                {"product_form":"furniture","example":"A chair hangs weightlessly in an open frame."}
              ],
              "transferability_score":80,"originality_score":60,"clarity_score":90
            }}
            """;
    static final String UNUSABLE = """
            {"usable":false,"unusable_reason":"This is a plain packshot without a transferable creative hook.","concept":null}
            """;
    static ConceptAnalysis analysis() { return JsonMapper.builder().build().readValue(VALID, ConceptAnalysis.class); }
}
