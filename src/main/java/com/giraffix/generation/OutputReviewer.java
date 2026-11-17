package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.*;

public interface OutputReviewer {
    Review review(Brief brief, Asset output);
    record Review(boolean accepted, String reason, long costMicros) {}
}
