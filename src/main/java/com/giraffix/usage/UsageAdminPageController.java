package com.giraffix.usage;

import com.giraffix.prompt.PromptTemplates;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
public class UsageAdminPageController {
    private final PromptTemplates admins;
    public UsageAdminPageController(PromptTemplates admins){this.admins=admins;}
    @GetMapping(value="/admin/usage",produces=MediaType.TEXT_HTML_VALUE)
    @io.swagger.v3.oas.annotations.Operation(hidden=true)
    public ResponseEntity<Resource> page(@AuthenticationPrincipal OidcUser user){
        admins.requireAdmin(user.getSubject());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ClassPathResource("admin/usage.html"));
    }
}
