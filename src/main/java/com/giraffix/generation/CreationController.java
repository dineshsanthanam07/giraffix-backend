package com.giraffix.generation;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.Asset;
import com.giraffix.shared.Pages;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

/** Saved ads belong to their original owner independently of the live product. */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="06 Creations")
public class CreationController {
    private final SelectionStore store;
    private final SelectionAssets assets;
    private final GeneratedAdDeletionQueue deletions;

    public CreationController(SelectionStore store,SelectionAssets assets,GeneratedAdDeletionQueue deletions){
        this.store=store;this.assets=assets;this.deletions=deletions;
    }

    @GetMapping("/api/v1/ads")
    @io.swagger.v3.oas.annotations.Operation(summary="List the signed-in user's saved ads",
            description="Optional productId and brandId filters also work after product deletion. Does not list pending generation requests.")
    public List<Creation> saved(@AuthenticationPrincipal OidcUser user,
                                @RequestParam(required=false) UUID productId,@RequestParam(required=false) UUID brandId,
                                @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return listOwned(user.getSubject(),productId,brandId,page,size);
    }

    @GetMapping("/api/v1/products/{productId}/creations")
    public List<Creation> list(@AuthenticationPrincipal OidcUser user,@PathVariable UUID productId,
                              @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        Pages.of(page,size);
        // Retain 404 for unknown/foreign products, but allow an owner's archived history.
        int owned=store.db.queryForObject("""
                SELECT COUNT(*) FROM users u WHERE u.google_id=? AND (
                    EXISTS(SELECT 1 FROM products p JOIN brands b ON b.id=p.brand_id WHERE p.id=? AND b.user_id=u.id)
                    OR EXISTS(SELECT 1 FROM creations c WHERE c.product_id=? AND c.owner_id=u.id))
                """,Integer.class,user.getSubject(),productId,productId);
        if(owned!=1)throw notFound();
        return listOwned(user.getSubject(),productId,null,page,size);
    }

    private List<Creation> listOwned(String subject,UUID productId,UUID brandId,int page,int size){
        Pages.of(page,size);
        var args=new ArrayList<Object>();args.add(subject);
        String filter="";
        if(productId!=null){filter+=" AND c.product_id=?";args.add(productId);}
        if(brandId!=null){filter+=" AND c.brand_id=?";args.add(brandId);}
        args.add(size);args.add((long)page*size);
        return store.db.query("""
                SELECT c.id,c.job_id,c.concept_id,c.created_at,c.aspect_ratio,c.quality,c.owner_id,c.product_id,c.brand_id
                FROM creations c JOIN users u ON u.id=c.owner_id WHERE u.google_id=?
                """+filter+" ORDER BY c.created_at DESC,c.id DESC LIMIT ? OFFSET ?",this::map,args.toArray());
    }

    @GetMapping({"/api/v1/creations/{id}","/api/v1/ads/saved/{id}"})
    @io.swagger.v3.oas.annotations.Operation(summary="Get a saved ad by its creation ID")
    public Detail get(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id){
        return store.db.query("""
                SELECT c.* FROM creations c JOIN users u ON u.id=c.owner_id WHERE c.id=? AND u.google_id=?
                """,(r,n)->new Detail(map(r,n),r.getString("image_prompt"),r.getString("prompt_version")),id,user.getSubject())
                .stream().findFirst().orElseThrow(CreationController::notFound);
    }

    @GetMapping({"/api/v1/creations/{id}/image","/api/v1/ads/saved/{id}/image"})
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id){
        var rows=store.db.query("""
                SELECT c.asset_json FROM creations c JOIN users u ON u.id=c.owner_id WHERE c.id=? AND u.google_id=?
                """,(r,n)->r.getString(1),id,user.getSubject());
        if(rows.isEmpty())throw notFound();
        Asset asset=store.json.read(rows.get(0),Asset.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(asset.mediaType()))
                .header("X-Content-Type-Options","nosniff").body(assets.read(asset));
    }

    @DeleteMapping({"/api/v1/creations/{id}","/api/v1/ads/saved/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.Operation(summary="Delete a saved ad",
            description="Deletes saved-ad access and commits durable image cleanup. Product deletion never calls this action.")
    public void delete(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id){
        store.transaction(()->{
            var rows=store.db.query("""
                    SELECT c.owner_id,c.job_id,c.asset_json FROM creations c WHERE c.id=?
                    AND EXISTS(SELECT 1 FROM users u WHERE u.id=c.owner_id AND u.google_id=?) FOR UPDATE
                    """,(r,n)->new Stored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                    store.json.read(r.getString(3),Asset.class)),id,user.getSubject());
            if(rows.isEmpty())throw notFound();
            var saved=rows.get(0);
            deletions.enqueue(saved.owner(),saved.job(),saved.asset());
            store.db.update("DELETE FROM creations WHERE id=?",id);
            store.db.update("UPDATE generation_jobs SET status='DELETED' WHERE id=?",saved.job());
            return null;
        });
    }

    private Creation map(java.sql.ResultSet r,int index) throws java.sql.SQLException {
        UUID id=r.getObject("id",UUID.class);
        return new Creation(id,r.getObject("job_id",UUID.class),r.getObject("concept_id",UUID.class),
                "/api/v1/creations/"+id+"/image",r.getTimestamp("created_at").toInstant(),r.getString("aspect_ratio"),
                r.getString("quality"),r.getObject("owner_id",UUID.class),r.getObject("product_id",UUID.class),r.getObject("brand_id",UUID.class));
    }
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved ad not found");}
    private record Stored(UUID owner,UUID job,Asset asset){}
    public record Creation(UUID id,UUID jobId,UUID conceptId,String imageUrl,Instant createdAt,String aspectRatio,String quality,
                           UUID ownerId,UUID productId,UUID brandId){}
    public record Detail(Creation ad,String imagePrompt,String promptVersion){}
}
