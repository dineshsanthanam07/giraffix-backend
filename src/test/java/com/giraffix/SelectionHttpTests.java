package com.giraffix;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import com.giraffix.concept.controller.ConceptReviewController;
import com.giraffix.concept.service.ConceptLibrary;
import com.giraffix.generation.CreationController;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SelectionHttpTests {
    MockMvc mvc;SelectionService service;ConceptLibrary library;
    UUID product=UUID.randomUUID();UUID id=UUID.randomUUID();
    @BeforeEach void setup(){
        service=mock(SelectionService.class);library=mock(ConceptLibrary.class);
        var user=mock(OidcUser.class);when(user.getSubject()).thenReturn("subject");
        mvc=MockMvcBuilders.standaloneSetup(new SelectionController(service,new JsonSupport(JsonMapper.builder().build()),Validation.buildDefaultValidatorFactory().getValidator()),
                new ConceptReviewController(library)).setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
            public boolean supportsParameter(MethodParameter p){return p.getParameterType()==OidcUser.class;}
            public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest r,WebDataBinderFactory f){return user;}
        }).build();
        when(service.start(anyString(),any(),any())).thenReturn(new Status(id,"QUEUED",0,0,0,null,null,null,Instant.now()));
    }
    String body(){return "{\"productId\":\""+product+"\",\"intent\":\"Launch\",\"format\":\"square\"}";}
    @Test void returnsAsyncLocationAndRequestId() throws Exception {
        mvc.perform(post("/api/v1/selection-requests").header("Idempotency-Key","test").contentType("application/json").content(body()))
                .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/v1/selection-requests/"+id)).andExpect(jsonPath("$.status").value("QUEUED"));
    }
    @Test void acceptsProductIdOnlyAndSuppliesDefaults() throws Exception {
        mvc.perform(post("/api/v1/selection-requests").header("Idempotency-Key","minimal").contentType("application/json")
                .content("{\"productId\":\""+product+"\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/v1/selection-requests/"+id));
        var input=org.mockito.ArgumentCaptor.forClass(Start.class);
        verify(service).start(eq("subject"),eq("minimal"),input.capture());
        assertThat(input.getValue().productId()).isEqualTo(product);
        assertThat(input.getValue().intent()).isEqualTo(Start.DEFAULT_INTENT);
        assertThat(input.getValue().format()).isEqualTo(Start.DEFAULT_FORMAT);
        assertThat(input.getValue().conceptId()).isNull();
    }
    @Test void acceptsOptionalOverridesAndDefaultsExplicitNulls() throws Exception {
        String payload="{\"productId\":\""+product+"\",\"intent\":null,\"format\":\"portrait\",\"controls\":\"No headline\"}";
        mvc.perform(post("/api/v1/selection-requests").header("Idempotency-Key","overrides").contentType("application/json").content(payload))
                .andExpect(status().isAccepted());
        verify(service).start("subject","overrides",new Start(product,Start.DEFAULT_INTENT,"portrait","No headline",null));
    }
    @Test void rejectsScoreOwnerAndApprovalOverrides() throws Exception {
        for(String field:List.of("score","ownerId","approved","s3Key")){
            mvc.perform(post("/api/v1/selection-requests").header("Idempotency-Key","test").contentType("application/json")
                    .content(body().replace("}",",\""+field+"\":\"override\"}"))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void rejectsMissingOrMalformedInput() throws Exception {
        for(String payload:List.of("{}","null","[]",body().replace("Launch"," "),body().replace(product.toString(),"invalid"))){
            mvc.perform(post("/api/v1/selection-requests").contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void pollingPreservesNotFoundIsolation() throws Exception {
        when(service.status("subject",id)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/v1/selection-requests/"+id)).andExpect(status().isNotFound());
    }
    @Test void conceptApprovalPreservesForbidden() throws Exception {
        when(library.review("subject",id,id,true)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(post("/api/v1/concepts/"+id+"/revisions/"+id+"/approve")).andExpect(status().isForbidden());
    }
}
