package com.giraffix.config;


import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CsrfController {

    @GetMapping("/api/v1/security/csrf")
    public Map<String, String> getCsrfToken(
            CsrfToken csrfToken) {

        return Map.of(
                "headerName", csrfToken.getHeaderName(),
                "token", csrfToken.getToken()
        );
    }
}
