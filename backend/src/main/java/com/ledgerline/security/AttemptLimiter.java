package com.ledgerline.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Counts attempts per key in fixed windows (a window starts at the first attempt counted for that key).
 *
 * <p>Reserving several keys is atomic and all-or-nothing; a reservation is never partly counted:
 * <ol>
 *   <li>If any key is at its limit, the result is {@link Blocked} with that key's remaining time, and nothing is
 *       counted.</li>
 *   <li>Otherwise, if every key can be stored, every key is counted ({@link Reserved}).</li>
 *   <li>Otherwise (the table is full and some new keys do not fit, even after expired entries are removed)
 *       nothing is counted and the caller's {@link OnFull} policy decides: {@code REJECT} returns {@link Full};
 *       {@code ALLOW_UNTRACKED} returns {@link Reserved} with {@link Reservation#NONE}.</li>
 * </ol>
 * Because the check and the increment happen together under one lock, any number of concurrent callers can never
 * get more than the limit through. A reservation can later be released, which removes exactly that reservation's
 * own count, and only from the same window it was counted in. Releasing never clears counts that belong to other
 * callers, and releasing {@link Reservation#NONE} changes nothing.
 *
 * <p>Capacity: at most {@code maxTrackedKeys} keys. Removing expired entries is a full scan under the lock. Both
 * reasons to scan (housekeeping every {@code PURGE_EVERY} reservations, and making room when full) go through one
 * gate, so a limiter scans at most once per {@link #PURGE_INTERVAL} of clock time however many requests arrive.
 * Expired entries therefore free their space at most {@link #PURGE_INTERVAL} after the next request that needs it.
 *
 * <p>Thread safety: all state is guarded by {@code this}. The critical sections are a few map operations, so
 * contention is negligible next to the work being limited (a BCrypt check takes ~250 ms). In memory only: counts
 * are per application instance and are lost on restart.
 */
public final class AttemptLimiter {

    private static final Log log = LogFactory.getLog(AttemptLimiter.class);
    private static final int PURGE_EVERY = 1_000;

    /** Expired entries are scanned for at most this often, for any reason. */
    static final Duration PURGE_INTERVAL = Duration.ofSeconds(1);

    /** What to do when a reservation needs new keys that do not fit. Nothing is counted in either case. */
    public enum OnFull {
        /** Refuse the attempt ({@link Full}). Used for login: the limits must not silently stop applying. */
        REJECT,
        /** Let the attempt through without counting it ({@link Reservation#NONE}). Used for registration. */
        ALLOW_UNTRACKED
    }

    /** One counter to check and count, with its maximum. */
    public record Limit(String key, int max) {
    }

    /** A counted attempt: which keys were counted, and in which window. Pass it to {@link #release} to undo it. */
    public record Reservation(List<Slot> slots) {

        public static final Reservation NONE = new Reservation(List.of());
    }

    public record Slot(String key, Instant windowStart) {
    }

    public sealed interface Result permits Reserved, Blocked, Full {
    }

    public record Reserved(Reservation reservation) implements Result {
    }

    public record Blocked(Duration retryAfter) implements Result {
    }

    /** The table is full and the reservation needed new keys; nothing was counted (only with {@link OnFull#REJECT}). */
    public record Full() implements Result {
    }

    private record Window(Instant start, int count) {
    }

    private final String name;
    private final Clock clock;
    private final Duration window;
    private final int maxTrackedKeys;
    private final Map<String, Window> windows = new HashMap<>();
    private int reservationsSincePurge;
    private boolean capacityWarningLogged;
    private Instant lastPurge;
    private int purgeScans;

    public AttemptLimiter(String name, Clock clock, Duration window, int maxTrackedKeys) {
        this.name = name;
        this.clock = clock;
        this.window = window;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /** Atomically checks every limit and either counts one attempt against every key or counts nothing. */
    public synchronized Result tryReserve(List<Limit> limits, OnFull onFull) {
        Instant now = clock.instant();
        Duration wait = Duration.ZERO;
        for (Limit limit : limits) {
            Window current = current(limit.key(), now);
            if (current != null && current.count() >= limit.max()) {
                Duration remaining = Duration.between(now, current.start().plus(window));
                wait = remaining.compareTo(wait) > 0 ? remaining : wait;
            }
        }
        if (!wait.isZero()) {
            return new Blocked(wait);
        }

        if (++reservationsSincePurge >= PURGE_EVERY) {
            purgeExpiredIfDue(now);   // resets the counter only if it actually scanned
        }
        // Keys absent from the map need new space. (A present-but-expired key reuses its own entry.)
        long newKeys = limits.stream().map(Limit::key).distinct().filter(key -> !windows.containsKey(key)).count();
        if (newKeys > 0 && !hasRoomFor(newKeys, now)) {
            return onFull == OnFull.REJECT ? new Full() : new Reserved(Reservation.NONE);
        }
        List<Slot> slots = new ArrayList<>(limits.size());
        for (Limit limit : limits) {
            Window current = current(limit.key(), now);
            if (current == null) {
                current = new Window(now, 0);
            }
            Window counted = new Window(current.start(), current.count() + 1);
            windows.put(limit.key(), counted);
            slots.add(new Slot(limit.key(), counted.start()));
        }
        return new Reserved(new Reservation(List.copyOf(slots)));
    }

    /**
     * Undoes one reservation: decrements each of its keys by one, but only while that key is still in the window
     * the reservation was counted in. Counts from other reservations are never removed.
     */
    public synchronized void release(Reservation reservation) {
        for (Slot slot : reservation.slots()) {
            Window current = windows.get(slot.key());
            if (current == null || !current.start().equals(slot.windowStart()) || current.count() <= 0) {
                continue;
            }
            if (current.count() == 1) {
                windows.remove(slot.key());
            } else {
                windows.put(slot.key(), new Window(current.start(), current.count() - 1));
            }
        }
    }

    /** For tests and diagnostics: the count currently held for a key (0 if none or expired). */
    synchronized int count(String key) {
        Window current = current(key, clock.instant());
        return current == null ? 0 : current.count();
    }

    synchronized int trackedKeyCount() {
        return windows.size();
    }

    synchronized Set<String> trackedKeys() {
        return Set.copyOf(windows.keySet());
    }

    /** For tests: how many full scans for expired entries have run. */
    synchronized int purgeScans() {
        return purgeScans;
    }

    private Window current(String key, Instant now) {
        Window existing = windows.get(key);
        return existing == null || !now.isBefore(existing.start().plus(window)) ? null : existing;
    }

    /**
     * Whether {@code newKeys} more keys fit. When they do not, expired entries are removed first if the purge
     * gate allows it. The first time the table is found full, a warning is logged (no key).
     */
    private boolean hasRoomFor(long newKeys, Instant now) {
        if (windows.size() + newKeys <= maxTrackedKeys) {
            return true;
        }
        if (purgeExpiredIfDue(now) && windows.size() + newKeys <= maxTrackedKeys) {
            return true;
        }
        if (!capacityWarningLogged) {
            capacityWarningLogged = true;
            log.warn(name + " rate limiter is full (" + maxTrackedKeys + " keys); attempts that need new keys are "
                    + "handled by its capacity policy until older windows expire.");
        }
        return false;
    }

    /**
     * The only way to scan: at most once per {@link #PURGE_INTERVAL}. If the clock moved backwards (e.g. a
     * wall-clock correction), one scan is allowed and the gate restarts from the new time, so it cannot stay
     * closed for the size of the jump.
     */
    private boolean purgeExpiredIfDue(Instant now) {
        boolean due = lastPurge == null || now.isBefore(lastPurge) || !now.isBefore(lastPurge.plus(PURGE_INTERVAL));
        if (!due) {
            return false;
        }
        lastPurge = now;
        reservationsSincePurge = 0;
        purgeExpired(now);
        return true;
    }

    private void purgeExpired(Instant now) {
        purgeScans++;
        windows.values().removeIf(existing -> !now.isBefore(existing.start().plus(window)));
        if (windows.size() < maxTrackedKeys) {
            capacityWarningLogged = false;
        }
    }
}
