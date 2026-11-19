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
        String path=path(request);
        return !(path.startsWith("/api/v1/") || path.equals("/admin/prompts") || path.equals("/logout"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        String supplied=request.getHeader("X-Request-ID");
        String analysisId=supplied!=null && supplied.matches("[A-Za-z0-9._:-]{1,64}")?supplied:UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", analysisId);
        request.setAttribute("giraffix.requestId",analysisId);
        long started = System.nanoTime();
        boolean failed = false;
        boolean read="GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        try (var ignored=MDC.putCloseable("analysisId",analysisId);var correlated=MDC.putCloseable("requestId",analysisId)) {
            var startedLog=read?log.atDebug():log.atInfo();
            startedLog.log("analysisId={} stage=http_request_received method={} path={} contentLength={}",
                    analysisId,request.getMethod(),clean(path(request)),request.getContentLengthLong());
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException ex) {
                failed = true;
                log.error("analysisId={} stage=http_request_error errorType={} location={}",analysisId,ex.getClass().getSimpleName(),
                        ex.getStackTrace().length==0?"unknown":ex.getStackTrace()[0]);
                log.debug("analysisId={} stage=http_error_stack frames={}",analysisId,java.util.Arrays.toString(ex.getStackTrace()));
                throw ex;
            } finally {
                int status=failed?500:response.getStatus();
                var completion=status>=500?log.atError():status>=400?log.atWarn():read?log.atDebug():log.atInfo();
                Object matched=request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                completion.log("analysisId={} stage=http_request_completed status={} durationMs={} method={} route={}",analysisId,
                        status,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),request.getMethod(),clean(matched==null?path(request):matched.toString()));
            }
        }
    }
    private String clean(String value){return value==null?"":value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]","_").substring(0,Math.min(value.length(),300));}
    private String path(HttpServletRequest request){
        String path=request.getServletPath();
        return path==null || path.isEmpty()?request.getRequestURI().substring(request.getContextPath().length()):path;
    }
}
