package com.giraffix;

import com.giraffix.generation.*;
import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

import static com.giraffix.selection.SelectionStore.stamp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP provider adapters, database workflow, S3 asset handling and final image delivery. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ad_flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "giraffix.ai.api-key=test-only","spring.jpa.show-sql=false","giraffix.selection.worker-enabled=false",
        "giraffix.storage.s3.bucket=ad-test","giraffix.generation.openai.api-key=test-openai",
        "giraffix.generation.openai.model=gpt-image-2",
        "giraffix.generation.prompt-max-output-tokens=8192",
        "giraffix.generation.prompt-model=test-director"})
class AdFlowIntegrationTests {
    static final AtomicReference<String> promptRequest=new AtomicReference<>(), imageRequest=new AtomicReference<>();
    static final AtomicReference<String> promptReply=new AtomicReference<>(), imageReply=new AtomicReference<>();
    static final AtomicInteger promptCalls=new AtomicInteger(), imageCalls=new AtomicInteger();
    static final AtomicInteger imageStatus=new AtomicInteger(200);
    static final String FINAL_PROMPT="Create a 4:5 commercial advertisement. Use the actual product reference unchanged, suspend it in a meaningful frame, use the official brand logo reference when supplied, with headline 'A Fresh Perspective' and supporting copy 'See the everyday differently'.";
    static final HttpServer server=server();
    static HttpServer server() {
        try {
            var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/v1/messages",exchange -> {
                promptCalls.incrementAndGet();
                promptRequest.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes=promptReply.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            http.createContext("/v1/images/edits",exchange -> {
                imageCalls.incrementAndGet();
                imageRequest.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes=imageReply.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.getResponseHeaders().add("x-request-id","image-operation");
                exchange.sendResponseHeaders(imageStatus.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            http.start();return http;
        } catch(IOException e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry properties) {
        properties.add("giraffix.ai.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
        properties.add("giraffix.generation.openai.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
    }
    @AfterAll static void stop(){server.stop(0);}
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired SelectionService selections;
    @Autowired SelectionWorker selectionWorker;
    @Autowired GenerationWorker generation;
    @Autowired SelectionStore store;
    @Autowired SelectionContextService contexts;
    @Autowired SelectionAssets assets;
    @Autowired ImageGenerator generator;
    @Autowired WebApplicationContext webContext;
    @MockitoBean ConceptEvaluator evaluator;
    @MockitoBean OutputReviewer reviewer;
    @MockitoBean S3Client s3;
    final Map<String,byte[]> objects=new ConcurrentHashMap<>();
    UUID owner,brand,product,concept;
    byte[] productPhoto,logoPhoto,outputImage;
    String subject;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        db.update("UPDATE selection_requests SET selected_evaluation_id=NULL");
        db.update("UPDATE concept_evaluations SET verified_evaluation_id=NULL");
        for(String table:List.of("product_concept_exclusions","creations","outbox_events","generation_jobs","concept_allocation_claims","concept_allocations","concept_match_cache",
                "concept_evaluations","selection_attempts","selection_seen","selection_batches","selection_requests","concept_revisions","concepts","product_images","products","brands","users"))db.update("DELETE FROM "+table);
        promptCalls.set(0);imageCalls.set(0);promptRequest.set(null);imageRequest.set(null);
        imageStatus.set(200);
        owner=UUID.randomUUID();brand=UUID.randomUUID();product=UUID.randomUUID();concept=UUID.randomUUID();
        subject="ad-owner-"+owner;
        productPhoto=ProductImageProcessorTests.fixture("png");logoPhoto=ProductImageProcessorTests.fixture("jpeg");
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,5,BufferedImage.TYPE_INT_RGB),"png",output);outputImage=output.toByteArray();
        promptReply.set(mapper.writeValueAsString(Map.of("id","prompt-operation","stop_reason","end_turn",
                "content",List.of(Map.of("type","text","text",FINAL_PROMPT)),"usage",Map.of("input_tokens",100,"output_tokens",100))));
        imageReply.set(mapper.writeValueAsString(Map.of("data",List.of(Map.of("b64_json",Base64.getEncoder().encodeToString(outputImage))),
                "usage",Map.of("input_tokens",100,"output_tokens",100,"input_tokens_details",Map.of("text_tokens",40,"image_tokens",60)))));
        when(s3.utilities()).thenReturn(S3Utilities.builder().region(Region.US_EAST_1).build());
        objects.clear();objects.put("products/"+product+"/front.png",productPhoto);objects.put("concept/"+concept+"/reference.png",productPhoto);
        objects.put("brands/logos/"+brand+".jpg",logoPhoto);
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> {
            GetObjectRequest request=call.getArgument(0);byte[] bytes=objects.get(request.key());
            if(bytes==null)throw NoSuchKeyException.builder().build();
            return new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream(bytes));
        });
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request=call.getArgument(0);RequestBody body=call.getArgument(1);
            try(var input=body.contentStreamProvider().newStream()){objects.put(request.key(),input.readAllBytes());}
            return PutObjectResponse.builder().build();
        });
        db.update("INSERT INTO users(id,google_id,email,onboarding_completed,created_at) VALUES(?,?,?,TRUE,?)",owner,subject,"owner@example.com",stamp(Instant.now()));
        db.update("INSERT INTO brands(id,user_id,name,description,primary_color,created_at,updated_at) VALUES(?,?,'Fresh Brand','Playful identity','#123456',?,?)",brand,owner,stamp(Instant.now()),stamp(Instant.now()));
        db.update("INSERT INTO products(id,brand_id,name,category,description,created_at,updated_at) VALUES(?,?,'Actual Bottle','Drinkware','Saved generated product description',?,?)",product,brand,stamp(Instant.now()),stamp(Instant.now()));
        db.update("INSERT INTO product_images(id,product_id,filename,media_type,position,size_bytes,storage_url,created_at) VALUES(?,?,'front.png','image/png',0,?,?,?)",
                UUID.randomUUID(),product,productPhoto.length,url("products/"+product+"/front.png"),stamp(Instant.now()));
        db.update("INSERT INTO concepts(id,image_bucket,image_key,image_url,image_filename,image_media_type,image_size_bytes,usable,analysis_json,schema_version,created_by,created_at) VALUES(?,'ad-test',?,?,'reference.png','image/png',?,TRUE,?,1,'admin',?)",
                concept,"concept/"+concept+"/reference.png",url("concept/"+concept+"/reference.png"),productPhoto.length,ConceptFixtures.VALID,stamp(Instant.now()));
        db.update("INSERT INTO concept_revisions(id,concept_id,revision,analysis_json,status,created_at) VALUES(?,?,1,?,'APPROVED',?)",concept,concept,ConceptFixtures.VALID,stamp(Instant.now()));
        when(evaluator.evaluate(any(),anyList(),any(),any())).thenAnswer(call -> {
            List<Candidate> candidates=call.getArgument(1);
            return new Evaluated(candidates.stream().map(c ->new Evaluation(c.conceptId(),c.revision(),"Suspend the actual product within a meaningful frame",null,
                    List.of(),List.of(),List.of(),false,90,"STRONG")).toList(),new Usage(100,50,"picker-operation"),List.of());
        });
        when(reviewer.review(any(),any())).thenReturn(new OutputReviewer.Review(true,"Fidelity preserved",1000));
        mvc=mvc(subject);
    }

    MockMvc mvc(String identity) {
        return mvc(identity,generator);
    }
    MockMvc mvc(String identity, ImageGenerator renderer) {
        var user=mock(OidcUser.class);when(user.getSubject()).thenReturn(identity);
        return MockMvcBuilders.standaloneSetup(new AdController(selections,store,contexts,renderer),new CreationController(store,contexts,assets))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(MethodParameter p){return p.getParameterType()==OidcUser.class;}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest r,WebDataBinderFactory f){return user;}
                }).build();
    }
    String url(String key){return s3.utilities().getUrl(GetUrlRequest.builder().bucket("ad-test").key(key).build()).toExternalForm();}
    void attachLogo(){db.update("UPDATE brands SET logo_url=?,logo_bucket='ad-test',logo_key=?,logo_media_type='image/jpeg',logo_size_bytes=? WHERE id=?",
            url("brands/logos/"+brand+".jpg"),"brands/logos/"+brand+".jpg",logoPhoto.length,brand);}
    UUID enqueue(String key) throws Exception {
        return enqueue(key,null);
    }
    UUID enqueue(String key,String quality) throws Exception {
        var request=new LinkedHashMap<String,Object>();request.put("productId",product);request.put("aspectRatio","4:5");
        if(quality!=null)request.put("quality",quality);
        String body=mvc.perform(post("/api/v1/ads").header("Idempotency-Key",key).contentType("application/json")
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).path("id").asText());
    }
    UUID runAd() throws Exception {
        var id=enqueue("ad-"+UUID.randomUUID());selectionWorker.run(id);
        generation.run(selections.status(subject,id).generationJobId());return id;
    }

    @Test void completeFlowUsesActualPhotosLogoAndPromptThenStoresAndReturnsPrivateImage() throws Exception {
        attachLogo();var id=runAd();
        var response=mvc.perform(get("/api/v1/ads/"+id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.aspectRatio").value("4:5"))
                .andReturn().getResponse().getContentAsString();
        var result=mapper.readTree(response);String imageUrl=result.path("imageUrl").asText();
        mvc.perform(get(imageUrl)).andExpect(status().isOk()).andExpect(content().bytes(outputImage))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        var promptBody=mapper.readTree(promptRequest.get());
        assertThat(promptBody.path("system").asText()).contains("Version:","v2","Return ONLY the final image-generation prompt.");
        assertThat(promptBody.path("model").asText()).isEqualTo("test-director");
        var content=promptBody.at("/messages/0/content");
        assertThat(content.toString()).contains("Saved generated product description","Fresh Brand","Playful identity","4:5");
        var imageBlocks=new ArrayList<String>();for(var block:content)if(block.path("type").asText().equals("image"))imageBlocks.add(block.at("/source/data").asText());
        assertThat(imageBlocks).containsExactly(Base64.getEncoder().encodeToString(productPhoto),Base64.getEncoder().encodeToString(productPhoto),Base64.getEncoder().encodeToString(logoPhoto));
        var render=mapper.readTree(imageRequest.get());
        assertThat(render.path("model").asText()).isEqualTo("gpt-image-2");
        assertThat(render.path("size").asText()).isEqualTo("1024x1280");
        assertThat(render.path("prompt").asText()).contains(FINAL_PROMPT,"LAST reference");
        assertThat(render.path("images").size()).isEqualTo(2);
        assertThat(render.at("/images/0/image_url").asText()).isEqualTo("data:image/png;base64,"+Base64.getEncoder().encodeToString(productPhoto));
        assertThat(render.at("/images/1/image_url").asText()).isEqualTo("data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(logoPhoto));
        assertThat(render.has("input_fidelity")).isFalse();
        var job=selections.status(subject,id).generationJobId();
        assertThat(objects.get("creations/"+owner+"/"+job+"/output")).isEqualTo(outputImage);
        assertThat(db.queryForObject("SELECT image_prompt FROM creations WHERE job_id=?",String.class,job)).isEqualTo(FINAL_PROMPT);
        assertThat(db.queryForObject("SELECT prompt_version FROM creations WHERE job_id=?",String.class,job)).isEqualTo("creative-director-v2");
        assertThat(db.queryForObject("SELECT spent_micros FROM generation_jobs WHERE id=?",Long.class,job)).isEqualTo(5880);
        verify(reviewer).review(argThat(b->b.context().brandLogo()!=null && b.imagePrompt().equals(FINAL_PROMPT)),any());
        generation.run(job);assertThat(promptCalls.get()).isEqualTo(1);assertThat(imageCalls.get()).isEqualTo(1);
    }

    @Test void logoIsOptionalAndImageRendererReceivesProductPhotoWithoutInventingLogo() throws Exception {
        var id=runAd();assertThat(selections.status(subject,id).generationStatus()).isEqualTo("SUCCEEDED");
        var render=mapper.readTree(imageRequest.get());assertThat(render.path("images").size()).isEqualTo(1);
        assertThat(render.path("prompt").asText()).contains("There is no separate brand logo. Do not invent one.");
    }

    @Test void repeatedOperationReturnsSameFlowAndRatioChangesConflict() throws Exception {
        String key="repeat-"+UUID.randomUUID();var id=enqueue(key);assertThat(enqueue(key)).isEqualTo(id);
        mvc.perform(post("/api/v1/ads").header("Idempotency-Key",key).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio","1:1"))))
                .andExpect(status().isConflict());
        assertThat(imageCalls.get()).isZero();
    }

    @Test void missingInvalidRatiosAndMissingKeyCannotStartFlow() throws Exception {
        for(String ratio:List.of("","auto","2:7","wide"))mvc.perform(post("/api/v1/ads").header("Idempotency-Key","invalid-"+UUID.randomUUID())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio",ratio))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ads").header("Idempotency-Key","missing").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("productId",product)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ads").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio","1:1")))).andExpect(status().isBadRequest());
        verifyNoInteractions(evaluator);assertThat(promptCalls.get()).isZero();assertThat(imageCalls.get()).isZero();
    }

    @Test void otherAccountsCannotReadAdStatusOrGeneratedImage() throws Exception {
        var id=runAd();String stranger="ad-stranger-"+UUID.randomUUID();
        db.update("INSERT INTO users(id,google_id,email,onboarding_completed,created_at) VALUES(?,?,'stranger@example.com',TRUE,?)",UUID.randomUUID(),stranger,stamp(Instant.now()));
        mvc(stranger).perform(get("/api/v1/ads/"+id)).andExpect(status().isNotFound());
        UUID creation=db.queryForObject("SELECT id FROM creations WHERE job_id=?",UUID.class,selections.status(subject,id).generationJobId());
        mvc(stranger).perform(get("/api/v1/creations/"+creation+"/image")).andExpect(status().isNotFound());
    }

    @Test void invalidPromptOutputNeverCallsImageRenderer() throws Exception {
        promptReply.set(mapper.writeValueAsString(Map.of("stop_reason","max_tokens","content",List.of(Map.of("type","text","text","partial")))));
        var id=runAd();assertThat(selections.status(subject,id).generationStatus()).isEqualTo("PROMPT_FAILED");
        mvc.perform(get("/api/v1/ads/"+id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value("PROMPT_TRUNCATED"))
                .andExpect(jsonPath("$.error.stage").value("PROMPTING"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM concept_allocation_claims",Integer.class)).isZero();
        assertThat(mapper.readTree(promptRequest.get()).path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(imageCalls.get()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"low","medium","high"})
    void selectedQualityReachesOpenAiAndIsSavedWithTheCreation(String quality) throws Exception {
        var id=enqueue("quality-"+quality,quality);selectionWorker.run(id);
        var job=selections.status(subject,id).generationJobId();generation.run(job);
        assertThat(selections.status(subject,id).generationStatus()).isEqualTo("SUCCEEDED");
        assertThat(mapper.readTree(imageRequest.get()).path("quality").asText()).isEqualTo(quality);
        assertThat(store.get(id).context().quality()).isEqualTo(quality);
        assertThat(db.queryForObject("SELECT quality FROM creations WHERE job_id=?",String.class,job)).isEqualTo(quality);
        mvc.perform(get("/api/v1/ads/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.quality").value(quality));
        mvc.perform(get("/api/v1/products/"+product+"/creations")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quality").value(quality));
    }

    @Test void defaultHighQualityPreservesIdempotencyAndOtherChoicesConflict() throws Exception {
        String key="default-quality";UUID id=enqueue(key);
        assertThat(enqueue(key,"high")).isEqualTo(id);
        mvc.perform(get("/api/v1/ads/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.quality").value("high"));
        mvc.perform(post("/api/v1/ads").header("Idempotency-Key",key).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio","4:5","quality","medium"))))
                .andExpect(status().isConflict());
    }

    @Test void unsupportedQualityIsRejectedBeforeAnyPaidRequest() throws Exception {
        for(String quality:List.of("","HIGH","auto","ultra")) {
            mvc.perform(post("/api/v1/ads").header("Idempotency-Key","invalid-quality-"+UUID.randomUUID()).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio","4:5","quality",quality))))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(evaluator);assertThat(promptCalls.get()).isZero();assertThat(imageCalls.get()).isZero();
    }

    @Test void swaggerShowsRatioAndQualityChoicesAlongsideProductId() throws Exception {
        var docs=MockMvcBuilders.webAppContextSetup(webContext).build();
        String response=docs.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc=mapper.readTree(response);
        var schema=doc.at("/paths/~1api~1v1~1ads/post/requestBody/content/application~1json/schema");
        if(schema.has("$ref"))schema=doc.at(schema.path("$ref").asText().substring(1));
        assertThat(schema.path("properties").has("productId")).isTrue();
        assertThat(schema.at("/properties/quality/enum").toString()).isEqualTo("[\"low\",\"medium\",\"high\"]");
        assertThat(schema.at("/properties/quality/default").asText()).isEqualTo("high");
        assertThat(schema.at("/properties/aspectRatio/enum").size()).isEqualTo(5);
    }

    @Test void invalidImageOutputIsNotStoredAsCreationOrRetried() throws Exception {
        imageReply.set("{\"data\":[{\"b64_json\":\"not-valid-base64\"}]}");
        var id=runAd();var job=selections.status(subject,id).generationJobId();generation.run(job);
        assertThat(selections.status(subject,id).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creations WHERE job_id=?",Integer.class,job)).isZero();
        assertThat(imageCalls.get()).isEqualTo(1);
    }

    @Test void missingProviderKeyRejectsBeforeSelectionOrPaidCalls() throws Exception {
        var unavailable=mock(ImageGenerator.class);
        mvc(subject,unavailable).perform(post("/api/v1/ads").header("Idempotency-Key","no-key")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio","1:1"))))
                .andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM selection_requests",Integer.class)).isZero();
        verifyNoInteractions(evaluator);assertThat(promptCalls.get()).isZero();assertThat(imageCalls.get()).isZero();
    }

    @Test void missingImageUsageKeepsConservativeReservation() throws Exception {
        imageReply.set(mapper.writeValueAsString(Map.of("data",List.of(Map.of("b64_json",Base64.getEncoder().encodeToString(outputImage))))));
        var id=runAd();var job=selections.status(subject,id).generationJobId();
        assertThat(selections.status(subject,id).generationStatus()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT spent_micros FROM generation_jobs WHERE id=?",Long.class,job)).isEqualTo(1701000);
    }

    @Test void imageHttpFailureIsNotAutomaticallyRetriedOrPublished() throws Exception {
        imageStatus.set(502);imageReply.set("{\"error\":{\"message\":\"Provider unavailable\"}}");
        var id=runAd();var job=selections.status(subject,id).generationJobId();generation.run(job);
        assertThat(selections.status(subject,id).generationStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM creations WHERE job_id=?",Integer.class,job)).isZero();
        assertThat(imageCalls.get()).isEqualTo(1);
    }

    @Test void adEndpointsRemainAuthenticatedAndCsrfProtected() throws Exception {
        var secured=MockMvcBuilders.webAppContextSetup(webContext).addFilters(webContext.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
        secured.perform(get("/api/v1/ads/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
        secured.perform(post("/api/v1/ads").header("Idempotency-Key","csrf-check").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("productId",product,"aspectRatio","1:1")))).andExpect(status().isForbidden());
        assertThat(promptCalls.get()).isZero();assertThat(imageCalls.get()).isZero();
    }
}
