package com.giraffix;

import com.giraffix.config.AnalysisRequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AnalysisRequestLoggingFilterTests {
    @Test
    void correlatesRejectedRequestsAndCleansThreadContext(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/analyze");
        request.setServletPath("/api/v1/analyze");
        var response = new MockHttpServletResponse();
        new AnalysisRequestLoggingFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("analysisId")).isEqualTo(response.getHeader("X-Request-ID"));
            response.setStatus(403);
        });
        assertThat(response.getHeader("X-Request-ID")).isNotBlank();
        assertThat(output.getOut()).contains("analysisId=" + response.getHeader("X-Request-ID"),
                "stage=http_request_received", "stage=http_request_completed status=403");
        assertThat(MDC.get("analysisId")).isNull();
    }
}
