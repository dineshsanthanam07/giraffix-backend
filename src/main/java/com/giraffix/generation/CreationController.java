package com.giraffix.generation;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.Asset;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController
public class CreationController {
    private final SelectionStore store;
    private final SelectionContextService contexts;
    private final SelectionAssets assets;
    public CreationController(SelectionStore store,SelectionContextService contexts,SelectionAssets assets){this.store=store;this.contexts=contexts;this.assets=assets;}
    @GetMapping("/api/v1/products/{productId}/creations")
    public List<Creation> list(@AuthenticationPrincipal OidcUser user,@PathVariable UUID productId,
                              @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        if(page<0 || size<1 || size>100 || page>1000000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        UUID owner=contexts.owner(user.getSubject());contexts.authorize(owner,productId);
        return store.db.query("SELECT id,job_id,concept_id,created_at,aspect_ratio,quality FROM creations WHERE owner_id=? AND product_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                (r,n)->new Creation(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                        "/api/v1/creations/"+r.getObject(1)+"/image",r.getTimestamp(4).toInstant(),r.getString(5),r.getString(6)),owner,productId,size,(long)page*size);
    }
    @GetMapping("/api/v1/creations/{id}/image")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id){
        UUID owner=contexts.owner(user.getSubject());
        var rows=store.db.query("SELECT product_id,asset_json FROM creations WHERE id=? AND owner_id=?",
                (r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2)},id,owner);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Creation not found");
        contexts.authorize(owner,(UUID)rows.get(0)[0]);
        Asset asset=store.json.read((String)rows.get(0)[1],Asset.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(asset.mediaType()))
                .header("X-Content-Type-Options","nosniff").body(assets.read(asset));
    }
    public record Creation(UUID id,UUID jobId,UUID conceptId,String imageUrl,Instant createdAt,String aspectRatio,String quality){}
}
