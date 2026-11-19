package com.giraffix.selection;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SelectionPolicy {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.giraffix.prompt.PromptTemplates prompts;
    @Value("${giraffix.selection.picker-enabled:true}") public boolean pickerEnabled = true;
    @Value("${giraffix.selection.batch-size:5}") public int batchSize = 5;
    @Value("${giraffix.selection.max-rounds:3}") public int maxRounds = 3;
    @Value("${giraffix.selection.max-verification-calls:3}") public int maxVerificationCalls = 3;
    @Value("${giraffix.selection.max-retries:1}") public int maxRetries = 1;
    @Value("${giraffix.selection.deadline-seconds:90}") public int deadlineSeconds = 90;
    @Value("${giraffix.selection.minimum-rating:3}") public int minimumRating = 3;
    @Value("${giraffix.selection.threshold:80}") public int threshold = 80;
    @Value("${giraffix.selection.low-score-threshold:40}") public int lowScoreThreshold = 40;
    @Value("${giraffix.selection.cooldown-days:7}") public int cooldownDays = 7;
    @Value("${giraffix.selection.max-cost-micros:2500000}") public long maxCostMicros = 2500000;
    @Value("${giraffix.selection.max-attempt-cost-micros:350000}") public long maxAttemptCostMicros = 350000;
    @Value("${giraffix.selection.model:claude-sonnet-5-5}") public String model = "claude-sonnet-5-5";
    @Value("${giraffix.selection.max-output-tokens:6000}") public int maxOutputTokens = 6000;
    @Value("${giraffix.selection.max-input-tokens:120000}") public int maxInputTokens = 120000;
    @Value("${giraffix.selection.input-micros-per-million:2000000}") public long inputRate = 2000000;
    @Value("${giraffix.selection.output-micros-per-million:10000000}") public long outputRate = 10000000;
    @Value("${giraffix.selection.weights:30,25,20,15,10}") public List<Integer> weights = List.of(30,25,20,15,10);

    @PostConstruct
    public void validate() {
        if (batchSize < 1 || batchSize > 100 || (!pickerEnabled && batchSize > 10)
                || maxRounds < 1 || maxVerificationCalls < 1 || maxRetries < 0
                || deadlineSeconds < 1 || minimumRating < 0 || minimumRating > 4 || threshold < 0 || threshold > 100
                || lowScoreThreshold < 0 || lowScoreThreshold > 100 || (!pickerEnabled && lowScoreThreshold > threshold) || cooldownDays < 1
                || maxAttemptCostMicros < 1 || maxCostMicros < maxAttemptCostMicros
                || weights == null || weights.size() != 5 || weights.stream().anyMatch(w -> w == null || w < 0)
                || weights.stream().mapToInt(Integer::intValue).sum() != 100 || model == null || model.isBlank()
                || maxInputTokens < 1 || maxOutputTokens < 1 || inputRate < 0 || outputRate < 0
                || cost(maxInputTokens, maxOutputTokens) > maxAttemptCostMicros) {
            throw new IllegalArgumentException("Invalid concept selection policy or insufficient attempt cost envelope");
        }
    }

    public long cost(long input, long output) {
        return Math.addExact(Math.multiplyExact(input, inputRate), Math.multiplyExact(output, outputRate) + 999999) / 1000000;
    }

    public Snapshot snapshot() {
        String rubric=pickerEnabled ? "concept-picker-prompt-v1" : "selection-rubric-v1";
        if(pickerEnabled && prompts!=null){
            int revision=prompts.current(com.giraffix.prompt.PromptTemplates.Kind.PICKER).version();
            if(revision>1)rubric+="@"+revision;
        }
        return new Snapshot(pickerEnabled ? ConceptPickerRules.VERSION : "weighted-fit-v1",
                rubric, model, batchSize, maxRounds,
                maxVerificationCalls, maxRetries, deadlineSeconds, minimumRating, threshold,
                lowScoreThreshold, cooldownDays, maxCostMicros, maxAttemptCostMicros,
                maxInputTokens, maxOutputTokens, inputRate, outputRate, List.copyOf(weights));
    }

    public record Snapshot(String version, String rubric, String model, int batchSize, int maxRounds,
            int maxVerificationCalls, int maxRetries, int deadlineSeconds, int minimumRating, int threshold,
            int lowScoreThreshold, int cooldownDays, long maxCostMicros, long maxAttemptCostMicros,
            int maxInputTokens, int maxOutputTokens, long inputRate, long outputRate, List<Integer> weights) {}
}
