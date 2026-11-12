package com.ledgerline;

import static com.ledgerline.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.support.LedgerApi;
import com.ledgerline.support.LedgerApi.TestUser;
import com.ledgerline.support.OwnerJdbc;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Statement filters (status, operation) over HTTP with real PostgreSQL, on both the account holder's and the
 * oversight endpoint. Each test builds a fresh account with this known history (amounts in paise):
 *
 * <pre>
 *  seq  status   operation   direction  amount   balance
 *   1   POSTED   DEPOSIT     CREDIT     10000    10000
 *   2   POSTED   WITHDRAWAL  DEBIT       3000     7000
 *   3   REFUSED  WITHDRAWAL  -         100000     7000   (insufficient funds)
 *   4   POSTED   TRANSFER    DEBIT       2000     5000   (to the recipient)
 *   5   REFUSED  TRANSFER    -         500000     5000   (insufficient funds)
 *   6   POSTED   DEPOSIT     CREDIT      1000     6000
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementFilterIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private Environment environment;

    private LedgerApi api;
    private TestUser holder;
    private TestUser recipient;
    private UUID account;
    private UUID recipientAccount;

    @BeforeEach
    void setUp() {
        api = new LedgerApi(mvc, appJdbc, OwnerJdbc.forTestDatabase(environment));
        holder = api.user("ACCOUNT_HOLDER");
        recipient = api.user("ACCOUNT_HOLDER");
        account = api.account(holder);
        recipientAccount = api.account(recipient);
        assertThat(api.deposit(holder, account, "100", key()).getStatus()).isEqualTo(201);
        assertThat(api.withdraw(holder, account, "30", key()).getStatus()).isEqualTo(201);
        assertThat(api.withdraw(holder, account, "1000", key()).getStatus()).isEqualTo(422);
        assertThat(api.transfer(holder, account, recipientAccount, "20", key()).getStatus()).isEqualTo(201);
        assertThat(api.transfer(holder, account, recipientAccount, "5000", key()).getStatus()).isEqualTo(422);
        assertThat(api.deposit(holder, account, "10", key()).getStatus()).isEqualTo(201);
    }

    private static String key() {
        return "filter-it-" + UUID.randomUUID();
    }

    private static <T> T json(MockHttpServletResponse response, String path) {
        return JsonPath.read(body(response), path);
    }

    private String own(String query) {
        return "/api/accounts/" + account + "/statement" + query;
    }

    private String oversight(String query) {
        return "/api/oversight/accounts/" + account + "/statement" + query;
    }

    private MockHttpServletResponse ok(TestUser as, String path) {
        MockHttpServletResponse response = api.get(as, path);
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        return response;
    }

    private static List<Long> sequences(MockHttpServletResponse response) {
        List<Number> numbers = json(response, "$.lines[*].sequence");
        return numbers.stream().map(Number::longValue).toList();
    }

    private static List<Long> balances(MockHttpServletResponse response) {
        List<Number> numbers = json(response, "$.lines[*].balanceMinor");
        return numbers.stream().map(Number::longValue).toList();
    }

    private static long totalLines(MockHttpServletResponse response) {
        return ((Number) json(response, "$.totalLines")).longValue();
    }

    private static int totalPages(MockHttpServletResponse response) {
        return json(response, "$.totalPages");
    }

    @Test
    void unfilteredStatementIsUnchanged() {
        MockHttpServletResponse all = ok(holder, own(""));
        assertThat(sequences(all)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(totalLines(all)).isEqualTo(6);
        assertThat(totalPages(all)).isEqualTo(1);
        LedgerApi.assertStatementConsistent(json(all, "$.lines"), 6_000);
        assertThat((Integer) json(all, "$.account.balanceMinor")).isEqualTo(6_000);
    }

    @Test
    void filtersSelectLinesWithTheirOriginalSequenceAndStoredRunningBalance() {
        List<Map<String, Object>> all = json(ok(holder, own("")), "$.lines");

        record Case(String query, List<Long> sequences, List<Long> balances) {
        }
        List<Case> cases = List.of(
                new Case("?status=POSTED", List.of(1L, 2L, 4L, 6L), List.of(10_000L, 7_000L, 5_000L, 6_000L)),
                new Case("?status=REFUSED", List.of(3L, 5L), List.of(7_000L, 5_000L)),
                new Case("?operation=DEPOSIT", List.of(1L, 6L), List.of(10_000L, 6_000L)),
                new Case("?operation=WITHDRAWAL", List.of(2L, 3L), List.of(7_000L, 7_000L)),
                new Case("?operation=TRANSFER", List.of(4L, 5L), List.of(5_000L, 5_000L)),
                new Case("?status=REFUSED&operation=WITHDRAWAL", List.of(3L), List.of(7_000L)),
                new Case("?status=POSTED&operation=TRANSFER", List.of(4L), List.of(5_000L)),
                new Case("?operation=TRANSFER&status=REFUSED", List.of(5L), List.of(5_000L)));

        for (Case c : cases) {
            MockHttpServletResponse response = ok(holder, own(c.query()));
            assertThat(sequences(response)).as(c.query()).isEqualTo(c.sequences());
            assertThat(balances(response)).as(c.query()).isEqualTo(c.balances());
            assertThat(totalLines(response)).as(c.query()).isEqualTo(c.sequences().size());
            assertThat(totalPages(response)).as(c.query()).isEqualTo(1);
            // Every filtered line is exactly the same line as in the full statement (status, reason, initiator, ...).
            List<Map<String, Object>> lines = json(response, "$.lines");
            for (Map<String, Object> line : lines) {
                int sequence = ((Number) line.get("sequence")).intValue();
                assertThat(line).as(c.query() + " line " + sequence).isEqualTo(all.get(sequence - 1));
                assertThat(line.get("initiatedBy")).isEqualTo(holder.id().toString());
            }
            // The header is the account itself, never a filtered figure.
            assertThat((Integer) json(response, "$.account.balanceMinor")).as(c.query()).isEqualTo(6_000);
        }
        List<Map<String, Object>> refused = json(ok(holder, own("?status=REFUSED")), "$.lines");
        assertThat(refused).extracting(line -> line.get("reason")).containsOnly("INSUFFICIENT_FUNDS");
        assertThat(refused).extracting(line -> line.get("direction")).containsOnlyNulls();
    }

    @Test
    void filteredPaginationAndTotalsCountOnlyMatchingLines() {
        MockHttpServletResponse first = ok(holder, own("?status=POSTED&size=3"));
        assertThat(sequences(first)).containsExactly(1L, 2L, 4L);
        assertThat(totalLines(first)).isEqualTo(4);
        assertThat(totalPages(first)).isEqualTo(2);

        MockHttpServletResponse second = ok(holder, own("?status=POSTED&size=3&page=1"));
        assertThat(sequences(second)).containsExactly(6L);
        assertThat(totalLines(second)).isEqualTo(4);
        assertThat((Integer) json(second, "$.page")).isEqualTo(1);

        MockHttpServletResponse beyond = ok(holder, own("?status=POSTED&size=3&page=2"));
        assertThat(sequences(beyond)).isEmpty();
        assertThat(totalLines(beyond)).isEqualTo(4);

        MockHttpServletResponse onePerPage = ok(holder, own("?status=REFUSED&size=1&page=1"));
        assertThat(sequences(onePerPage)).containsExactly(5L);
        assertThat(totalPages(onePerPage)).isEqualTo(2);

        // No refused deposits: an empty, zero-page result, not an error.
        MockHttpServletResponse none = ok(holder, own("?status=REFUSED&operation=DEPOSIT"));
        assertThat(sequences(none)).isEmpty();
        assertThat(totalLines(none)).isZero();
        assertThat(totalPages(none)).isZero();
    }

    @Test
    void theRecipientSeesOnlyTheIncomingTransferAndNoneOfTheSendersRefusals() {
        String path = "/api/accounts/" + recipientAccount + "/statement";
        MockHttpServletResponse transfers = ok(recipient, path + "?operation=TRANSFER");
        assertThat(sequences(transfers)).containsExactly(1L);
        assertThat((String) json(transfers, "$.lines[0].direction")).isEqualTo("CREDIT");
        assertThat((String) json(transfers, "$.lines[0].counterpartyAccountId")).isEqualTo(account.toString());
        assertThat(totalLines(ok(recipient, path + "?status=REFUSED"))).isZero();
    }

    @Test
    void oversightFiltersGiveTheSameLinesAsTheHoldersOwnStatement() {
        for (TestUser staff : List.of(api.user("ADMIN"), api.user("AUDITOR"))) {
            for (String query : List.of("", "?status=REFUSED", "?operation=TRANSFER", "?status=POSTED&operation=DEPOSIT",
                    "?status=POSTED&size=2&page=1")) {
                MockHttpServletResponse mine = ok(holder, own(query));
                MockHttpServletResponse theirs = ok(staff, oversight(query));
                assertThat(body(theirs)).as(query).isEqualTo(body(mine));
            }
        }
    }

    @Test
    void filtersDoNotChangeWhoMayReadAStatement() {
        TestUser stranger = api.user("ACCOUNT_HOLDER");
        UUID unknown = UUID.randomUUID();
        String query = "?status=REFUSED&operation=WITHDRAWAL";

        // Another holder's account is the same 404 as an unknown one, with or without filters.
        MockHttpServletResponse foreign = api.get(stranger, "/api/accounts/" + account + "/statement" + query);
        MockHttpServletResponse missing = api.get(stranger, "/api/accounts/" + unknown + "/statement" + query);
        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(body(foreign).replace(account.toString(), "ID")).isEqualTo(body(missing).replace(unknown.toString(), "ID"))
                .doesNotContain(holder.id().toString()).doesNotContain("REFUSED");

        for (TestUser staff : List.of(api.user("ADMIN"), api.user("AUDITOR"))) {
            assertThat(api.get(staff, own(query)).getStatus()).isEqualTo(403);
        }
        assertThat(api.get(holder, oversight(query)).getStatus()).isEqualTo(403);
        assertThat(api.get(stranger, oversight(query)).getStatus()).isEqualTo(403);
        assertThat(api.get(null, own(query)).getStatus()).isEqualTo(401);
        assertThat(api.get(null, oversight(query)).getStatus()).isEqualTo(401);
    }

    @Test
    void invalidFilterValuesAre400OnBothEndpointsAndChangeNothing() {
        TestUser auditor = api.user("AUDITOR");
        for (String query : List.of("?status=", "?status=posted", "?status=PENDING", "?status=POSTED&status=REFUSED",
                "?operation=", "?operation=Deposit", "?operation=REFUND", "?operation=DEPOSIT,WITHDRAWAL",
                "?operation=TRANSFER'%20or%20'1'='1", "?status=REFUSED&operation=SWAP")) {
            MockHttpServletResponse holderResponse = api.get(holder, own(query));
            assertThat(holderResponse.getStatus()).as(query).isEqualTo(400);
            assertThat(body(holderResponse)).as(query).doesNotContain("select").doesNotContain("ledger_entries");
            assertThat(api.get(auditor, oversight(query)).getStatus()).as(query).isEqualTo(400);
        }
        assertThat(totalLines(ok(holder, own("")))).isEqualTo(6);
        assertThat(api.balance(account)).isEqualTo(6_000);
    }
}
