package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.Brief;
import java.time.Instant;

/** Writes text only; image rendering is the separate ImageGenerator step. */
public interface AdPromptWriter {
    Result write(Brief brief, long maxCostMicros, Instant deadline);
    default Result write(Brief brief,long maxCostMicros,Instant deadline,int templateVersion){
        return write(brief,maxCostMicros,deadline);
    }
    record Result(String prompt,String model,String version,String providerId,long costMicros,Long inputTokens,Long outputTokens) {
        public Result(String prompt,String model,String version,String providerId,long costMicros){this(prompt,model,version,providerId,costMicros,null,null);}
    }
}
