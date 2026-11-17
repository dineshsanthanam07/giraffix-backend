package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.Brief;
import java.time.Instant;

/** Writes text only; image rendering is the separate ImageGenerator step. */
public interface AdPromptWriter {
    Result write(Brief brief, long maxCostMicros, Instant deadline);
    record Result(String prompt, String model, String version, String providerId, long costMicros) {}
}
