package com.giraffix.selection;

import com.giraffix.config.S3Properties;
import com.giraffix.shared.image.ImageProcessor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import java.net.URI;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import static com.giraffix.selection.SelectionModels.*;

@Service
public class SelectionAssets {
    private final S3Client s3;
    private final S3Properties config;
    private final ThreadLocal<Scope> scope = new ThreadLocal<>();
    public SelectionAssets(S3Client s3, S3Properties config) { this.s3 = s3; this.config = config; }

    public Asset product(UUID product, String url, String mediaType) {
        try {
            if (config.bucket() == null || config.bucket().isBlank() || url == null) throw new IllegalArgumentException();
            String path = URI.create(url).getPath();
            String key = path == null ? "" : path.substring(1);
            String expected = s3.utilities().getUrl(GetUrlRequest.builder().bucket(config.bucket()).key(key).build()).toExternalForm();
            if (!key.startsWith("products/" + product + "/") || !expected.equals(url)) throw new IllegalArgumentException();
            return fingerprint(new Asset(config.bucket(), key, mediaType, null));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product needs an owned S3 image; legacy external URLs are not eligible");
        }
    }

    public Asset fingerprint(Asset asset) {
        byte[] bytes=read(asset);
        Asset fingerprinted=new Asset(asset.bucket(), asset.key(), asset.mediaType(), JsonSupport.digest(bytes));
        remember(fingerprinted,bytes);
        return fingerprinted;
    }

    /** A bounded cache for one execution only. Never reuse unhashed assets or cross job boundaries. */
    public Scope openScope() {
        Scope current=new Scope(scope.get());
        scope.set(current);
        return current;
    }
    private void remember(Asset asset,byte[] bytes) {
        Scope current=scope.get();
        if(current!=null && asset.sha256()!=null && !current.images.containsKey(asset)
                && current.bytes+bytes.length<=32L*1024*1024) {
            current.images.put(asset,bytes);
            current.bytes+=bytes.length;
        }
    }
    public final class Scope implements AutoCloseable {
        private final Scope previous;
        private final Map<Asset,byte[]> images=new HashMap<>();
        private long bytes;
        private Scope(Scope previous){this.previous=previous;}
        @Override public void close(){images.clear();if(previous==null)scope.remove();else scope.set(previous);}
    }

    public Asset brandLogo(String bucket, String key, String mediaType) {
        if (config.bucket() == null || !config.bucket().equals(bucket) || key == null || !key.startsWith("brands/logos/"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Brand logo must belong to configured S3 storage");
        return fingerprint(new Asset(bucket, key, mediaType, null));
    }

    public byte[] read(Asset asset) {
        Scope current=scope.get();
        if(current!=null && asset.sha256()!=null && current.images.containsKey(asset))return current.images.get(asset);
        try (var stream = s3.getObject(GetObjectRequest.builder().bucket(asset.bucket()).key(asset.key()).build())) {
            byte[] bytes = stream.readNBytes(5242881);
            var prepared = ImageProcessor.prepare(bytes, 5242880);
            if (!prepared.mediaType().equals(asset.mediaType()) || prepared.converted()) throw new IllegalArgumentException("Image metadata mismatch");
            if (asset.sha256() != null && !asset.sha256().equals(JsonSupport.digest(bytes)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stored image changed after selection snapshot");
            remember(asset,bytes);
            return bytes;
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read selection image"); }
    }
}
