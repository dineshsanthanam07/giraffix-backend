package com.giraffix;

import com.giraffix.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorLoggingTests {
    MockMvc mvc;
    @RestController static class BrokenApi {
        @GetMapping("/api/v1/test/error") public void error(){throw new IllegalStateException("PRIVATE_PROVIDER_TOKEN_DO_NOT_LOG");}
        @GetMapping("/api/v1/test/ok") public String ok(){return "ok";}
    }
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new BrokenApi()).setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new AnalysisRequestLoggingFilter()).build();}
    @Test void errorsCarryRequestIdAndDoNotExposeExceptionPayload(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/test/error").header("X-Request-ID","support-trace-123"))
                .andExpect(status().isInternalServerError()).andExpect(header().string("X-Request-ID","support-trace-123"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.requestId").value("support-trace-123"));
        assertThat(output.getAll()).contains("stage=api_error","status=500","errorType=IllegalStateException","support-trace-123")
                .doesNotContain("PRIVATE_PROVIDER_TOKEN_DO_NOT_LOG");
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }
    @Test void successfulReadsAreCorrelatedWithoutInfoLogNoise(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/test/ok").header("X-Request-ID","read-123")).andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID","read-123"));
        assertThat(output.getAll()).doesNotContain("stage=http_request_received","stage=http_request_completed");
        assertThat(org.slf4j.MDC.get("analysisId")).isNull();
    }
    @Test void unsafeCorrelationHeaderIsReplaced() throws Exception {
        var response=mvc.perform(get("/api/v1/test/ok").header("X-Request-ID","unsafe header value"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("X-Request-ID")).matches("[a-f0-9-]{36}");
    }
}
