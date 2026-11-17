package com.giraffix.concept.controller;

import com.giraffix.concept.service.ConceptLibrary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/concepts/{conceptId}/revisions")
public class ConceptReviewController {
    private final ConceptLibrary library;
    public ConceptReviewController(ConceptLibrary library) { this.library=library; }
    @GetMapping
    public List<ConceptLibrary.Revision> list(@AuthenticationPrincipal OidcUser user,@PathVariable UUID conceptId) {
        return library.revisions(user.getSubject(),conceptId);
    }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ConceptLibrary.Revision revise(@AuthenticationPrincipal OidcUser user,@PathVariable UUID conceptId,@RequestBody JsonNode body) {
        try { return library.revise(user.getSubject(),conceptId,body); }
        catch (IllegalArgumentException ex) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid concept revision"); }
    }
    @PostMapping("/{revisionId}/approve")
    public ConceptLibrary.Revision approve(@AuthenticationPrincipal OidcUser user,@PathVariable UUID conceptId,@PathVariable UUID revisionId) {
        return library.review(user.getSubject(),conceptId,revisionId,true);
    }
    @PostMapping("/{revisionId}/revoke")
    public ConceptLibrary.Revision revoke(@AuthenticationPrincipal OidcUser user,@PathVariable UUID conceptId,@PathVariable UUID revisionId) {
        return library.review(user.getSubject(),conceptId,revisionId,false);
    }
}
