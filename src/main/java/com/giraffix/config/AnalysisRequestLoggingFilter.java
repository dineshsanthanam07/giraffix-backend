package com.giraffix.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AnalysisRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !("POST".equals(request.getMethod()) && ("/api/v1/analyze".equals(path)
                || "/api/v1/concepts".equals(path)
                || path.matches("/api/v1/brands/[^/]+/products")
                || path.matches("/api/v1/products/[^/]+/images"))
                || "DELETE".equals(request.getMethod()) && path.matches("/api/v1/products/[^/]+"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        String analysisId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", analysisId);
        long started = System.nanoTime();
        boolean failed = false;
        try (var ignored = MDC.putCloseable("analysisId", analysisId)) {
            log.info("analysisId={} stage=http_request_received method={} path={} contentLength={}",
                    analysisId, request.getMethod(), request.getServletPath(), request.getContentLengthLong());
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException ex) {
                failed = true;
                log.error("analysisId={} stage=http_request_error errorType={}", analysisId, ex.getClass().getSimpleName());
                throw ex;
            } finally {
                log.info("analysisId={} stage=http_request_completed status={} durationMs={}", analysisId,
                        failed ? 500 : response.getStatus(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }
}
