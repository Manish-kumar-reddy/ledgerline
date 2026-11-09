package com.ledgerline.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerline.security.AttemptLimiter.Blocked;
import com.ledgerline.security.AttemptLimiter.Full;
import com.ledgerline.security.AttemptLimiter.Limit;
import com.ledgerline.security.AttemptLimiter.OnFull;
import com.ledgerline.security.AttemptLimiter.Reservation;
import com.ledgerline.security.AttemptLimiter.Reserved;
import com.ledgerline.security.AttemptLimiter.Result;
import com.ledgerline.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Unit tests (no database, no Spring) for the atomic counting core, including real concurrent callers. */
class AttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final AttemptLimiter limiter = new AttemptLimiter("test", clock, Duration.ofMinutes(15), 10_000);

    private static AttemptLimiter.Result tryReserveOn(AttemptLimiter target, OnFull onFull, Limit... limits) {
        return target.tryReserve(List.of(limits), onFull);
    }

    private Reservation reserve(Limit... limits) {
        return ((Reserved) limiter.tryReserve(List.of(limits), OnFull.REJECT)).reservation();
    }

    // ---- sequential semantics ---------------------------------------------------------------------------------

    @Test
    void countsUpToTheLimitThenBlocksWithTheRemainingTime() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryReserve(List.of(new Limit("k", 3)), OnFull.REJECT)).isInstanceOf(Reserved.class);
        }
        clock.advance(Duration.ofMinutes(4));
        assertThat(limiter.tryReserve(List.of(new Limit("k", 3)), OnFull.REJECT))
                .isEqualTo(new Blocked(Duration.ofMinutes(11)));
        assertThat(limiter.count("k")).as("a blocked attempt is not counted").isEqualTo(3);
    }

    @Test
    void combinedReservationIsAllOrNothing() {
        reserve(new Limit("ip", 100), new Limit("email", 1));       // email now at its limit
        assertThat(limiter.tryReserve(List.of(new Limit("ip", 100), new Limit("email", 1)), OnFull.REJECT)).isInstanceOf(Blocked.class);
        assertThat(limiter.count("ip")).as("the IP is not counted when the email blocks").isEqualTo(1);
    }

    @Test
    void releaseRemovesOnlyThatReservationsOwnCount() {
        Reservation first = reserve(new Limit("k", 10));
        reserve(new Limit("k", 10));
        reserve(new Limit("k", 10));
        limiter.release(first);
        assertThat(limiter.count("k")).isEqualTo(2);
    }

    @Test
    void releaseAfterTheWindowRolledOverDoesNotTouchTheNewWindow() {
        Reservation old = reserve(new Limit("k", 10));
        clock.advance(Duration.ofMinutes(15));
        reserve(new Limit("k", 10));                                  // starts a new window, count 1
        limiter.release(old);
        assertThat(limiter.count("k")).isEqualTo(1);
    }

    @Test
    void theWindowEndsAndCountingStartsAgain() {
        reserve(new Limit("k", 1));
        assertThat(limiter.tryReserve(List.of(new Limit("k", 1)), OnFull.REJECT)).isInstanceOf(Blocked.class);
        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.tryReserve(List.of(new Limit("k", 1)), OnFull.REJECT)).isInstanceOf(Reserved.class);
    }

    // ---- capacity: a reservation is never partly counted ------------------------------------------------------

    /** A limiter with room for 2 keys, already holding "a" and "b" (one attempt each, limit 5). */
    private AttemptLimiter fullLimiter() {
        AttemptLimiter full = new AttemptLimiter("full", clock, Duration.ofMinutes(15), 2);
        tryReserveOn(full, OnFull.REJECT, new Limit("a", 5), new Limit("b", 5));
        assertThat(full.trackedKeyCount()).isEqualTo(2);
        return full;
    }

    @Test
    void fullWithBothKeysNewRejectCountsNothing() {
        AttemptLimiter full = fullLimiter();
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("ip:new", 50), new Limit("email:new", 5)))
                .isInstanceOf(Full.class);
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("a", "b");
        assertThat(full.count("a")).isEqualTo(1);
    }

    @Test
    void fullWithBothKeysNewAllowUntrackedCountsNothing() {
        AttemptLimiter full = fullLimiter();
        assertThat(tryReserveOn(full, OnFull.ALLOW_UNTRACKED, new Limit("ip:new", 50), new Limit("email:new", 5)))
                .isEqualTo(new Reserved(Reservation.NONE));
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void fullWithOneExistingAndOneNewKeyDoesNotCountTheExistingKeyAlone() {
        // The old behaviour counted "a" and skipped the new key: a partial reservation. Now neither is counted.
        AttemptLimiter full = fullLimiter();
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("a", 5), new Limit("email:new", 5)))
                .isInstanceOf(Full.class);
        assertThat(full.count("a")).as("the existing key is not counted on its own").isEqualTo(1);
        assertThat(tryReserveOn(full, OnFull.ALLOW_UNTRACKED, new Limit("a", 5), new Limit("email:new", 5)))
                .isEqualTo(new Reserved(Reservation.NONE));
        assertThat(full.count("a")).isEqualTo(1);
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void fullWithAnExistingKeyAtItsLimitIsBlockedNotFullOrUntracked() {
        AttemptLimiter full = new AttemptLimiter("full", clock, Duration.ofMinutes(15), 2);
        tryReserveOn(full, OnFull.REJECT, new Limit("ip:busy", 1), new Limit("b", 5));   // ip:busy now at limit 1
        clock.advance(Duration.ofMinutes(5));
        // Even the fail-open policy must not let a key that is at its limit through just because the table is full.
        for (OnFull policy : OnFull.values()) {
            assertThat(tryReserveOn(full, policy, new Limit("ip:busy", 1), new Limit("email:new", 5)))
                    .as(policy.name()).isEqualTo(new Blocked(Duration.ofMinutes(10)));
        }
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("ip:busy", "b");
    }

    @Test
    void fullButBothKeysAlreadyTrackedIsCountedNormally() {
        AttemptLimiter full = fullLimiter();
        Result result = tryReserveOn(full, OnFull.REJECT, new Limit("a", 5), new Limit("b", 5));
        assertThat(result).isInstanceOf(Reserved.class);
        assertThat(full.count("a")).isEqualTo(2);
        assertThat(full.count("b")).isEqualTo(2);
    }

    @Test
    void releaseAfterACapacityLimitedReservationChangesNothing() {
        AttemptLimiter full = fullLimiter();
        Reservation untracked = ((Reserved) tryReserveOn(full, OnFull.ALLOW_UNTRACKED,
                new Limit("a", 5), new Limit("email:new", 5))).reservation();
        assertThat(untracked).isEqualTo(Reservation.NONE);
        full.release(untracked);
        assertThat(full.count("a")).as("releasing must not take away another attempt's count").isEqualTo(1);
        assertThat(full.count("b")).isEqualTo(1);
    }

    @Test
    void expiredEntriesAreRemovedToMakeRoom() {
        AttemptLimiter full = fullLimiter();
        clock.advance(Duration.ofMinutes(15));
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("ip:new", 50), new Limit("email:new", 5)))
                .isInstanceOf(Reserved.class);
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("ip:new", "email:new");
    }

    @Test
    void whileFullTheExpiredEntryScanRunsAtMostOncePerInterval() {
        AttemptLimiter full = fullLimiter();
        int before = full.purgeScans();
        for (int i = 0; i < 500; i++) {
            tryReserveOn(full, OnFull.REJECT, new Limit("new:" + i, 5));
        }
        assertThat(full.purgeScans() - before).as("500 refused attempts in the same instant").isEqualTo(1);
        clock.advance(AttemptLimiter.PURGE_INTERVAL);
        tryReserveOn(full, OnFull.REJECT, new Limit("new:x", 5));
        assertThat(full.purgeScans() - before).isEqualTo(2);
    }

    @Test
    void moreThanAThousandReservationsInOneSecondScanAtMostOnce() {
        // Room for 1,500 keys; 2,500 distinct keys inside one second. The 1,000th reservation reaches the
        // housekeeping trigger and the 1,501st finds the table full: both reasons to scan arise, and the
        // 1,000 refused attempts after that keep asking. Only one scan may run.
        AttemptLimiter busy = new AttemptLimiter("busy", clock, Duration.ofMinutes(15), 1_500);
        int reserved = 0;
        for (int i = 0; i < 2_500; i++) {
            if (i % 100 == 0) {
                clock.advance(Duration.ofMillis(9));                  // 25 steps of 9 ms: 225 ms in total
            }
            if (tryReserveOn(busy, OnFull.REJECT, new Limit("k" + i, 5)) instanceof Reserved) {
                reserved++;
            }
        }
        assertThat(reserved).isEqualTo(1_500);
        assertThat(busy.purgeScans()).isEqualTo(1);
        // A second interval later the gate opens again, exactly once.
        clock.advance(AttemptLimiter.PURGE_INTERVAL);
        for (int i = 0; i < 1_200; i++) {
            tryReserveOn(busy, OnFull.REJECT, new Limit("later" + i, 5));
        }
        assertThat(busy.purgeScans()).isEqualTo(2);
    }

    @Test
    void expiredEntriesFreeCapacityOnceTheThrottleIntervalHasPassed() {
        AttemptLimiter full = fullLimiter();                          // "a" and "b" counted at 10:00:00
        // Half a second before they expire, a new key is refused, and that refusal uses up the scan.
        clock.advance(Duration.ofMinutes(15).minusMillis(500));
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("new", 5))).isInstanceOf(Full.class);
        // Now they have expired, but the gate is still closed: still refused, nothing counted.
        clock.advance(Duration.ofMillis(500));
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("new", 5))).isInstanceOf(Full.class);
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("a", "b");
        // One interval after the last scan, the expired entries are removed and the new key is counted.
        clock.advance(AttemptLimiter.PURGE_INTERVAL.minusMillis(500));
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("new", 5))).isInstanceOf(Reserved.class);
        assertThat(full.trackedKeys()).containsExactly("new");
        assertThat(full.count("new")).isEqualTo(1);
    }

    @Test
    void aClockThatMovesBackwardsDoesNotKeepThePurgeGateClosed() {
        AttemptLimiter full = fullLimiter();
        clock.advance(Duration.ofMinutes(20));
        assertThat(tryReserveOn(full, OnFull.REJECT, new Limit("x", 5), new Limit("y", 5))).isInstanceOf(Reserved.class);
        clock.advance(Duration.ofMinutes(16));                        // "x" and "y" expired
        tryReserveOn(full, OnFull.REJECT, new Limit("z1", 5), new Limit("z2", 5));
        assertThat(full.trackedKeys()).containsExactlyInAnyOrder("z1", "z2");
        int scans = full.purgeScans();
        clock.advance(Duration.ofHours(-1));                          // wall-clock correction
        tryReserveOn(full, OnFull.REJECT, new Limit("w", 5));
        assertThat(full.purgeScans()).as("one scan allowed after the jump").isEqualTo(scans + 1);
    }

    // ---- real concurrency -------------------------------------------------------------------------------------

    /** Runs every task on its own thread, all released at the same instant, and returns their results. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<T> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
        }
        return results;
    }

    @RepeatedTest(20)
    void sixtyFourSimultaneousCallersOnOneKeyGetExactlyTheLimit() throws Exception {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            tasks.add(() -> limiter.tryReserve(List.of(new Limit("email:victim", 5)), OnFull.REJECT) instanceof Reserved);
        }
        assertThat(runTogether(tasks)).filteredOn(Boolean::booleanValue).hasSize(5);
        assertThat(limiter.count("email:victim")).isEqualTo(5);
    }

    @RepeatedTest(20)
    void simultaneousCallersRespectBothLimitsOfACombinedReservation() throws Exception {
        // 64 callers from one IP (limit 8), spread over 4 emails (limit 3 each): at most 8 in total may pass,
        // no email may exceed 3, and the IP count must equal exactly the number that passed.
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String email = "email:" + (i % 4);
            tasks.add(() -> limiter.tryReserve(List.of(new Limit("ip:one", 8), new Limit(email, 3)), OnFull.REJECT) instanceof Reserved
                    ? email : null);
        }
        List<String> passed = runTogether(tasks).stream().filter(e -> e != null).toList();
        assertThat(passed).hasSize(8);
        assertThat(limiter.count("ip:one")).isEqualTo(8);
        for (int e = 0; e < 4; e++) {
            String email = "email:" + e;
            assertThat(limiter.count(email)).isLessThanOrEqualTo(3).isEqualTo((int) passed.stream().filter(email::equals).count());
        }
    }

    @RepeatedTest(20)
    void concurrentReleasesNeverRemoveOtherReservationsCounts() throws Exception {
        // 10 reservations are made; 6 "succeed" and release concurrently while 4 "fail" and keep theirs.
        List<Reservation> reservations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            reservations.add(reserve(new Limit("k", 100)));
        }
        List<Callable<Boolean>> releases = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Reservation own = reservations.get(i);
            releases.add(() -> {
                limiter.release(own);
                return true;
            });
        }
        runTogether(releases);
        assertThat(limiter.count("k")).isEqualTo(4);
    }
}
