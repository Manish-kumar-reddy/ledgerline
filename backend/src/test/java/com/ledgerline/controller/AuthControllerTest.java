package com.ledgerline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.dto.TokenResponse;
import com.ledgerline.dto.UserResponse;
import com.ledgerline.entity.Role;
import com.ledgerline.exception.EmailAlreadyRegisteredException;
import com.ledgerline.exception.TooManyAttemptsException;
import java.time.Duration;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtTokenService;
import com.ledgerline.service.AuthService;
import com.ledgerline.support.WebSecurityTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Web layer with the real security configuration; AuthService and UserRepository are mocked (no database).
 * Database behaviour is covered by AuthFlowIT.
 */
@WebMvcTest(AuthController.class)
@WebSecurityTest
class AuthControllerTest {

    private static final String VALID_REGISTRATION =
            "{\"fullName\":\"Asha Rao\",\"email\":\"asha@example.com\",\"password\":\"correct horse battery\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenService tokens;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository users;

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void registerReturns201WithoutAnyPasswordField() throws Exception {
        UUID id = UUID.randomUUID();
        given(authService.register(any(), any())).willReturn(
                new UserResponse(id, "Asha Rao", "asha@example.com", Role.ACCOUNT_HOLDER, Instant.parse("2026-11-09T10:00:00Z")));

        postJson("/api/auth/register", VALID_REGISTRATION)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/auth/me"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "enabled", "id", "isAdmin"})
    void registerRejectsAnyExtraPropertyIncludingRole(String property) throws Exception {
        String json = VALID_REGISTRATION.replace("}", ",\"" + property + "\":\"ADMIN\"}");
        postJson("/api/auth/register", json).andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void registerReportsInvalidFieldsWithoutEchoingValues() throws Exception {
        String secretLookingPassword = "short1";
        String body = postJson("/api/auth/register",
                "{\"fullName\":\"\",\"email\":\"not-an-email\",\"password\":\"" + secretLookingPassword + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("email", "fullName", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(secretLookingPassword).doesNotContain("not-an-email");
        verifyNoInteractions(authService);
    }

    @Test
    void registrationOverTheLimitIs429WithRetryAfter() throws Exception {
        willThrow(new TooManyAttemptsException("Too many registration attempts", Duration.ofMinutes(42))).given(authService).register(any(), any());
        postJson("/api/auth/register", VALID_REGISTRATION)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2520"))
                .andExpect(jsonPath("$.title").value("Too many registration attempts"));
    }

    @Test
    void registerReturns409ForATakenEmail() throws Exception {
        given(authService.register(any(), any())).willThrow(new EmailAlreadyRegisteredException());
        postJson("/api/auth/register", VALID_REGISTRATION)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already registered"));
    }

    @Test
    void loginReturnsABearerToken() throws Exception {
        given(authService.login(any(), any())).willReturn(TokenResponse.bearer("token-value", 900));
        postJson("/api/auth/login", "{\"email\":\"asha@example.com\",\"password\":\"correct horse battery\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("token-value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void everyLoginFailureGetsTheSameResponse() throws Exception {
        String request = "{\"email\":\"asha@example.com\",\"password\":\"whatever-password\"}";

        // willThrow(..).given(mock) form: re-stubbing with given(mock.login(..)) would call the already-throwing stub.
        willThrow(new BadCredentialsException("Bad credentials")).given(authService).login(any(), any());
        String wrongPassword = postJson("/api/auth/login", request)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        willThrow(new DisabledException("User is disabled")).given(authService).login(any(), any());
        String disabled = postJson("/api/auth/login", request)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(disabled).contains("Invalid email or password").doesNotContain("disabled");
    }

    @Test
    void rateLimitedLoginIs429WithRetryAfterAndAGenericBody() throws Exception {
        willThrow(new TooManyAttemptsException("Too many login attempts", Duration.ofSeconds(299).plusMillis(400))).given(authService).login(any(), any());
        String body = postJson("/api/auth/login", "{\"email\":\"asha@example.com\",\"password\":\"whatever-password\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.title").value("Too many login attempts"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("asha").doesNotContain("email").doesNotContain("203.0.113");
    }

    @Test
    void loginPassesTheConnectionsRemoteAddressAsTheClientIp() throws Exception {
        given(authService.login(any(), any())).willReturn(TokenResponse.bearer("t", 900));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha@example.com\",\"password\":\"whatever-password\"}")
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; }))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(authService).login(any(), org.mockito.ArgumentMatchers.eq("203.0.113.9"));
    }

    @Test
    void loginRejectsUnknownProperties() throws Exception {
        postJson("/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"x\",\"role\":\"ADMIN\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void meWithoutATokenIs401WithBearerChallenge() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void meWithAMalformedTokenIs401InvalidToken() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void aValidTokenForAUserThatDoesNotExistIs401() throws Exception {
        given(users.findById(any())).willReturn(java.util.Optional.empty());
        String token = tokens.issue(UUID.randomUUID()).value();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
    }

    @Test
    void anUnknownProtectedPathIs401NotA404() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
    }
}
