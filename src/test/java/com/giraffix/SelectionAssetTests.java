package com.giraffix;

import com.giraffix.config.S3Properties;
import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.Asset;
import com.giraffix.generation.GeneratedAssetStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectionAssetTests {
    S3Client s3;SelectionAssets assets;S3Properties properties;
    @BeforeEach void setup(){s3=mock(S3Client.class);properties=new S3Properties("private-test","us-east-1",null,null,null);
        assets=new SelectionAssets(s3,properties);when(s3.utilities()).thenReturn(S3Utilities.builder().region(Region.US_EAST_1).build());}
    void bytes(byte[] image){when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call->new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream(image)));}
    @Test void resolvesOnlyOwnedProductKeysAndHashesContents() throws Exception {
        byte[] data=ProductImageProcessorTests.fixture("png");bytes(data);UUID id=UUID.randomUUID();
        String url=s3.utilities().getUrl(GetUrlRequest.builder().bucket("private-test").key("products/"+id+"/front.png").build()).toExternalForm();
        var asset=assets.product(id,url,"image/png");assertThat(asset.sha256()).isEqualTo(JsonSupport.digest(data));assertThat(assets.read(asset)).isEqualTo(data);
    }
    @Test void rejectsExternalAndOtherProductUrlsBeforeS3Read(){
        UUID id=UUID.randomUUID();String url=s3.utilities().getUrl(GetUrlRequest.builder().bucket("private-test").key("products/"+UUID.randomUUID()+"/front.png").build()).toExternalForm();
        for(String invalid:new String[]{url,"https://external.example/image",null})assertThatThrownBy(()->assets.product(id,invalid,"image/png")).isInstanceOf(ResponseStatusException.class);
        verify(s3,never()).getObject(any(GetObjectRequest.class));
    }
    @Test void changedOrOversizedImageCannotEnterModelContext() throws Exception {
        bytes(ProductImageProcessorTests.fixture("png"));assertThatThrownBy(()->assets.read(new Asset("private-test","key","image/png","wrong-hash")))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        bytes(new byte[5242881]);assertThatThrownBy(()->assets.read(new Asset("private-test","key","image/png",null))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void generatedAssetsUseStablePrivateKeysAndPreparedBytes() throws Exception {
        UUID owner=UUID.randomUUID(),job=UUID.randomUUID();byte[] bytes=ProductImageProcessorTests.fixture("png");
        var asset=new GeneratedAssetStorage(s3,properties).put(owner,job,bytes);
        assertThat(asset.key()).isEqualTo("creations/"+owner+"/"+job+"/output");assertThat(asset.sha256()).isEqualTo(JsonSupport.digest(bytes));
        verify(s3).putObject(argThat((PutObjectRequest request)->request.acl()==null && request.bucket().equals("private-test") && request.contentType().equals("image/png")),any(RequestBody.class));
    }

    @Test void explicitAdCleanupRejectsOtherOwnersJobsBucketsAndProductPhotos(){
        UUID owner=UUID.randomUUID(),job=UUID.randomUUID();
        var storage=new GeneratedAssetStorage(s3,properties);
        String expected="creations/"+owner+"/"+job+"/output";
        for(var asset:java.util.List.of(new Asset("foreign",expected,"image/png",null),
                new Asset("private-test","creations/"+UUID.randomUUID()+"/"+job+"/output","image/png",null),
                new Asset("private-test","creations/"+owner+"/"+UUID.randomUUID()+"/output","image/png",null),
                new Asset("private-test","products/"+UUID.randomUUID()+"/photo.png","image/png",null)))
            assertThatThrownBy(()->storage.delete(owner,job,asset)).isInstanceOf(IllegalArgumentException.class);
        verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void executionScopeReusesVerifiedBytesButNeverCachesAcrossJobs() throws Exception {
        byte[] data=ProductImageProcessorTests.fixture("png");bytes(data);
        var asset=new Asset("private-test","key","image/png",JsonSupport.digest(data));
        try(var scope=assets.openScope()){
            assertThat(assets.read(asset)).isEqualTo(data);
            assertThat(assets.read(asset)).isEqualTo(data);
            verify(s3,times(1)).getObject(any(GetObjectRequest.class));
        }
        bytes(ProductImageProcessorTests.fixture("jpeg"));
        try(var scope=assets.openScope()){
            assertThatThrownBy(()->assets.read(asset)).isInstanceOf(ResponseStatusException.class);
        }
        verify(s3,times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test void fingerprintSeedsOnlyTheVerifiedHashAndUnhashedReadsRemainFresh() throws Exception {
        byte[] data=ProductImageProcessorTests.fixture("png");bytes(data);
        var raw=new Asset("private-test","key","image/png",null);
        try(var scope=assets.openScope()){
            var verified=assets.fingerprint(raw);
            assets.read(verified);
            verify(s3,times(1)).getObject(any(GetObjectRequest.class));
            assets.read(raw);
            verify(s3,times(2)).getObject(any(GetObjectRequest.class));
            assertThatThrownBy(()->assets.read(new Asset("private-test","key","image/png","wrong-hash")))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }
}
