package com.giraffix.config;


import com.giraffix.user.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class GoogleLoginSuccessHandler
        implements AuthenticationSuccessHandler {

    private final UserService userService;

    public GoogleLoginSuccessHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException, ServletException {

        OidcUser googleUser =
                (OidcUser) authentication.getPrincipal();

        userService.saveOrUpdateGoogleUser(googleUser);

        // For initial backend testing:
        response.sendRedirect(
                request.getContextPath() + "/api/v1/users/me");
    }
}
