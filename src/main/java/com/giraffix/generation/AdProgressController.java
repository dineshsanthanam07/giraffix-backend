package com.giraffix.generation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.UUID;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="05 Ads")
public class AdProgressController {
    private final AdProgressStream streams;
    public AdProgressController(AdProgressStream streams){this.streams=streams;}

    @GetMapping(value="/api/v1/ads/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    @io.swagger.v3.oas.annotations.Operation(summary="Stream live ad generation progress",
            description="Session-authenticated SSE using the operation ID returned by POST /ads. Subscribe to progress events; close the browser EventSource when terminal is true.")
    public ResponseEntity<SseEmitter> events(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,HttpServletRequest request){
        SseEmitter emitter=streams.subscribe(user==null?null:user.getSubject(),id,request.getSession(false));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering","no").body(emitter);
    }
}
