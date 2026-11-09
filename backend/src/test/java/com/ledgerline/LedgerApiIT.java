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

/** Phase 5 money endpoints end to end over HTTP with real PostgreSQL (sequential behaviour; see LedgerConcurrencyIT). */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIT {

    private static final String SETTLEMENT = "a0000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private Environment environment;

    private JdbcTemplate ownerJdbc;
    private LedgerApi api;

    @BeforeEach
    void setUp() {
        ownerJdbc = OwnerJdbc.forTestDatabase(environment);
        api = new LedgerApi(mvc, appJdbc, ownerJdbc);
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static <T> T json(MockHttpServletResponse response, String path) {
        return JsonPath.read(body(response), path);
    }

    private List<Map<String, Object>> statement(TestUser owner, UUID account) {
        return api.statementLines(owner, "/api/accounts/" + account + "/statement");
    }

    @Test
    void theWorkedExampleFromTheReadme() {
        TestUser a = api.user("ACCOUNT_HOLDER");
        TestUser b = api.user("ACCOUNT_HOLDER");
        UUID accountA = api.account(a);
        UUID accountB = api.account(b);
        long settlementBefore = api.balance(UUID.fromString(SETTLEMENT));

        MockHttpServletResponse deposit = api.deposit(a, accountA, "500", key());
        assertThat(deposit.getStatus()).isEqualTo(201);
        assertThat((String) json(deposit, "$.balance")).isEqualTo("500.00");
        assertThat((String) json(deposit, "$.counterpartyAccountId")).isEqualTo(SETTLEMENT);
        assertThat(api.withdraw(a, accountA, "200", key()).getStatus()).isEqualTo(201);
        MockHttpServletResponse transfer = api.transfer(a, accountA, accountB, "100", key());
        assertThat(transfer.getStatus()).isEqualTo(201);
        assertThat((String) json(transfer, "$.operation")).isEqualTo("TRANSFER");
        assertThat((String) json(transfer, "$.initiatedBy")).isEqualTo(a.id().toString());

        assertThat(api.balance(accountA)).isEqualTo(20_000);
        assertThat(api.balance(accountB)).isEqualTo(10_000);
        assertThat(api.balance(UUID.fromString(SETTLEMENT)) - settlementBefore).isEqualTo(-30_000);

        List<Map<String, Object>> linesA = statement(a, accountA);
        assertThat(linesA).extracting(l -> l.get("direction")).containsExactly("CREDIT", "DEBIT", "DEBIT");
        assertThat(linesA).extracting(l -> l.get("balance")).containsExactly("500.00", "300.00", "200.00");
        LedgerApi.assertStatementConsistent(linesA, 20_000);
        List<Map<String, Object>> linesB = statement(b, accountB);
        assertThat(linesB).singleElement().satisfies(l -> {
            assertThat(l.get("direction")).isEqualTo("CREDIT");
            assertThat(l.get("initiatedBy")).as("the sender is the initiator").isEqualTo(a.id().toString());
            assertThat(l.get("counterpartyAccountId")).isEqualTo(accountA.toString());
        });
    }

    @Test
    void everyRoleAgainstEveryMoneyEndpoint() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        TestUser admin = api.user("ADMIN");
        TestUser auditor = api.user("AUDITOR");
        UUID account = api.fundedAccount(holder, "100");
        UUID other = api.fundedAccount(api.user("ACCOUNT_HOLDER"), "100");

        for (TestUser staff : List.of(admin, auditor)) {
            assertThat(api.deposit(staff, account, "1", key()).getStatus()).isEqualTo(403);
            assertThat(api.withdraw(staff, account, "1", key()).getStatus()).isEqualTo(403);
            assertThat(api.transfer(staff, account, other, "1", key()).getStatus()).isEqualTo(403);
            assertThat(api.get(staff, "/api/accounts/" + account + "/statement").getStatus()).isEqualTo(403);
            assertThat(api.get(staff, "/api/oversight/accounts/" + account + "/statement").getStatus()).isEqualTo(200);
            assertThat(api.get(staff, "/api/oversight/accounts/" + SETTLEMENT + "/statement").getStatus()).isEqualTo(200);
            assertThat(api.get(staff, "/api/oversight/reconciliation").getStatus()).isEqualTo(200);
        }
        assertThat(api.get(holder, "/api/oversight/accounts/" + account + "/statement").getStatus()).isEqualTo(403);
        assertThat(api.get(holder, "/api/oversight/reconciliation").getStatus()).isEqualTo(403);
        assertThat(api.deposit(null, account, "1", key()).getStatus()).isEqualTo(401);
        assertThat(api.get(null, "/api/accounts/" + account + "/statement").getStatus()).isEqualTo(401);
        assertThat(api.get(null, "/api/oversight/reconciliation").getStatus()).isEqualTo(401);
        // Staff attempts changed nothing.
        assertThat(api.balance(account)).isEqualTo(10_000);
        assertThat(statement(holder, account)).hasSize(1);
    }

    @Test
    void foreignAccountsGiveTheSame404AsUnknownOnesAndNothingIsRecordedOnTheVictim() {
        TestUser victim = api.user("ACCOUNT_HOLDER");
        TestUser attacker = api.user("ACCOUNT_HOLDER");
        UUID victimAccount = api.fundedAccount(victim, "100");
        UUID attackerAccount = api.fundedAccount(attacker, "100");
        UUID unknown = UUID.randomUUID();

        for (UUID target : List.of(victimAccount, unknown)) {
            String kind = target.equals(unknown) ? "unknown" : "foreign";
            MockHttpServletResponse withdraw = api.withdraw(attacker, target, "1", key());
            MockHttpServletResponse deposit = api.deposit(attacker, target, "1", key());
            MockHttpServletResponse transfer = api.transfer(attacker, target, attackerAccount, "1", key());
            MockHttpServletResponse statement = api.get(attacker, "/api/accounts/" + target + "/statement");
            for (MockHttpServletResponse response : List.of(withdraw, deposit, transfer, statement)) {
                assertThat(response.getStatus()).as(kind).isEqualTo(404);
                assertThat(body(response).replace(target.toString(), "ID")).as(kind)
                        .doesNotContain(victim.id().toString()).contains("\"title\":\"Account not found\"");
            }
        }
        String foreign = body(api.withdraw(attacker, victimAccount, "1", key())).replace(victimAccount.toString(), "ID");
        String missing = body(api.withdraw(attacker, unknown, "1", key())).replace(unknown.toString(), "ID");
        assertThat(foreign).isEqualTo(missing);

        assertThat(api.balance(victimAccount)).isEqualTo(10_000);
        assertThat(statement(victim, victimAccount)).hasSize(1);    // only the funding deposit
    }

    @Test
    void refusalsAreRecordedOnlyOnTheCallersOwnStatementAndChangeNoBalance() {
        TestUser sender = api.user("ACCOUNT_HOLDER");
        TestUser recipient = api.user("ACCOUNT_HOLDER");
        UUID from = api.fundedAccount(sender, "10");
        UUID to = api.fundedAccount(recipient, "10");

        MockHttpServletResponse overdraft = api.withdraw(sender, from, "10.01", key());
        assertThat(overdraft.getStatus()).isEqualTo(422);
        assertThat((String) json(overdraft, "$.reason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat((String) json(overdraft, "$.outcome")).isEqualTo("REFUSED");
        assertThat((String) json(overdraft, "$.balance")).isEqualTo("10.00");
        assertThat((Object) json(overdraft, "$.transactionId")).isNull();

        MockHttpServletResponse tooMuch = api.transfer(sender, from, to, "50", key());
        assertThat((String) json(tooMuch, "$.reason")).isEqualTo("INSUFFICIENT_FUNDS");

        for (UUID destination : List.of(UUID.randomUUID(), UUID.fromString(SETTLEMENT))) {
            MockHttpServletResponse unavailable = api.transfer(sender, from, destination, "1", key());
            assertThat(unavailable.getStatus()).isEqualTo(422);
            assertThat((String) json(unavailable, "$.reason")).isEqualTo("DESTINATION_UNAVAILABLE");
        }

        assertThat(api.balance(from)).isEqualTo(1_000);
        assertThat(api.balance(to)).isEqualTo(1_000);
        List<Map<String, Object>> senderLines = statement(sender, from);
        assertThat(senderLines).extracting(l -> l.get("status")).containsExactly("POSTED", "REFUSED", "REFUSED", "REFUSED", "REFUSED");
        assertThat(senderLines.subList(1, 5)).allSatisfy(l -> {
            assertThat(l.get("balance")).isEqualTo("10.00");
            assertThat(l.get("initiatedBy")).isEqualTo(sender.id().toString());
        });
        LedgerApi.assertStatementConsistent(senderLines, 1_000);
        assertThat(statement(recipient, to)).as("nothing on the recipient's statement").hasSize(1);
        assertThat(appJdbc.queryForObject("select count(*) from journal_transactions where initiated_by = ? and kind = 'TRANSFER'",
                Integer.class, sender.id())).as("no journal for a refusal").isZero();
    }

    @Test
    void aFrozenAccountRefusesWithAccountNotActiveAndAFrozenDestinationIsUnavailable() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "100");
        UUID second = api.fundedAccount(holder, "100");
        ownerJdbc.update("update accounts set status = 'FROZEN' where id = ?", second);

        assertThat((String) json(api.transfer(holder, account, second, "1", key()), "$.reason")).isEqualTo("DESTINATION_UNAVAILABLE");
        assertThat((String) json(api.withdraw(holder, second, "1", key()), "$.reason")).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat((String) json(api.deposit(holder, second, "1", key()), "$.reason")).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(api.balance(second)).isEqualTo(10_000);
    }

    @Test
    void operationAndBalanceLimits() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.account(holder);
        MockHttpServletResponse overLimit = api.deposit(holder, account, "1000000.01", key());
        assertThat(overLimit.getStatus()).isEqualTo(422);
        assertThat((String) json(overLimit, "$.reason")).isEqualTo("AMOUNT_LIMIT_EXCEEDED");

        for (int i = 0; i < 10; i++) {                               // 10 x Rs 10,00,000 = Rs 1,00,00,000
            assertThat(api.deposit(holder, account, "1000000.00", key()).getStatus()).isEqualTo(201);
        }
        assertThat(api.balance(account)).isEqualTo(1_000_000_000L);
        assertThat((String) json(api.deposit(holder, account, "0.01", key()), "$.reason")).isEqualTo("BALANCE_LIMIT_EXCEEDED");

        // A transfer that would take the recipient over the limit: the sender only learns "unavailable".
        TestUser sender = api.user("ACCOUNT_HOLDER");
        UUID from = api.fundedAccount(sender, "1");
        UUID full = account;
        assertThat((String) json(api.transfer(sender, from, full, "1", key()), "$.reason")).isEqualTo("DESTINATION_UNAVAILABLE");
        assertThat(api.balance(account)).isEqualTo(1_000_000_000L);
        LedgerApi.assertStatementConsistent(statement(holder, account), 1_000_000_000L);
    }

    @Test
    void sameKeyReplaysTheOriginalResultAndADifferentPayloadIsAConflict() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        String key = key();
        MockHttpServletResponse first = api.withdraw(holder, account, "4", key);
        MockHttpServletResponse again = api.withdraw(holder, account, "4", key);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Idempotent-Replayed")).isNull();
        assertThat(again.getStatus()).isEqualTo(201);
        assertThat(again.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(again)).isEqualTo(body(first));

        MockHttpServletResponse changed = api.withdraw(holder, account, "5", key);
        assertThat(changed.getStatus()).isEqualTo(409);
        assertThat(api.deposit(holder, account, "4", key).getStatus()).as("another operation, same key").isEqualTo(409);
        assertThat(api.balance(account)).isEqualTo(600);
        assertThat(statement(holder, account)).hasSize(2);

        // Keys are per user: someone else's identical key is independent.
        TestUser other = api.user("ACCOUNT_HOLDER");
        UUID otherAccount = api.fundedAccount(other, "10");
        assertThat(api.withdraw(other, otherAccount, "4", key).getStatus()).isEqualTo(201);
        assertThat(api.balance(otherAccount)).isEqualTo(600);
    }

    @Test
    void aRefusedAttemptReplaysAsRefusedEvenAfterFundsArrive() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        String key = key();
        MockHttpServletResponse refused = api.withdraw(holder, account, "50", key);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(api.deposit(holder, account, "100", key()).getStatus()).isEqualTo(201);

        MockHttpServletResponse replay = api.withdraw(holder, account, "50", key);
        assertThat(replay.getStatus()).isEqualTo(422);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay)).isEqualTo(body(refused));
        assertThat(api.withdraw(holder, account, "50", key()).getStatus()).as("a new key").isEqualTo(201);
        assertThat(api.balance(account)).isEqualTo(6_000);
        LedgerApi.assertStatementConsistent(statement(holder, account), 6_000);
    }

    @Test
    void initiatorAndOtherInjectedFieldsAreRejected() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        TestUser victim = api.user("ACCOUNT_HOLDER");
        for (String extra : List.of("\"initiatorId\":\"" + victim.id() + "\"", "\"initiatedBy\":\"" + victim.id() + "\"",
                "\"accountId\":\"" + UUID.randomUUID() + "\"", "\"balance\":\"999\"", "\"currency\":\"USD\"")) {
            MockHttpServletResponse response = api.money(holder, "/api/accounts/" + account + "/withdrawals",
                    "{\"amount\":\"1\"," + extra + "}", key());
            assertThat(response.getStatus()).as(extra).isEqualTo(400);
        }
        assertThat(api.money(holder, "/api/accounts/" + account + "/deposits", "{\"amount\":1}", key()).getStatus()).isEqualTo(400);
        assertThat(api.money(holder, "/api/accounts/" + account + "/deposits", "{\"amount\":\"1\"}", null).getStatus()).isEqualTo(400);
        assertThat(api.transfer(holder, account, account, "1", key()).getStatus()).isEqualTo(400);
        assertThat(api.balance(account)).isEqualTo(1_000);
    }

    @Test
    void oversightStatementsAndReconciliation() {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "25");
        api.withdraw(holder, account, "100", key());
        TestUser auditor = api.user("AUDITOR");

        List<Map<String, Object>> lines = api.statementLines(auditor, "/api/oversight/accounts/" + account + "/statement");
        LedgerApi.assertStatementConsistent(lines, 2_500);
        // The settlement statement grows with every test run, so check its last page here; reconciliation below
        // checks every one of its running balances.
        MockHttpServletResponse first = api.get(auditor, "/api/oversight/accounts/" + SETTLEMENT + "/statement?size=100");
        int lastPage = (Integer) json(first, "$.totalPages") - 1;
        MockHttpServletResponse last = api.get(auditor, "/api/oversight/accounts/" + SETTLEMENT + "/statement?size=100&page=" + lastPage);
        List<Map<String, Object>> tail = json(last, "$.lines");
        assertThat(((Number) tail.getLast().get("sequence")).longValue()).isEqualTo(((Number) json(last, "$.totalLines")).longValue());
        assertThat(((Number) tail.getLast().get("balanceMinor")).longValue()).isEqualTo(api.balance(UUID.fromString(SETTLEMENT)));
        assertThat((String) json(last, "$.account.kind")).isEqualTo("SYSTEM");

        MockHttpServletResponse reconciliation = api.get(api.user("ADMIN"), "/api/oversight/reconciliation");
        assertThat(reconciliation.getStatus()).isEqualTo(200);
        assertThat((Boolean) json(reconciliation, "$.consistent")).isTrue();
        assertThat((Integer) json(reconciliation, "$.differenceCount")).isZero();
        assertThat(((Number) json(reconciliation, "$.totalBalanceMinor")).longValue()).isZero();
        long customers = ((Number) json(reconciliation, "$.customerBalanceTotalMinor")).longValue();
        long settlement = ((Number) json(reconciliation, "$.settlementBalanceMinor")).longValue();
        assertThat(customers).isEqualTo(-settlement).isPositive();
    }
}
