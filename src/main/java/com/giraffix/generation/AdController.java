package com.giraffix.generation;

import com.giraffix.selection.SelectionModels.Start;
import com.giraffix.selection.SelectionService;
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
@io.swagger.v3.oas.annotations.tags.Tag(name="05 Ads")
@RequestMapping("/api/v1/ads")
public class AdController {
    private final SelectionService selections;
    private final AdStatusService statuses;
    private final ImageGenerator generator;

    public AdController(SelectionService selections, AdStatusService statuses, ImageGenerator generator) {
        this.selections = selections;
        this.statuses = statuses;
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
        UUID selection = selections.startId(user.getSubject(), key, new Start(request.productId(), request.intent(),
                "Advertisement composed for aspect ratio " + request.aspectRatio(), request.controls(), null, request.aspectRatio(), request.quality()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/ads/" + selection))
                .body(statuses.get(user.getSubject(), selection));
    }

    @GetMapping("/{id}")
    public Status get(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id) {
        return statuses.get(user.getSubject(), id);
    }

}
