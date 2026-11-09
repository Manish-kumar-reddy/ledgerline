package com.ledgerline.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the real HTTP API in integration tests: registers users (roles set as the database owner, exactly as in
 * production), opens accounts, moves money and reads statements. Safe to use from many threads at once.
 */
public final class LedgerApi {

    public static final String PASSWORD = "correct horse battery staple";

    public record TestUser(UUID id, String email, String token) {
    }

    private final MockMvc mvc;
    private final JdbcTemplate appJdbc;
    private final JdbcTemplate ownerJdbc;

    public LedgerApi(MockMvc mvc, JdbcTemplate appJdbc, JdbcTemplate ownerJdbc) {
        this.mvc = mvc;
        this.appJdbc = appJdbc;
        this.ownerJdbc = ownerJdbc;
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, TestUser as) {
        try {
            return mvc.perform(as == null ? request : request.header("Authorization", "Bearer " + as.token()))
                    .andReturn().getResponse();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String body(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public TestUser user(String role) {
        String email = "ledger-" + UUID.randomUUID() + "@example.test";
        MockHttpServletResponse registered = perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Ledger Tester\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"), null);
        assertThat(registered.getStatus()).isEqualTo(201);
        if (!"ACCOUNT_HOLDER".equals(role)) {
            assertThat(ownerJdbc.update("update users set role = ? where email = ?", role, email)).isEqualTo(1);
        }
        MockHttpServletResponse login = perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"), null);
        assertThat(login.getStatus()).isEqualTo(200);
        UUID id = appJdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return new TestUser(id, email, JsonPath.read(body(login), "$.accessToken"));
    }

    public UUID account(TestUser owner) {
        MockHttpServletResponse created = perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ledger test\"}"), owner);
        assertThat(created.getStatus()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(body(created), "$.id"));
    }

    public MockHttpServletResponse deposit(TestUser as, UUID account, String amount, String key) {
        return money(as, "/api/accounts/" + account + "/deposits", "{\"amount\":\"" + amount + "\"}", key);
    }

    public MockHttpServletResponse withdraw(TestUser as, UUID account, String amount, String key) {
        return money(as, "/api/accounts/" + account + "/withdrawals", "{\"amount\":\"" + amount + "\"}", key);
    }

    public MockHttpServletResponse transfer(TestUser as, UUID from, UUID to, String amount, String key) {
        return money(as, "/api/accounts/" + from + "/transfers",
                "{\"toAccountId\":\"" + to + "\",\"amount\":\"" + amount + "\"}", key);
    }

    public MockHttpServletResponse money(TestUser as, String path, String json, String key) {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return perform(request, as);
    }

    public MockHttpServletResponse get(TestUser as, String path) {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path), as);
    }

    /** A funded account: opened and given one deposit through the API. */
    public UUID fundedAccount(TestUser owner, String amount) {
        UUID account = account(owner);
        assertThat(deposit(owner, account, amount, "fund-" + UUID.randomUUID()).getStatus()).isEqualTo(201);
        return account;
    }

    public long balance(UUID account) {
        return appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, account);
    }

    /** Every line of a statement (all pages), via the owner's or an oversight endpoint. */
    public List<Map<String, Object>> statementLines(TestUser as, String statementPath) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int page = 0; ; page++) {
            MockHttpServletResponse response = perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(statementPath + "?size=100&page=" + page), as);
            assertThat(response.getStatus()).isEqualTo(200);
            List<Map<String, Object>> chunk = JsonPath.read(body(response), "$.lines");
            lines.addAll(chunk);
            int totalPages = JsonPath.read(body(response), "$.totalPages");
            if (page + 1 >= totalPages) {
                return lines;
            }
        }
    }

    /**
     * Statement integrity: sequence numbers 1..n with no gaps; every POSTED line's balance is the previous balance
     * plus a credit or minus a debit; every REFUSED line leaves the balance unchanged; the last balance is the
     * account's stored balance; and line times never decrease as the sequence number increases.
     */
    public static void assertStatementConsistent(List<Map<String, Object>> lines, long storedBalance) {
        long running = 0;
        java.time.Instant previous = java.time.Instant.MIN;
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> line = lines.get(i);
            java.time.Instant occurred = java.time.Instant.parse((String) line.get("occurredAt"));
            assertThat(occurred).as("time of line %d is not before line %d", i + 1, i).isAfterOrEqualTo(previous);
            previous = occurred;
            assertThat(((Number) line.get("sequence")).longValue()).as("sequence of line %d", i).isEqualTo(i + 1);
            long amount = ((Number) line.get("amountMinor")).longValue();
            long balance = ((Number) line.get("balanceMinor")).longValue();
            if ("POSTED".equals(line.get("status"))) {
                running += "CREDIT".equals(line.get("direction")) ? amount : -amount;
            } else {
                assertThat(line.get("status")).isEqualTo("REFUSED");
                assertThat(line.get("direction")).isNull();
            }
            assertThat(balance).as("running balance at line %d", i + 1).isEqualTo(running);
            assertThat(line.get("initiatedBy")).as("initiator at line %d", i + 1).isNotNull();
        }
        assertThat(running).as("final balance").isEqualTo(storedBalance);
    }
}
