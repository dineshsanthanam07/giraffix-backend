package com.giraffix.prompt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="09 Admin - Prompts")
public class PromptAdminController {
    private final PromptTemplates templates;
    public PromptAdminController(PromptTemplates templates){this.templates=templates;}
    public record Draft(@NotBlank @Size(max=60000) String content,@Min(1) int expectedActiveVersion){}
    public record Publish(@Min(1) int version,@Min(1) int expectedActiveVersion){}

    @GetMapping(value="/admin/prompts",produces=MediaType.TEXT_HTML_VALUE)
    @io.swagger.v3.oas.annotations.Operation(hidden=true)
    public ResponseEntity<Resource> editor(@AuthenticationPrincipal OidcUser user){
        templates.requireAdmin(user.getSubject());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ClassPathResource("admin/prompts.html"));
    }
    @GetMapping("/api/v1/admin/prompts")
    public List<PromptTemplates.Summary> list(@AuthenticationPrincipal OidcUser user){return templates.list(user.getSubject());}
    @GetMapping("/api/v1/admin/prompts/{key}")
    public PromptTemplates.Revision get(@AuthenticationPrincipal OidcUser user,@PathVariable String key){return templates.get(user.getSubject(),PromptTemplates.Kind.from(key));}
    @GetMapping("/api/v1/admin/prompts/{key}/versions")
    public List<PromptTemplates.VersionInfo> history(@AuthenticationPrincipal OidcUser user,@PathVariable String key,
                                                    @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return templates.history(user.getSubject(),PromptTemplates.Kind.from(key),page,size);
    }
    @GetMapping("/api/v1/admin/prompts/{key}/versions/{version}")
    public PromptTemplates.Revision version(@AuthenticationPrincipal OidcUser user,@PathVariable String key,@PathVariable int version){
        templates.requireAdmin(user.getSubject());return templates.version(PromptTemplates.Kind.from(key),version);
    }
    @GetMapping("/api/v1/admin/prompts/{key}/publications")
    public List<PromptTemplates.Publication> publications(@AuthenticationPrincipal OidcUser user,@PathVariable String key,
                                                         @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return templates.publications(user.getSubject(),PromptTemplates.Kind.from(key),page,size);
    }
    @PostMapping("/api/v1/admin/prompts/{key}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public PromptTemplates.Revision draft(@AuthenticationPrincipal OidcUser user,@PathVariable String key,@Valid @RequestBody Draft body){
        return templates.draft(user.getSubject(),PromptTemplates.Kind.from(key),body.content(),body.expectedActiveVersion());
    }
    @PostMapping("/api/v1/admin/prompts/{key}/publish")
    public PromptTemplates.Revision publish(@AuthenticationPrincipal OidcUser user,@PathVariable String key,@Valid @RequestBody Publish body){
        return templates.publish(user.getSubject(),PromptTemplates.Kind.from(key),body.version(),body.expectedActiveVersion());
    }
}
