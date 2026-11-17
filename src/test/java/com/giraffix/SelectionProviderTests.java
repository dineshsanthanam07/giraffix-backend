package com.giraffix;

import com.giraffix.config.ClaudeProperties;
import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectionProviderTests {
    HttpServer server;JsonSupport json=new JsonSupport(JsonMapper.builder().build());
    AtomicReference<String> response=new AtomicReference<>();AtomicReference<String> request=new AtomicReference<>();
    ClaudeSelectionEvaluator adapter;SelectionPolicy policy=new SelectionPolicy();Candidate candidate;
    SelectionAssets mockedAssets;ClaudeProperties config;
    Context context;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/messages",exchange->{request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        mockedAssets=mock(SelectionAssets.class);when(mockedAssets.read(any())).thenReturn(ProductImageProcessorTests.fixture("png"));
        config=new ClaudeProperties("test-only","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","model","2023-06-01",6000,5242880);
        adapter=new ClaudeSelectionEvaluator(RestClient.builder(),config,json,mockedAssets);
        policy.pickerEnabled=false;
        var asset=new Asset("test","key","image/png","hash");
        candidate=new Candidate(UUID.randomUUID(),UUID.randomUUID(),1,ConceptFixtures.VALID,asset);
        context=new Context(UUID.randomUUID(),UUID.randomUUID(),"{\"name\":\"Bottle\"}","Launch","square",null,List.of(asset));
    }
    @AfterEach void stop(){server.stop(0);}
    String envelope(String text,String stop){return json.write(Map.of("id","provider-id","stop_reason",stop,"content",List.of(Map.of("type","text","text",text)),"usage",Map.of("input_tokens",100,"output_tokens",50)));}
    Evaluation value(){return new Evaluation(candidate.conceptId(),1,"Bottle floats",new Ratings(4,4,4,4,4),List.of(),List.of(),
            ScorePolicy.DIMENSIONS.stream().map(d->new Reason(d,"SUPPORTED",List.of("/intent"))).toList(),false);}
    @Test void sendsStructuredSchemaAndImagesAndPreservesUsage(){
        response.set(envelope(json.write(Map.of("candidates",List.of(value()))),"end_turn"));
        var result=adapter.evaluate(context,List.of(candidate),null,policy.snapshot());
        assertThat(result.candidates()).containsExactly(value());assertThat(result.usage().providerId()).isEqualTo("provider-id");
        var sent=json.mapper.readTree(request.get());assertThat(sent.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(sent.at("/messages/0/content").size()).isEqualTo(2);
    }
    @Test void verificationIncludesReferenceAndProductAndHidesScreenRatings(){
        response.set(envelope(json.write(Map.of("candidates",List.of(value()))),"end_turn"));
        adapter.evaluate(context,List.of(candidate),value(),policy.snapshot());
        var content=json.mapper.readTree(request.get()).at("/messages/0/content");assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(2).path("text").asText()).contains("Bottle floats").doesNotContain("\"conceptFidelity\":4");
    }
    @Test void rejectsTruncatedAndExtraFields(){
        response.set(envelope(json.write(Map.of("candidates",List.of(value()))),"max_tokens"));
        assertThatThrownBy(()->adapter.evaluate(context,List.of(candidate),null,policy.snapshot())).isInstanceOf(IllegalArgumentException.class);
        var invalid=json.mapper.valueToTree(value());((tools.jackson.databind.node.ObjectNode)invalid).put("extra","not allowed");
        response.set(envelope(json.write(Map.of("candidates",List.of(invalid))),"end_turn"));
        assertThatThrownBy(()->adapter.evaluate(context,List.of(candidate),null,policy.snapshot())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oversizedEnvelopeStopsBeforeProviderCall(){
        policy.maxInputTokens=10;
        assertThatThrownBy(()->adapter.evaluate(context,List.of(candidate),null,policy.snapshot())).isInstanceOf(ClaudeSelectionEvaluator.EnvelopeExceeded.class);
        assertThat(request.get()).isNull();
    }
    @Test void actualOutputReviewUsesSavedPolicyAndChecksAllImageRoles(){
        var reviewer=new com.giraffix.generation.ClaudeOutputReviewer(RestClient.builder(),config,mockedAssets,json);
        response.set(envelope("{\"accepted\":true,\"reason\":\"Identity and relationship preserved\"}","end_turn"));
        var brief=new Brief(context,candidate,value(),policy.snapshot(),new GenerationLimits(2000000,300000,600));
        var result=reviewer.review(brief,candidate.reference());
        assertThat(result.accepted()).isTrue();assertThat(result.costMicros()).isEqualTo(700);
        var body=json.mapper.readTree(request.get());assertThat(body.path("model").asText()).isEqualTo(policy.model);
        assertThat(body.at("/messages/0/content").size()).isEqualTo(4);
        response.set(envelope("{\"accepted\":true,\"reason\":\"Pass\",\"extra\":true}","end_turn"));
        assertThatThrownBy(()->reviewer.review(brief,candidate.reference())).isInstanceOf(IllegalArgumentException.class);
    }

    String pickerBody(int score,String classification,List<UUID> excluded) {
        return json.write(Map.of("evaluations",List.of(Map.of("conceptId",candidate.conceptId(),"compatibilityScore",score,
                "classification",classification,"adaptationDirection","Suspend the recognizable bottle within a frame tied to its use.")),
                "notSuitableConceptIds",excluded));
    }
    @Test void pickerSendsVersionedPromptActualPhotosProductBrandAndExactOutputSchema() throws Exception {
        policy.pickerEnabled=true;
        context=new Context(context.ownerId(),context.productId(),"{\"name\":\"Bottle\",\"category\":\"Drinkware\",\"description\":\"Visible product details\",\"brandName\":\"Example brand\",\"primaryColor\":\"#123456\"}",
                "Launch","square",null,List.of(candidate.reference(),candidate.reference()));
        response.set(envelope(pickerBody(85,"STRONG",List.of()),"end_turn"));
        var result=adapter.evaluate(context,List.of(candidate),null,policy.snapshot());
        assertThat(result.candidates().get(0).compatibilityScore()).isEqualTo(85);
        assertThat(result.candidates().get(0).classification()).isEqualTo("STRONG");
        assertThat(result.notSuitableConceptIds()).isEmpty();
        var body=json.mapper.readTree(request.get());
        assertThat(body.path("system").asText()).contains("Giraffix", "Configured minimum compatibility score: 80")
                .doesNotContain("{{MINIMUM_COMPATIBILITY_SCORE}}");
        var content=body.at("/messages/0/content");assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(0).path("source").path("data").asText()).isEqualTo(Base64.getEncoder().encodeToString(ProductImageProcessorTests.fixture("png")));
        var data=json.mapper.readTree(content.get(2).path("text").asText());
        assertThat(data.at("/product/description").asText()).isEqualTo("Visible product details");
        assertThat(data.at("/brand/name").asText()).isEqualTo("Example brand");
        assertThat(data.at("/brand/primaryColor").asText()).isEqualTo("#123456");
        assertThat(data.at("/concepts/0/conceptId").asText()).isEqualTo(candidate.conceptId().toString());
        assertThat(data.at("/concepts/0/preserve").isArray()).isTrue();
        var properties=body.at("/output_config/format/schema/properties");
        assertThat(properties.propertyNames()).containsExactlyInAnyOrder("evaluations","notSuitableConceptIds");
    }
    @Test void pickerUsesConfiguredThresholdAndKeepsClassificationSeparateFromExclusion() {
        policy.pickerEnabled=true;policy.threshold=90;
        response.set(envelope(pickerBody(85,"CONDITIONAL",List.of(candidate.conceptId())),"end_turn"));
        var result=adapter.evaluate(context,List.of(candidate),null,policy.snapshot());
        assertThat(result.notSuitableConceptIds()).containsExactly(candidate.conceptId());
        assertThat(json.mapper.readTree(request.get()).path("system").asText()).contains("Configured minimum compatibility score: 90");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"extra","missing","decimal","outOfRange","classification","unknownId","duplicate","missingExclusion","extraExclusion","duplicateExclusion","blankAdaptation"})
    void pickerRejectsMalformedOrContradictoryResults(String mutation) {
        policy.pickerEnabled=true;
        var body=(tools.jackson.databind.node.ObjectNode)json.mapper.readTree(pickerBody(70,"CONDITIONAL",List.of(candidate.conceptId())));
        var evaluation=(tools.jackson.databind.node.ObjectNode)body.path("evaluations").get(0);
        switch(mutation) {
            case "extra" -> evaluation.put("reason","Unexpected");
            case "missing" -> evaluation.remove("adaptationDirection");
            case "decimal" -> evaluation.put("compatibilityScore",70.5);
            case "outOfRange" -> evaluation.put("compatibilityScore",101);
            case "classification" -> evaluation.put("classification","GOOD");
            case "unknownId" -> evaluation.put("conceptId",UUID.randomUUID().toString());
            case "duplicate" -> ((tools.jackson.databind.node.ArrayNode)body.path("evaluations")).add(evaluation.deepCopy());
            case "missingExclusion" -> body.putArray("notSuitableConceptIds");
            case "extraExclusion" -> evaluation.put("compatibilityScore",80);
            case "duplicateExclusion" -> ((tools.jackson.databind.node.ArrayNode)body.path("notSuitableConceptIds")).add(candidate.conceptId().toString());
            case "blankAdaptation" -> evaluation.put("adaptationDirection"," ");
        }
        response.set(envelope(json.write(body),"end_turn"));
        assertThatThrownBy(()->adapter.evaluate(context,List.of(candidate),null,policy.snapshot())).isInstanceOf(RuntimeException.class);
    }
    @Test void pickerRejectsTruncationAndOversizedInputBeforeDispatch() {
        policy.pickerEnabled=true;
        response.set(envelope(pickerBody(85,"STRONG",List.of()),"max_tokens"));
        assertThatThrownBy(()->adapter.evaluate(context,List.of(candidate),null,policy.snapshot())).isInstanceOf(IllegalArgumentException.class);
        request.set(null);policy.maxInputTokens=10;
        assertThatThrownBy(()->adapter.evaluate(context,List.of(candidate),null,policy.snapshot())).isInstanceOf(ClaudeSelectionEvaluator.EnvelopeExceeded.class);
        assertThat(request.get()).isNull();
    }
}
