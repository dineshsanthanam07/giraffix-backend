package com.giraffix.usage;

import com.giraffix.prompt.PromptTemplates;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
import static com.giraffix.usage.UsageService.*;

@RestController
@RequestMapping("/api/v1/admin/usage")
@io.swagger.v3.oas.annotations.tags.Tag(name="10 Admin - Usage")
public class UsageAdminController {
    private final UsageService usage;
    private final PromptTemplates admins;
    public UsageAdminController(UsageService usage,PromptTemplates admins){this.usage=usage;this.admins=admins;}
    private void admin(OidcUser user){admins.requireAdmin(user.getSubject());}
    public record Report(String unit,String costBasis,Summary usage){}

    @GetMapping
    public Report summary(@AuthenticationPrincipal OidcUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID userId,@RequestParam(required=false) UUID productId,
            @RequestParam(required=false) UUID brandId,@RequestParam(required=false) Kind kind){
        admin(user);return new Report("USD_MICROS","ESTIMATED",usage.summary(from,to,userId,productId,brandId,kind));
    }
    @GetMapping("/users")
    public List<UserUsage> users(@AuthenticationPrincipal OidcUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID userId,@RequestParam(required=false) UUID productId,
            @RequestParam(required=false) UUID brandId,@RequestParam(required=false) Kind kind,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        admin(user);return usage.users(from,to,userId,productId,brandId,kind,page,size);
    }
    @GetMapping("/operations")
    public List<Operation> operations(@AuthenticationPrincipal OidcUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID userId,@RequestParam(required=false) UUID productId,
            @RequestParam(required=false) UUID brandId,@RequestParam(required=false) Kind kind,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        admin(user);return usage.operations(from,to,userId,productId,brandId,kind,page,size);
    }
    @GetMapping("/operations/{id}")
    public Operation detail(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id){admin(user);return usage.operation(id);}
    @GetMapping("/operations/{id}/calls")
    public List<ModelCall> calls(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){admin(user);return usage.calls(id,page,size);}
    @GetMapping("/daily")
    public List<DailyUsage> daily(@AuthenticationPrincipal OidcUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID userId,@RequestParam(required=false) UUID productId,
            @RequestParam(required=false) UUID brandId,@RequestParam(required=false) Kind kind){admin(user);return usage.daily(from,to,userId,productId,brandId,kind);}
    @GetMapping("/models")
    public List<ModelUsage> models(@AuthenticationPrincipal OidcUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID userId,@RequestParam(required=false) UUID productId,
            @RequestParam(required=false) UUID brandId,@RequestParam(required=false) Kind kind,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){admin(user);return usage.models(from,to,userId,productId,brandId,kind,page,size);}
}
