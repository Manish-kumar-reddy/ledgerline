package com.ledgerline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.entity.Role;
import com.ledgerline.entity.User;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtTokenService;
import com.ledgerline.service.AccountOversightService;
import com.ledgerline.service.AccountService;
import com.ledgerline.support.WebSecurityTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Account endpoints' web layer with the real security configuration; services and UserRepository are mocked.
 * End-to-end behaviour against PostgreSQL is in AccountAuthorizationIT.
 */
@WebMvcTest({AccountController.class, OversightAccountController.class})
@WebSecurityTest
class AccountControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenService tokens;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private AccountOversightService oversightService;

    @MockitoBean
    private UserRepository users;

    private String tokenFor(Role role) {
        UUID id = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getId()).willReturn(id);
        given(user.getRole()).willReturn(role);
        given(user.isEnabled()).willReturn(true);
        given(user.getCreatedAt()).willReturn(Instant.now());
        given(users.findById(id)).willReturn(Optional.of(user));
        return tokens.issue(id).value();
    }

    private ResultActions create(String token, String json) throws Exception {
        return mvc.perform(post("/api/accounts").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void anAccessDeniedFromMethodSecurityIs403Not401() throws Exception {
        // What a @PreAuthorize refusal throws. It must reach Spring Security's 403 handler, not the login 401 handler.
        given(accountService.listOwn(anyInt(), anyInt()))
                .willThrow(new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false)));
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + tokenFor(Role.ACCOUNT_HOLDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"A\",\"ownerId\":\"6f1c2e1e-0000-4000-8000-000000000000\"}",
            "{\"name\":\"A\",\"role\":\"ADMIN\"}",
            "{\"name\":\"A\",\"status\":\"FROZEN\"}",
            "{\"name\":\"A\",\"currency\":\"USD\"}",
            "{\"name\":\"A\",\"id\":\"6f1c2e1e-0000-4000-8000-000000000000\"}"})
    void anyFieldOtherThanNameIsRejectedBeforeTheService(String json) throws Exception {
        create(tokenFor(Role.ACCOUNT_HOLDER), json).andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":\"tab\\there\"}", "{}"})
    void invalidNamesAre400(String json) throws Exception {
        create(tokenFor(Role.ACCOUNT_HOLDER), json).andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @Test
    void aNameOver100CharactersIs400() throws Exception {
        create(tokenFor(Role.ACCOUNT_HOLDER), "{\"name\":\"" + "n".repeat(101) + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void aMalformedIdIs400AndIsNotEchoed() throws Exception {
        // "instance" is the requested path (URL-encoded), as in every problem response; detail and title must not
        // repeat the rejected value.
        mvc.perform(get("/api/accounts/not-a-uuid-<b>").header("Authorization", "Bearer " + tokenFor(Role.ACCOUNT_HOLDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Parameter 'id' has an invalid format."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "page=-1", "page=10001", "page=abc", "size=1e9"})
    void pagingIsValidatedOnBothLists(String query) throws Exception {
        for (String path : new String[] {"/api/accounts?", "/api/oversight/accounts?"}) {
            Role role = path.contains("oversight") ? Role.AUDITOR : Role.ACCOUNT_HOLDER;
            String body = mvc.perform(get(path + query).header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("abc").doesNotContain("1e9");
        }
        verifyNoInteractions(accountService, oversightService);
    }

    @Test
    void theLargestAllowedPageIsAccepted() throws Exception {
        given(oversightService.listAll(any(Integer.class), any(Integer.class)))
                .willReturn(new com.ledgerline.dto.PageResponse<>(java.util.List.of(), 10_000, 100, 0, 0));
        mvc.perform(get("/api/oversight/accounts?page=10000&size=100").header("Authorization", "Bearer " + tokenFor(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void corsPreflightStillWorksUnderDefaultDeny() throws Exception {
        // Preflight requests carry no token; the CORS filter answers them before authorization runs.
        mvc.perform(options("/api/accounts").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/accounts").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
