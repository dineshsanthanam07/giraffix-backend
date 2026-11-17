package com.giraffix.selection;

import java.util.List;
import static com.giraffix.selection.SelectionModels.*;

public interface ConceptEvaluator {
    Evaluated evaluate(Context context, List<Candidate> candidates, Evaluation exactAdaptation,
                       SelectionPolicy.Snapshot policy);
}
