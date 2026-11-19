package com.giraffix.generation;

import com.giraffix.config.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdProgressStreamTests {
    final UUID operation=UUID.randomUUID();
    final AtomicLong nanos=new AtomicLong();
    AdStatusService statuses;
    AdProgressStream streams;
    MockMvc mvc;
    MockHttpSession session;

    @BeforeEach void setup(){
        statuses=mock(AdStatusService.class);
        when(statuses.get(anyString(),any())).thenReturn(snapshot("QUEUED"));
        streams=new AdProgressStream(statuses,1000,1000,10000,4,2,true,nanos::get);
        session=session("owner");
        var user=mock(OidcUser.class);when(user.getSubject()).thenReturn("owner");
        mvc=MockMvcBuilders.standaloneSetup(new AdProgressController(streams))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter){return parameter.getParameterType()==OidcUser.class;}
                    public Object resolveArgument(org.springframework.core.MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer m,
                            org.springframework.web.context.request.NativeWebRequest r,org.springframework.web.bind.support.WebDataBinderFactory f){return user;}
                }).build();
    }
    @AfterEach void close(){streams.close();}

    AdController.Status snapshot(String state){
        boolean success=state.equals("SUCCEEDED");
        return new AdController.Status(operation,state,"SELECTED",UUID.nameUUIDFromBytes("job".getBytes()),
                success?operation:null,success?"/api/v1/creations/"+operation+"/image":null,"4:5","high",false,null);
    }
    static MockHttpSession session(String subject){
        var principal=mock(OidcUser.class);when(principal.getSubject()).thenReturn(subject);
        var auth=new OAuth2AuthenticationToken(principal,List.of(new SimpleGrantedAuthority("ROLE_USER")),"google");
        var session=new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new SecurityContextImpl(auth));
        return session;
    }
    MvcResult open() throws Exception {
        return mvc.perform(get("/api/v1/ads/"+operation+"/events").session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering","no"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn();
    }
    String body(MvcResult result){try{return result.getResponse().getContentAsString();}catch(Exception e){throw new AssertionError(e);}}
    void tickUntil(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline){streams.scan();Thread.sleep(10);}
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test void streamsRealStagesAndFinalImageThenReleasesConnection() throws Exception {
        var response=open();
        assertThat(body(response)).contains("event:progress","\"stage\":\"QUEUED\"","\"terminal\":false");
        for(String state:List.of("SCREENING","PROMPTING","GENERATING","REVIEWING","SUCCEEDED")){
            when(statuses.get("owner",operation)).thenReturn(snapshot(state));
            tickUntil(()->body(response).contains("\"stage\":\""+state+"\""));
        }
        response.getAsyncResult(5000);
        var completed=mvc.perform(asyncDispatch(response)).andExpect(status().isOk()).andReturn();
        assertThat(body(completed)).contains("Generating your ad image","Your ad is ready","\"terminal\":true","/api/v1/creations/")
                .doesNotContain("imagePrompt","factsJson","api-key");
        assertThat(streams.activeConnections()).isZero();
    }

    @Test void unchangedStateSendsOnlyHeartbeatAndTabsShareOneSnapshotRead() throws Exception {
        var first=open();var second=open();
        clearInvocations(statuses);
        var read=new CountDownLatch(1);
        when(statuses.get("owner",operation)).thenAnswer(call->{read.countDown();return snapshot("QUEUED");});
        streams.scan();assertThat(read.await(5,TimeUnit.SECONDS)).isTrue();
        verify(statuses,times(1)).get("owner",operation);
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500));
        tickUntil(()->body(first).contains(":keep-alive") && body(second).contains(":keep-alive"));
        assertThat(body(first).split("event:progress",-1)).hasSize(2);
        assertThat(body(second).split("event:progress",-1)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings={"SUCCEEDED","FAILED","PROMPT_FAILED","OUTPUT_REJECTED","STALE_CONTEXT","RECONCILIATION_REQUIRED",
            "DELETED","POOL_EXHAUSTED","NO_MATCH_WITHIN_BUDGET","PROVIDER_FAILED","SELECTION_BUSY","TEMPORARILY_SUPPRESSED"})
    void terminalSnapshotsAreDeliveredImmediatelyAndClosed(String state) throws Exception {
        when(statuses.get("owner",operation)).thenReturn(snapshot(state));
        var response=open();response.getAsyncResult(5000);
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk());
        assertThat(body(response)).contains("\"stage\":\""+state+"\"","\"terminal\":true");
        assertThat(streams.activeConnections()).isZero();
    }

    @Test void foreignUnknownOperationsFailBeforeStreamingEvenWithEventStreamAccept() throws Exception {
        when(statuses.get("owner",operation)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Ad not found"));
        mvc.perform(get("/api/v1/ads/"+operation+"/events").session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound()).andExpect(request().asyncNotStarted()).andExpect(content().contentType(MediaType.APPLICATION_JSON));
        assertThat(streams.activeConnections()).isZero();
    }

    @Test void invalidSessionIsRejectedWithoutReadingAdState() throws Exception {
        for(MockHttpSession invalid:List.of(new MockHttpSession(),session("another-owner")))
            mvc.perform(get("/api/v1/ads/"+operation+"/events").session(invalid).accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isUnauthorized()).andExpect(request().asyncNotStarted());
        verifyNoInteractions(statuses);
    }

    @Test void logoutStopsUpdatesBeforeAnotherSnapshotRead() throws Exception {
        var response=open();
        session.invalidate();
        when(statuses.get("owner",operation)).thenReturn(snapshot("SUCCEEDED"));
        tickUntil(()->streams.activeConnections()==0);
        response.getAsyncResult(5000);
        assertThat(body(response)).contains("event:stream-error","AUTHENTICATION_REQUIRED","\"retryable\":false")
                .doesNotContain("Your ad is ready");
        verify(statuses,times(1)).get("owner",operation);
    }

    @Test void lifetimeClosesStreamAndReconnectGetsCurrentSnapshot() throws Exception {
        var old=open();nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(10001));
        tickUntil(()->streams.activeConnections()==0);old.getAsyncResult(5000);
        when(statuses.get("owner",operation)).thenReturn(snapshot("SUCCEEDED"));
        var fresh=open();fresh.getAsyncResult(5000);
        assertThat(body(fresh)).contains("Your ad is ready","\"terminal\":true").doesNotContain("Your ad is queued");
    }

    @Test void readOutageClosesWithRetryableTransportErrorWithoutInventingAdFailure() throws Exception {
        var response=open();
        when(statuses.get("owner",operation)).thenThrow(new IllegalStateException("PRIVATE_DB_DETAILS"));
        tickUntil(()->streams.activeConnections()==0);response.getAsyncResult(5000);
        assertThat(body(response)).contains("PROGRESS_UNAVAILABLE","\"retryable\":true")
                .doesNotContain("PRIVATE_DB_DETAILS","\"stage\":\"FAILED\"");
    }

    @Test void connectionLimitsAreBoundedAndShutdownReleasesCapacity(){
        streams.subscribe("owner",operation,session);
        streams.subscribe("owner",operation,session);
        assertThatThrownBy(()->streams.subscribe("owner",operation,session))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
        streams.subscribe("second",UUID.randomUUID(),session("second"));
        streams.subscribe("third",UUID.randomUUID(),session("third"));
        assertThatThrownBy(()->streams.subscribe("fourth",UUID.randomUUID(),session("fourth")))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
        assertThat(streams.activeConnections()).isEqualTo(4);
        streams.close();assertThat(streams.activeConnections()).isZero();
    }
}
