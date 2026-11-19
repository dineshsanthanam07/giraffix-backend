package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.Brief;
import java.util.UUID;

/** Adapter must use jobId as provider idempotency key where supported. No blind retry after timeout. */
public interface ImageGenerator {
    boolean available();
    Result generate(UUID jobId, Brief brief, Budget budget);
    record Budget(long maxCostMicros, java.time.Instant deadline) {}
    record Result(byte[] image,String mediaType,String operationId,long costMicros,Long inputTokens,Long outputTokens,String model) {
        public Result(byte[] image,String mediaType,String operationId,long costMicros){this(image,mediaType,operationId,costMicros,null,null,null);}
    }
}
