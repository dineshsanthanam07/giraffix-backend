package com.giraffix.generation;

import com.giraffix.selection.SelectionContextService;
import com.giraffix.selection.SelectionModels.Start;
import com.giraffix.selection.SelectionService;
import com.giraffix.selection.SelectionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ads")
public class AdController {
    private final SelectionService selections;
    private final SelectionStore store;
    private final SelectionContextService contexts;
    private final ImageGenerator generator;

    public AdController(SelectionService selections, SelectionStore store, SelectionContextService contexts, ImageGenerator generator) {
        this.selections = selections;
        this.store = store;
        this.contexts = contexts;
        this.generator = generator;
    }

    public record Request(@NotNull UUID productId,
                          @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"1:1","4:5","3:4","16:9","9:16"})
                          @NotNull @Pattern(regexp="1:1|4:5|3:4|16:9|9:16") String aspectRatio,
                          @Pattern(regexp="(?s).*\\S.*") @Size(max=2000) String intent,
                          @Size(max=2000) String controls,
                          @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"low","medium","high"},defaultValue="high")
                          @Pattern(regexp="low|medium|high") String quality) {
        public Request {if (quality == null) quality = "high";}
    }
    public record Status(UUID id, String status, String selectionStatus, UUID generationJobId,
                         UUID creationId, String imageUrl, String aspectRatio, String quality, boolean fallbackUsed, Error error) {}
    public record Error(String code, String stage, String message) {}

    @PostMapping
    public ResponseEntity<Status> create(@AuthenticationPrincipal OidcUser user,
                                        @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody Request request) {
        if (!generator.available()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Configure the OpenAI image generation API key");
        var selection = selections.start(user.getSubject(), key, new Start(request.productId(), request.intent(),
                "Advertisement composed for aspect ratio " + request.aspectRatio(), request.controls(), null, request.aspectRatio(), request.quality()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/ads/" + selection.id()))
                .body(status(user.getSubject(), selection.id()));
    }

    @GetMapping("/{id}")
    public Status get(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id) {
        return status(user.getSubject(), id);
    }

    private Status status(String subject, UUID id) {
        var selected = selections.status(subject, id); // Includes account/product ownership checks.
        var request = store.get(id);
        var creations = store.db.query("SELECT id FROM creations WHERE job_id=? AND owner_id=?",
                (r,n) -> r.getObject(1, UUID.class), selected.generationJobId(), contexts.owner(subject));
        UUID creation = creations.isEmpty() ? null : creations.get(0);
        String state = selected.generationStatus() == null ? selected.status() : selected.generationStatus();
        var errors = store.db.query("SELECT failure_code,failure_stage,failure_message FROM generation_jobs WHERE id=? AND failure_code IS NOT NULL",
                (r,n) -> new Error(r.getString(1),r.getString(2),r.getString(3)),selected.generationJobId());
        return new Status(id, state, selected.status(), selected.generationJobId(), creation,
                creation == null ? null : "/api/v1/creations/" + creation + "/image",
                request.context().aspectRatio(), java.util.Objects.toString(request.context().quality(),"high"), selected.fallbackUsed(),
                errors.isEmpty()?null:errors.get(0));
    }
}
