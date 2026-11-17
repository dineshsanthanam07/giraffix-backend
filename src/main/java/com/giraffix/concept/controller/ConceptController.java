package com.giraffix.concept.controller;

import com.giraffix.concept.dto.ConceptResponse;
import com.giraffix.concept.service.ConceptService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/concepts")
public class ConceptController {
    private final ConceptService concepts;
    public ConceptController(ConceptService concepts) { this.concepts = concepts; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload and analyze one concept reference image",
            description = "Upload one image using image (5 MB maximum). Stores the image in S3 and the validated "
                    + "analysis in the concepts table, including unusable results. Uses the existing session and CSRF protection; "
                    + "admin-role enforcement is deferred.")
    public ConceptResponse create(@AuthenticationPrincipal OidcUser user,
                                  @RequestPart("image") MultipartFile image) {
        return concepts.create(user.getSubject(), image);
    }

    @GetMapping("/{id}")
    public ConceptResponse get(@PathVariable UUID id) { return concepts.get(id); }

    @GetMapping
    public Page<ConceptResponse> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return concepts.list(page, size);
    }
}
