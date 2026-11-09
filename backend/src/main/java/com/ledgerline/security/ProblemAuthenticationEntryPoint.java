package com.ledgerline.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 for a missing or unacceptable access token.
 *
 * Unlike Spring's default bearer entry point, the WWW-Authenticate header carries no error_description, so a
 * caller cannot tell an expired token from a forged one or from a disabled user.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper json;

    public ProblemAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        boolean tokenWasPresented = ex instanceof OAuth2AuthenticationException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                tokenWasPresented ? "Bearer error=\"invalid_token\"" : "Bearer");
        ProblemResponses.write(response, json, request, HttpServletResponse.SC_UNAUTHORIZED,
                "Authentication required", "A valid access token is required.");
    }
}
