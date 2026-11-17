package com.giraffix.concept.port;

import com.giraffix.concept.model.ConceptAnalysis;

public interface ConceptAnalyzer {
    ConceptAnalysis analyze(byte[] image, String mediaType);
}

