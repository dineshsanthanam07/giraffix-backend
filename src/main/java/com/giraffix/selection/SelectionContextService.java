package com.giraffix.selection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.giraffix.selection.SelectionModels.*;

@Service
public class SelectionContextService {
    private final JdbcTemplate db;
    private final JsonSupport json;
    private final SelectionAssets assets;
    private final TransactionTemplate tx;
    public SelectionContextService(JdbcTemplate db, JsonSupport json, SelectionAssets assets, PlatformTransactionManager tm) {
        this.db=db; this.json=json; this.assets=assets; tx=new TransactionTemplate(tm);
    }
    public UUID owner(String subject) {
        return db.query("SELECT id FROM users WHERE google_id=?",(r,n)->r.getObject(1,UUID.class),subject).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found"));
    }
    public void authorize(UUID owner, UUID product) {
        if (db.queryForObject("SELECT COUNT(*) FROM products p JOIN brands b ON b.id=p.brand_id WHERE p.id=? AND b.user_id=?",Integer.class,product,owner)!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    }
    public void lock(UUID owner, UUID product) {
        var brand=db.query("SELECT b.id FROM products p JOIN brands b ON b.id=p.brand_id WHERE p.id=? AND b.user_id=?",
                (r,n)->r.getObject(1,UUID.class),product,owner);
        if (brand.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
        if(db.queryForList("SELECT id FROM brands WHERE id=? AND user_id=? FOR UPDATE",brand.get(0),owner).isEmpty()
                || db.queryForList("SELECT id FROM products WHERE id=? AND brand_id=? FOR UPDATE",product,brand.get(0)).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    }
    public Context build(UUID owner, Start request) {
        // Never perform S3 I/O while holding database locks.
        String facts=tx.execute(s->{lock(owner,request.productId());return facts(owner,request.productId());});
        var productFacts=json.mapper.readTree(facts);
        if(productFacts.path("name").asText("").isBlank() || productFacts.path("category").asText("").isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Selection requires a confirmed product name and category");
        var rows=productFacts.path("images");
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Selection requires an owned uploaded product image");
        List<Asset> images=new ArrayList<>();
        for(var row:rows)images.add(assets.product(request.productId(),row.get(1).asText(),row.get(2).asText()));
        var logoData = productFacts.path("brandLogo");
        Asset logo = logoData.isMissingNode() ? null : assets.brandLogo(logoData.path("bucket").asText(),
                logoData.path("key").asText(), logoData.path("mediaType").asText());
        return new Context(owner,request.productId(),facts,request.intent().trim(),request.format().trim(),request.controls(),images,
                request.aspectRatio(), logo, request.aspectRatio()==null?request.quality():Objects.toString(request.quality(),"high"));
    }
    public String facts(UUID owner, UUID product) {
        var values=db.query("""
            SELECT p.name,p.category,p.description,p.key_features,p.target_audience,p.image_url,
                   b.name,b.description,b.industry,b.target_audience,b.primary_color,b.secondary_color,
                   b.logo_bucket,b.logo_key,b.logo_media_type,b.logo_url,b.logo_size_bytes
            FROM products p JOIN brands b ON b.id=p.brand_id WHERE p.id=? AND b.user_id=?
            """,(r,n)->{
                var map=new TreeMap<String,Object>();
                String[] keys={"name","category","description","keyFeatures","targetAudience","legacyImageUrl",
                        "brandName","brandDescription","brandIndustry","brandAudience","primaryColor","secondaryColor"};
                for(int i=0;i<keys.length;i++) map.put(keys[i],r.getString(i+1));
                if (r.getString(14) != null) map.put("brandLogo", Map.of("bucket", r.getString(13),
                        "key", r.getString(14), "mediaType", r.getString(15), "url", r.getString(16), "sizeBytes", r.getLong(17)));
                return map;
            },product,owner).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found"));
        values.put("images",db.query("SELECT id,storage_url,media_type,size_bytes,position FROM product_images WHERE product_id=? ORDER BY position",
                (r,n)->List.of(r.getObject(1).toString(),Objects.toString(r.getString(2),""),r.getString(3),r.getLong(4),r.getInt(5)),product));
        return json.write(values);
    }
    public boolean current(Context context) {
        try {
            lock(context.ownerId(),context.productId());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value()==404) return false;
            throw ex;
        }
        return context.factsJson().equals(facts(context.ownerId(),context.productId()));
    }
}
