package com.adrianrevilla.trading.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test moves by hand; sleeping advances it, so no real time passes. */
    private static final class ManualClock extends Clock {
        final AtomicLong now = new AtomicLong(1_000);

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }

    @Test
    void callsUpToTheLimitPassWithoutWaiting() throws Exception {
        var clock = new ManualClock();
        List<Long> sleeps = new ArrayList<>();
        var limiter = new RateLimiter(3, 1_000, clock, sleeps::add);
        for (int i = 0; i < 3; i++) {
            limiter.acquire();
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void theCallBeyondTheLimitWaitsUntilTheOldestLeavesTheWindow() throws Exception {
        var clock = new ManualClock();
        List<Long> sleeps = new ArrayList<>();
        var limiter = new RateLimiter(2, 1_000, clock, ms -> {
            sleeps.add(ms);
            clock.now.addAndGet(ms);
        });
        limiter.acquire();
        clock.now.addAndGet(100);
        limiter.acquire();
        limiter.acquire(); // third one: has to wait 900 ms, until the first call is 1000 ms old
        assertThat(sleeps).containsExactly(900L);
    }

    @Test
    void theWindowSlidesSoOldCallsStopCounting() throws Exception {
        var clock = new ManualClock();
        List<Long> sleeps = new ArrayList<>();
        var limiter = new RateLimiter(1, 1_000, clock, sleeps::add);
        limiter.acquire();
        clock.now.addAndGet(1_000);
        limiter.acquire();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void underConcurrencyNoWindowEverHoldsMoreCallsThanTheLimit() throws Exception {
        int limit = 5;
        long window = 200;
        var limiter = new RateLimiter(limit, window, Clock.systemUTC(), Thread::sleep);
        List<Long> stamps = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < 6; t++) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < 5; i++) {
                    limiter.acquire();
                    stamps.add(System.nanoTime() / 1_000_000);
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(stamps).hasSize(30);
        List<Long> sorted = new ArrayList<>(stamps);
        Collections.sort(sorted);
        for (int i = 0; i + limit < sorted.size(); i++) {
            // the (limit+1)-th call after any call must come at least one window later (1 ms of clock tolerance)
            assertThat(sorted.get(i + limit) - sorted.get(i)).isGreaterThanOrEqualTo(window - 1);
        }
    }
}
