package com.ledgerline.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** 403: signed in, but the current role is not allowed to do this. */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;

    public ProblemAccessDeniedHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        ProblemResponses.write(response, json, request, HttpServletResponse.SC_FORBIDDEN,
                "Forbidden", "You do not have permission to perform this action.");
    }
}
