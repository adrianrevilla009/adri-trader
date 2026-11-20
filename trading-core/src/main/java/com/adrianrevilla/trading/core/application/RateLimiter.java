package com.adrianrevilla.trading.core.application;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window limiter (N calls per window). eToro allows 60 reads and 20 writes per minute and key, and the
 * terms for autonomous tools require our own rate limiting.
 */
public final class RateLimiter {
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final int maxCalls;
    private final long windowMillis;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Deque<Long> calls = new ArrayDeque<>();

    public RateLimiter(int maxCalls, long windowMillis, Clock clock, Sleeper sleeper) {
        this.maxCalls = maxCalls;
        this.windowMillis = windowMillis;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static RateLimiter perMinute(int maxCalls) {
        return new RateLimiter(maxCalls, 60_000, Clock.systemUTC(), Thread::sleep);
    }

    /** Blocks until a call is allowed. */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            long now = clock.millis();
            while (!calls.isEmpty() && now - calls.peekFirst() >= windowMillis) {
                calls.pollFirst();
            }
            if (calls.size() < maxCalls) {
                calls.addLast(now);
                return;
            }
            sleeper.sleep(Math.max(1, windowMillis - (now - calls.peekFirst())));
        }
    }
}
