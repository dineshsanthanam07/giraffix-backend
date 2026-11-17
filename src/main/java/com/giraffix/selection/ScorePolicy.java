package com.giraffix.selection;

import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.List;
import static com.giraffix.selection.SelectionModels.*;

@Component
public class ScorePolicy {
    public static final List<String> DIMENSIONS = List.of("conceptFidelity", "productFidelity", "intentFit", "visualClarity", "renderability");
    public Verdict score(Evaluation value, SelectionPolicy.Snapshot policy) {
        if (value == null || value.ratings() == null || value.adaptation() == null || value.adaptation().isBlank()
                || value.adaptation().length() > 4000 || value.hardViolations() == null || value.uncertainties() == null
                || value.reasons() == null || value.reasons().size() > 20 || value.hardViolations().size() > 10
                || value.uncertainties().size() > 10) throw new IllegalArgumentException("Invalid evaluation");
        for (var list : List.of(value.hardViolations(), value.uncertainties()))
            if (list.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 500)) throw new IllegalArgumentException("Invalid reason");
        var explained = new HashSet<String>();
        for (Reason reason : value.reasons()) {
            if (reason == null || !DIMENSIONS.contains(reason.dimension()) || reason.code() == null
                    || !reason.code().matches("[A-Z_]{1,80}") || reason.evidenceRefs() == null
                    || reason.evidenceRefs().isEmpty() || reason.evidenceRefs().size() > 10)
                throw new IllegalArgumentException("Invalid dimension evidence");
            explained.add(reason.dimension());
        }
        if (explained.size() != 5) throw new IllegalArgumentException("Every dimension needs evidence");
        int numerator = 0;
        boolean floor = true;
        boolean missing = false;
        for (int i = 0; i < 5; i++) {
            Integer rating = value.ratings().values().get(i);
            if (rating == null) {
                if (value.uncertainties().isEmpty()) throw new IllegalArgumentException("Null rating requires uncertainty");
                missing = true;
                continue;
            }
            if (rating < 0 || rating > 4) throw new IllegalArgumentException("Rating must be 0-4");
            numerator += policy.weights().get(i) * rating;
            floor &= rating >= policy.minimumRating();
        }
        if (missing) return new Verdict(null, false);
        return new Verdict(numerator, floor && numerator >= policy.threshold() * 4
                && value.hardViolations().isEmpty() && value.uncertainties().isEmpty());
    }
}
