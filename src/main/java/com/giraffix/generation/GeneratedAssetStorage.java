package com.giraffix.generation;

import com.giraffix.config.S3Properties;
import com.giraffix.selection.JsonSupport;
import com.giraffix.selection.SelectionModels.Asset;
import com.giraffix.shared.image.ImageProcessor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.util.UUID;

@Service
public class GeneratedAssetStorage {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GeneratedAssetStorage.class);
    private final S3Client s3;
    private final S3Properties config;
    public GeneratedAssetStorage(S3Client s3,S3Properties config){this.s3=s3;this.config=config;}
    public Asset put(UUID owner,UUID job,byte[] image){
        var prepared=ImageProcessor.prepare(image,5242880);
        if(config.bucket()==null || config.bucket().isBlank())throw new IllegalStateException("S3 bucket required");
        // Deterministic private key makes upload/review recovery idempotent.
        String key="creations/"+owner+"/"+job+"/output";
        s3.putObject(PutObjectRequest.builder().bucket(config.bucket()).key(key).contentType(prepared.mediaType())
                .contentLength((long)prepared.bytes().length).build(),RequestBody.fromBytes(prepared.bytes()));
        return new Asset(config.bucket(),key,prepared.mediaType(),JsonSupport.digest(prepared.bytes()));
    }
    public void validateDeleteTarget(UUID owner,UUID job,Asset asset){
        String expected="creations/"+owner+"/"+job+"/output";
        if(!java.util.Objects.equals(config.bucket(),asset.bucket()) || !expected.equals(asset.key())){
            throw new IllegalArgumentException("Unexpected generated ad cleanup object");
        }
    }
    public void delete(UUID owner,UUID job,Asset asset){
        validateDeleteTarget(owner,job,asset);
        s3.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(asset.bucket()).key(asset.key()).build());
        log.info("stage=creation_cleanup_completed jobId={}",job);
    }
    public void cleanup(UUID owner,UUID job,Asset asset){
        try{
            delete(owner,job,asset);
        }catch(RuntimeException ex){log.error("stage=creation_cleanup_failed jobId={} errorType={} action=retry_cleanup",job,ex.getClass().getSimpleName());}
    }
}
