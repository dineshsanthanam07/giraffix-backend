package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.*;

public interface OutputReviewer {
    Review review(Brief brief, Asset output);
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record Review(boolean accepted,String reason,long costMicros,Long inputTokens,Long outputTokens,String providerId) {
        public Review(boolean accepted,String reason,long costMicros){this(accepted,reason,costMicros,null,null,null);}
    }
}
