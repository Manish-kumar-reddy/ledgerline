package com.ledgerline;

import static com.ledgerline.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.money.Money;
import com.ledgerline.support.LedgerApi;
import com.ledgerline.support.LedgerApi.TestUser;
import com.ledgerline.support.OwnerJdbc;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real concurrent HTTP requests against real PostgreSQL. Every request in a burst runs on its own thread and all are
 * released at the same instant. This class gets its own connection pool of 10, so up to 10 posting transactions run
 * in the database at the same moment (the rest queue for a connection, as in production). The ledgerline_app role
 * allows 20 connections in total (database/setup/create-database.sql) and other cached test contexts hold up to 9,
 * so the pool is closed after this class ({@code @DirtiesContext}). Each burst ends with statement and
 * reconciliation checks.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerConcurrencyIT {

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

    /** Runs every task on its own thread, all released together; returns results in task order. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private static String key() {
        return "cc-" + UUID.randomUUID();
    }

    private int postingsFor(UUID user, String key) {
        return ownerJdbc.queryForObject("""
                select (select count(*) from journal_transactions j where j.idempotency_key_id = k.id)
                     + (select count(*) from refused_attempts r where r.idempotency_key_id = k.id)
                from idempotency_keys k where k.user_id = ? and k.idem_key = ?""", Integer.class, user, key);
    }

    private int keysFor(UUID user, String key) {
        return ownerJdbc.queryForObject("select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, user, key);
    }

    private void assertStatement(TestUser owner, UUID account) {
        LedgerApi.assertStatementConsistent(api.statementLines(owner, "/api/accounts/" + account + "/statement"),
                api.balance(account));
    }

    private void assertReconciles() {
        MockHttpServletResponse response = api.get(api.user("AUDITOR"), "/api/oversight/reconciliation");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).contains("\"consistent\":true", "\"differenceCount\":0", "\"totalBalanceMinor\":0");
    }

    // ---- 1. no overdraft under concurrency ------------------------------------------------------------------------

    @RepeatedTest(5)
    void fiftySimultaneousTenRupeeWithdrawalsAgainstOneHundredRupees() throws Exception {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "100");
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> api.withdraw(holder, account, "10", key()));
        }
        List<MockHttpServletResponse> responses = runTogether(tasks);

        assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(10);
        assertThat(responses).filteredOn(r -> r.getStatus() == 422).hasSize(40)
                .allSatisfy(r -> assertThat(body(r)).contains("\"reason\":\"INSUFFICIENT_FUNDS\"", "\"balanceMinor\":0"));
        assertThat(api.balance(account)).isZero();
        List<Map<String, Object>> lines = api.statementLines(holder, "/api/accounts/" + account + "/statement");
        assertThat(lines).hasSize(51);
        assertThat(lines).filteredOn(l -> "DEBIT".equals(l.get("direction"))).hasSize(10);
        assertThat(lines).filteredOn(l -> "REFUSED".equals(l.get("status"))).hasSize(40);
        LedgerApi.assertStatementConsistent(lines, 0);
        assertReconciles();
    }

    // ---- 2. idempotency under concurrency -------------------------------------------------------------------------

    @RepeatedTest(5)
    void twentySimultaneousIdenticalRequestsPostExactlyOnce() throws Exception {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "100");
        String key = key();
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> api.withdraw(holder, account, "7.25", key));
        }
        List<MockHttpServletResponse> responses = runTogether(tasks);

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
        assertThat(responses).filteredOn(r -> r.getHeader("Idempotent-Replayed") == null).hasSize(1);
        assertThat(responses).filteredOn(r -> "true".equals(r.getHeader("Idempotent-Replayed"))).hasSize(19);
        assertThat(responses.stream().map(LedgerApi::body).distinct()).as("identical bodies").hasSize(1);
        assertThat(postingsFor(holder.id(), key)).isEqualTo(1);
        assertThat(api.balance(account)).isEqualTo(10_000 - 725);
        assertStatement(holder, account);
    }

    @RepeatedTest(3)
    void sameKeyWithDifferentPayloadsAtOnceGivesOnePostingAndConflictsForTheOthers() throws Exception {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "100");
        String key = key();
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String amount = i % 2 == 0 ? "1" : "2";
            tasks.add(() -> api.withdraw(holder, account, amount, key));
        }
        List<MockHttpServletResponse> responses = runTogether(tasks);

        assertThat(postingsFor(holder.id(), key)).isEqualTo(1);
        long withdrawn = 10_000 - api.balance(account);
        assertThat(withdrawn).isIn(100L, 200L);
        // The ten requests matching the winner get its result; the ten with the other amount get 409.
        assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(10);
        assertThat(responses).filteredOn(r -> r.getStatus() == 409).hasSize(10);
        assertStatement(holder, account);
    }

    // ---- 3. deadlock freedom --------------------------------------------------------------------------------------

    @RepeatedTest(3)
    void oppositeDirectionTransfersAtOnceAllSucceedWithoutDeadlock() throws Exception {
        TestUser alice = api.user("ACCOUNT_HOLDER");
        TestUser bob = api.user("ACCOUNT_HOLDER");
        UUID a = api.fundedAccount(alice, "1000");
        UUID b = api.fundedAccount(bob, "1000");
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> api.transfer(alice, a, b, "10", key()));
            tasks.add(() -> api.transfer(bob, b, a, "10", key()));
        }
        List<MockHttpServletResponse> responses = runTogether(tasks);

        assertThat(responses).as("no 503 (lock timeout or deadlock) and no refusal")
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
        assertThat(api.balance(a)).isEqualTo(100_000);
        assertThat(api.balance(b)).isEqualTo(100_000);
        assertStatement(alice, a);
        assertStatement(bob, b);
        assertReconciles();
    }

    // ---- 4. conservation under a concurrent mixed workload ---------------------------------------------------------

    @RepeatedTest(3)
    void concurrentMixedDepositsWithdrawalsAndTransfersConserveMoney() throws Exception {
        List<TestUser> users = List.of(api.user("ACCOUNT_HOLDER"), api.user("ACCOUNT_HOLDER"), api.user("ACCOUNT_HOLDER"));
        Map<UUID, TestUser> ownerOf = new HashMap<>();
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TestUser owner = users.get(i % 3);
            UUID account = api.fundedAccount(owner, "500");
            accounts.add(account);
            ownerOf.put(account, owner);
        }
        long seed = System.nanoTime();
        Random random = new Random(seed);
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            UUID account = accounts.get(random.nextInt(accounts.size()));
            TestUser owner = ownerOf.get(account);
            String amount = Money.format(1 + random.nextInt(60_000));
            switch (random.nextInt(3)) {
                case 0 -> tasks.add(() -> api.deposit(owner, account, amount, key()));
                case 1 -> tasks.add(() -> api.withdraw(owner, account, amount, key()));
                default -> {
                    UUID to = accounts.get(random.nextInt(accounts.size()));
                    UUID destination = to.equals(account) ? accounts.get((accounts.indexOf(account) + 1) % 5) : to;
                    tasks.add(() -> api.transfer(owner, account, destination, amount, key()));
                }
            }
        }
        List<MockHttpServletResponse> responses = runTogether(tasks);

        long deposited = 0;
        long withdrawn = 0;
        for (MockHttpServletResponse response : responses) {
            assertThat(response.getStatus()).as("seed %d: %s", seed, body(response)).isIn(201, 422);
            if (response.getStatus() == 201) {
                long amount = ((Number) JsonPath.read(body(response), "$.amountMinor")).longValue();
                String operation = JsonPath.read(body(response), "$.operation");
                if ("DEPOSIT".equals(operation)) {
                    deposited += amount;
                } else if ("WITHDRAWAL".equals(operation)) {
                    withdrawn += amount;
                }
            }
        }
        long total = accounts.stream().mapToLong(api::balance).sum();
        assertThat(total).as("seed %d: money is conserved", seed).isEqualTo(5 * 50_000 + deposited - withdrawn);
        for (UUID account : accounts) {
            assertThat(api.balance(account)).as("seed %d", seed).isBetween(0L, 1_000_000_000L);
            assertStatement(ownerOf.get(account), account);
        }
        assertReconciles();
    }

    // ---- 5. lock timeout: nothing committed, retry is safe ---------------------------------------------------------

    @Test
    void aLockTimeoutRollsBackThePostingAndTheKeyAndTheSameKeyCanBeRetried() throws Exception {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "100");
        String key = key();
        try (Connection blocker = ownerJdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("select 1 from accounts where id = ? for update")) {
                lock.setObject(1, account);
                lock.execute();
            }
            MockHttpServletResponse timedOut = api.withdraw(holder, account, "10", key);   // waits ~3 s for the lock
            assertThat(timedOut.getStatus()).isEqualTo(503);
            assertThat(timedOut.getHeader("Retry-After")).isEqualTo("1");
            assertThat(keysFor(holder.id(), key)).as("no committed idempotency key").isZero();
            assertThat(api.balance(account)).isEqualTo(10_000);
            assertThat(appJdbc.queryForObject("select last_seq from accounts where id = ?", Long.class, account)).isEqualTo(1);
            blocker.rollback();
        }
        MockHttpServletResponse retried = api.withdraw(holder, account, "10", key);
        assertThat(retried.getStatus()).isEqualTo(201);
        assertThat(retried.getHeader("Idempotent-Replayed")).isNull();
        assertThat(api.withdraw(holder, account, "10", key).getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(api.balance(account)).isEqualTo(9_000);
        assertStatement(holder, account);
    }

    @Test
    void whenTheFirstRequestRollsBackTheWaitingDuplicatePostsInstead() throws Exception {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "100");
        String key = key();
        try (Connection blocker = ownerJdbc.getDataSource().getConnection();
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("select 1 from accounts where id = ? for update")) {
                lock.setObject(1, account);
                lock.execute();
            }
            // t=0: the first request claims the key, then waits for the account lock (times out at ~3 s).
            Future<MockHttpServletResponse> first = pool.submit(() -> api.withdraw(holder, account, "10", key));
            Thread.sleep(1_500);
            // t=1.5 s: an identical request waits on the first one's uncommitted key.
            Future<MockHttpServletResponse> second = pool.submit(() -> api.withdraw(holder, account, "10", key));
            assertThat(first.get().getStatus()).as("first rolled back by lock timeout").isEqualTo(503);
            // t=~3 s: the first rolled back, so the second now owns the key and waits for the account lock itself.
            Thread.sleep(500);
            blocker.rollback();                                       // release the lock well inside its timeout
            MockHttpServletResponse result = second.get();
            assertThat(result.getStatus()).isEqualTo(201);
            assertThat(result.getHeader("Idempotent-Replayed")).as("it posted; it did not replay").isNull();
        }
        assertThat(postingsFor(holder.id(), key)).isEqualTo(1);
        assertThat(api.balance(account)).isEqualTo(9_000);
        assertStatement(holder, account);
    }

    // ---- 6. statement times follow sequence order under contention (V6, M1) -------------------------------------------

    @RepeatedTest(3)
    void simultaneousDepositsGetTimesThatFollowTheSettlementSequence() throws Exception {
        TestUser holder = api.user("ACCOUNT_HOLDER");
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            accounts.add(api.account(holder));
        }
        String settlement = "a0000000-0000-4000-8000-000000000001";
        long before = appJdbc.queryForObject("select last_seq from accounts where id = ?::uuid", Long.class, settlement);
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (UUID account : accounts) {
            tasks.add(() -> api.deposit(holder, account, "1", key()));      // every one locks the settlement account
        }
        assertThat(runTogether(tasks)).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));

        List<java.sql.Timestamp> times = appJdbc.queryForList(
                "select j.posted_at from account_statement_lines l "
                + "join ledger_entries e on e.id = l.ledger_entry_id "
                + "join journal_transactions j on j.id = e.transaction_id "
                + "where l.account_id = ?::uuid and l.seq > ? order by l.seq", java.sql.Timestamp.class, settlement, before);
        assertThat(times).hasSizeGreaterThanOrEqualTo(20);
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).as("settlement line %d", before + i + 1).isAfterOrEqualTo(times.get(i - 1));
        }
        for (UUID account : accounts) {
            assertStatement(holder, account);
        }
        assertReconciles();
    }
}
