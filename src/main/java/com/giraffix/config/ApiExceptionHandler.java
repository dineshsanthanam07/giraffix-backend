package com.giraffix.config;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** MVC errors omit request bodies, rejected values, SQL and upstream responses. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record Error(String code,String message,String requestId,Map<String,String> fields){}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Error> status(ResponseStatusException ex,HttpServletRequest request){
        int status=ex.getStatusCode().value();
        String code=switch(status){case 400->"INVALID_REQUEST";case 401->"AUTHENTICATION_REQUIRED";case 403->"ACCESS_DENIED";
            case 404->"NOT_FOUND";case 409->"CONFLICT";case 413->"UPLOAD_TOO_LARGE";case 502->"UPSTREAM_FAILURE";
            case 503->"SERVICE_UNAVAILABLE";default->"REQUEST_FAILED";};
        return response(status,code,status>=500?"The operation could not be completed. Use the request ID to trace the failure.":Objects.toString(ex.getReason(),"Request rejected"),Map.of(),request,ex);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Error> validation(MethodArgumentNotValidException ex,HttpServletRequest request){
        Map<String,String> fields=new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error->fields.putIfAbsent(error.getField(),Objects.toString(error.getDefaultMessage(),"Invalid value")));
        return response(400,"VALIDATION_FAILED","Check the request fields",fields,request,ex);
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> malformed(Exception ex,HttpServletRequest request){return response(400,"INVALID_REQUEST","Invalid request body, parameter, header or multipart part",Map.of(),request,ex);}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Error> oversized(Exception ex,HttpServletRequest request){return response(413,"UPLOAD_TOO_LARGE","Upload exceeds the allowed size",Map.of(),request,ex);}
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Error> conflict(Exception ex,HttpServletRequest request){return response(409,"CONFLICT","Request conflicts with stored data",Map.of(),request,ex);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> unexpected(Exception ex,HttpServletRequest request){
        if(ex instanceof org.springframework.web.ErrorResponse error)return response(error.getStatusCode().value(),"REQUEST_FAILED","Request could not be handled",Map.of(),request,ex);
        return response(500,"INTERNAL_ERROR","An unexpected error occurred. Use the request ID to trace it.",Map.of(),request,ex);
    }
    private ResponseEntity<Error> response(int status,String code,String message,Map<String,String> fields,HttpServletRequest request,Exception ex){
        String id=Objects.toString(request.getAttribute("giraffix.requestId"),Objects.toString(MDC.get("requestId"),"unavailable"));
        var event=status>=500?log.atError():log.atWarn();
        event.log("stage=api_error status={} code={} requestId={} errorType={} location={}",status,code,id,ex.getClass().getSimpleName(),
                ex.getStackTrace().length==0?"unknown":ex.getStackTrace()[0]);
        if(status>=500)log.debug("stage=api_error_stack requestId={} frames={}",id,Arrays.toString(ex.getStackTrace()));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(new Error(code,message,id,fields));
    }
}
