package com.ledgerline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.entity.Role;
import com.ledgerline.entity.User;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.LedgerBusyException;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtTokenService;
import com.ledgerline.service.LedgerService;
import com.ledgerline.service.PostingResult;
import com.ledgerline.service.StatementFilter;
import com.ledgerline.service.StatementService;
import com.ledgerline.support.WebSecurityTest;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Money endpoints' web layer with the real security configuration; services mocked (no database). */
@WebMvcTest(LedgerController.class)
@WebSecurityTest
class LedgerControllerTest {

    private static final UUID ACCOUNT = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenService tokens;

    @MockitoBean
    private LedgerService ledger;

    @MockitoBean
    private StatementService statements;

    @MockitoBean
    private UserRepository users;

    private String holderToken() {
        UUID id = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getId()).willReturn(id);
        given(user.getRole()).willReturn(Role.ACCOUNT_HOLDER);
        given(user.isEnabled()).willReturn(true);
        given(user.getCreatedAt()).willReturn(Instant.now());
        given(users.findById(id)).willReturn(Optional.of(user));
        return tokens.issue(id).value();
    }

    private ResultActions deposit(String key, String json) throws Exception {
        var request = post("/api/accounts/" + ACCOUNT + "/deposits").header("Authorization", "Bearer " + holderToken())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static PostingResult result(String outcome, boolean replayed) {
        return new PostingResult(outcome, replayed, "DEPOSIT", "POSTED".equals(outcome) ? UUID.randomUUID() : null,
                "POSTED".equals(outcome) ? null : UUID.randomUUID(), "POSTED".equals(outcome) ? null : "BALANCE_LIMIT_EXCEEDED",
                ACCOUNT, UUID.randomUUID(), 10050, 10050, 1, Instant.parse("2026-11-10T10:00:00Z"), UUID.randomUUID());
    }

    @Test
    void anExactDecimalStringBecomesPaise() throws Exception {
        given(ledger.deposit(any(), anyLong(), any(), anyString())).willReturn(result("POSTED", false));
        deposit("key-1", "{\"amount\":\"100.50\",\"description\":\"  salary  \"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.outcome").value("POSTED"))
                .andExpect(jsonPath("$.amount").value("100.50"))
                .andExpect(jsonPath("$.amountMinor").value(10050));
        verify(ledger).deposit(eq(ACCOUNT), eq(10050L), eq("salary"), eq("key-1"));
    }

    @Test
    void aRefusalIs422AndAReplayIsMarked() throws Exception {
        given(ledger.deposit(any(), anyLong(), any(), anyString())).willReturn(result("REFUSED", true));
        deposit("key-2", "{\"amount\":\"100.50\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.reason").value("BALANCE_LIMIT_EXCEEDED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\":10.5}", "{\"amount\":10}", "{\"amount\":\"0\"}", "{\"amount\":\"0.00\"}",
            "{\"amount\":\"-1\"}", "{\"amount\":\"1.001\"}", "{\"amount\":\"1e3\"}", "{\"amount\":\"1,000\"}",
            "{\"amount\":\"1000000000\"}", "{\"amount\":null}", "{}", "{\"amount\":\"1\",\"initiatorId\":\"x\"}",
            "{\"amount\":\"1\",\"accountId\":\"x\"}", "{\"amount\":\"1\",\"balance\":\"5\"}",
            "{\"amount\":\"1\",\"description\":\"a\\nb\"}"})
    void invalidBodiesAre400BeforeTheService(String json) throws Exception {
        deposit("key-3", json).andExpect(status().isBadRequest());
        verifyNoInteractions(ledger);
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "tab\there", ""})
    void anInvalidIdempotencyKeyIs400(String key) throws Exception {
        deposit(key, "{\"amount\":\"1\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(ledger);
    }

    @Test
    void aMissingOrOverlongIdempotencyKeyIs400() throws Exception {
        deposit(null, "{\"amount\":\"1\"}").andExpect(status().isBadRequest());
        deposit("k".repeat(101), "{\"amount\":\"1\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(ledger);
    }

    @Test
    void conflictBusyAndDatabaseErrorsAreMappedWithoutLeakingSql() throws Exception {
        // willThrow(..).given(mock): re-stubbing with given(mock.call()) would invoke the previous stub.
        willThrow(new IdempotencyConflictException()).given(ledger).deposit(any(), anyLong(), any(), anyString());
        deposit("k", "{\"amount\":\"1\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));

        willThrow(new LedgerBusyException()).given(ledger).deposit(any(), anyLong(), any(), anyString());
        deposit("k", "{\"amount\":\"1\"}").andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));

        willThrow(new DataIntegrityViolationException(
                "ERROR: new row for relation \"accounts\" violates check constraint \"ck_accounts_balance_range\""))
                .given(ledger).deposit(any(), anyLong(), any(), anyString());
        String body = deposit("k", "{\"amount\":\"1\"}").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andReturn().getResponse().getContentAsString();
        // ("instance" is the request path, which legitimately contains "accounts".)
        assertThat(body).doesNotContain("relation").doesNotContain("violates").doesNotContain("ck_accounts_balance_range")
                .doesNotContain("ERROR");
    }

    @Test
    void aBlankDescriptionIsSentToTheDatabaseAsNull() throws Exception {
        given(ledger.deposit(any(), anyLong(), any(), anyString())).willReturn(result("POSTED", false));
        deposit("key-blank", "{\"amount\":\"1\",\"description\":\"   \"}").andExpect(status().isCreated());
        deposit("key-empty", "{\"amount\":\"1\",\"description\":\"\"}").andExpect(status().isCreated());
        verify(ledger).deposit(eq(ACCOUNT), eq(100L), eq(null), eq("key-blank"));
        verify(ledger).deposit(eq(ACCOUNT), eq(100L), eq(null), eq("key-empty"));
    }

    private ResultActions statement(String query) throws Exception {
        // URI.create keeps the query exactly as written (a string template would re-encode '%').
        return mvc.perform(get(URI.create("/api/accounts/" + ACCOUNT + "/statement" + query))
                .header("Authorization", "Bearer " + holderToken()));
    }

    @Test
    void statementFiltersReachTheServiceAsEnums() throws Exception {
        statement("").andExpect(status().isOk());
        verify(statements).own(ACCOUNT, StatementFilter.NONE, 0, 50);
        statement("?status=REFUSED&operation=TRANSFER&page=2&size=10").andExpect(status().isOk());
        verify(statements).own(ACCOUNT,
                new StatementFilter(StatementFilter.Status.REFUSED, StatementFilter.Operation.TRANSFER), 2, 10);
        statement("?operation=DEPOSIT").andExpect(status().isOk());
        verify(statements).own(ACCOUNT, new StatementFilter(null, StatementFilter.Operation.DEPOSIT), 0, 50);
    }

    // Exact upper-case single values only: empty, lower case, padded, unknown, SQL-like and repeated values are 400.
    @ParameterizedTest
    @ValueSource(strings = {"?status=", "?status=posted", "?status=%20POSTED", "?status=PENDING",
            "?status=POSTED&status=REFUSED", "?status=POSTED'%20or%20'1'='1", "?operation=", "?operation=transfer",
            "?operation=REFUND", "?operation=DEPOSIT,TRANSFER", "?operation=DEPOSIT&operation=DEPOSIT",
            "?status=POSTED&operation=PAYMENT"})
    void invalidStatementFiltersAre400BeforeTheService(String query) throws Exception {
        statement(query).andExpect(status().isBadRequest());
        verifyNoInteractions(statements);
    }
}
