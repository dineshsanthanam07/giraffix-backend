package com.giraffix.generation;

/** Safe, bounded diagnostics for a completed or rejected prompt-writing request. */
public class PromptGenerationException extends RuntimeException {
    private final String code;
    private final String providerId;
    private final String stopReason;
    private final Long costMicros;

    public PromptGenerationException(String code, String message, String providerId, String stopReason, Long costMicros) {
        super(message);
        this.code=code;this.providerId=providerId;this.stopReason=stopReason;this.costMicros=costMicros;
    }
    public String code(){return code;}
    public String providerId(){return providerId;}
    public String stopReason(){return stopReason;}
    public Long costMicros(){return costMicros;}
}
