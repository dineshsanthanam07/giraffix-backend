package com.giraffix.selection;

import java.util.*;
import static com.giraffix.selection.SelectionModels.*;

/** The provider supplies scores; selection and exclusion decisions stay in Java. */
public final class ConceptPickerRules {
    public static final String VERSION = "concept-picker-v1";
    private static final Set<String> CLASSIFICATIONS = Set.of("STRONG", "CONDITIONAL", "NOT_SUITABLE");

    private ConceptPickerRules() {}

    public static boolean enabled(SelectionPolicy.Snapshot policy) { return VERSION.equals(policy.version()); }

    public static boolean qualifies(Evaluation evaluation, int threshold) {
        return "STRONG".equals(evaluation.classification()) && evaluation.compatibilityScore() >= threshold;
    }

    public static void validate(List<Candidate> candidates, Evaluated result, int threshold) {
        if (result == null || result.candidates() == null || result.candidates().size() != candidates.size()
                || result.notSuitableConceptIds() == null) throw new IllegalArgumentException("Incomplete concept picker response");
        Map<UUID, Candidate> supplied = new HashMap<>();
        for (var candidate : candidates) {
            if (supplied.put(candidate.conceptId(), candidate) != null) throw new IllegalArgumentException("Duplicate supplied concept");
        }
        Set<UUID> seen = new HashSet<>(), belowThreshold = new HashSet<>();
        for (var evaluation : result.candidates()) {
            if (evaluation == null) throw new IllegalArgumentException("Missing concept evaluation");
            var candidate = supplied.get(evaluation.conceptId());
            if (candidate == null || !seen.add(evaluation.conceptId()) || evaluation.conceptVersion() != candidate.revision()
                    || evaluation.compatibilityScore() == null || evaluation.compatibilityScore() < 0 || evaluation.compatibilityScore() > 100
                    || evaluation.classification() == null || !CLASSIFICATIONS.contains(evaluation.classification())
                    || evaluation.adaptation() == null || evaluation.adaptation().isBlank() || evaluation.adaptation().length() > 4000)
                throw new IllegalArgumentException("Invalid concept picker evaluation");
            if (evaluation.compatibilityScore() < threshold) belowThreshold.add(evaluation.conceptId());
        }
        Set<UUID> excluded = new HashSet<>(result.notSuitableConceptIds());
        if (excluded.size() != result.notSuitableConceptIds().size() || !excluded.equals(belowThreshold))
            throw new IllegalArgumentException("notSuitableConceptIds must contain exactly the below-threshold concepts");
    }
}
