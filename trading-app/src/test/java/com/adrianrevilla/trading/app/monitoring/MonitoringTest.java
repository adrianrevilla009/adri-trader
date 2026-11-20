package com.adrianrevilla.trading.app.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.Alerter.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MonitoringTest {
    private static final Instant NOW = Instant.parse("2025-06-18T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2025-06-18");

    private static Snapshot healthy() {
        return new Snapshot(NOW, false, null, NOW.minusSeconds(3600), TODAY.minusDays(1), TODAY.minusDays(1),
                TODAY.minusDays(1), 0, 0, 0, 0, 0);
    }

    private static Snapshot with(java.util.function.UnaryOperator<Snapshot> f) {
        return f.apply(healthy());
    }

    private static List<String> keys(Snapshot s) {
        return WatchdogRules.evaluate(s, TODAY).stream().map(WatchdogRules.Finding::key).toList();
    }

    // ---- rules ----

    @Test
    void aHealthySystemHasNoFindings() {
        assertThat(WatchdogRules.evaluate(healthy(), TODAY)).isEmpty();
    }

    @Test
    void aLongWeekendDoesNotCountAsStaleDataButAWeekWithoutBarsDoes() {
        assertThat(keys(with(s -> new Snapshot(NOW, false, null, NOW, TODAY.minusDays(4), null, null, 0, 0, 0, 0, 0))))
                .doesNotContain("data-stale");
        assertThat(keys(with(s -> new Snapshot(NOW, false, null, NOW, TODAY.minusDays(7), null, null, 0, 0, 0, 0, 0))))
                .contains("data-stale");
    }

    @Test
    void noDataAtAllIsAWarningNotACrash() {
        var s = new Snapshot(NOW, false, null, NOW, null, null, null, 0, 0, 0, 0, 0);
        assertThat(WatchdogRules.evaluate(s, TODAY)).extracting(WatchdogRules.Finding::key).containsExactly("no-data");
    }

    @Test
    void stuckFailedAndUnknownTradingStateAreCritical() {
        var s = new Snapshot(NOW, false, null, NOW, TODAY, TODAY, TODAY, 2, 1, 1, 1, 0);
        var findings = WatchdogRules.evaluate(s, TODAY);
        assertThat(findings).extracting(WatchdogRules.Finding::key)
                .containsExactlyInAnyOrder("trading-day-stuck", "trading-day-failed", "unknown-orders");
        assertThat(findings).allSatisfy(f -> assertThat(f.level()).isEqualTo(Level.CRITICAL));
    }

    @Test
    void aKillSwitchThatStaysOnForADayGetsAReminderButAFreshOneDoesNot() {
        var fresh = new Snapshot(NOW, true, "test", NOW.minusSeconds(60), TODAY, null, null, 0, 0, 0, 0, 0);
        var old = new Snapshot(NOW, true, "test", NOW.minusSeconds(3 * 86400), TODAY, null, null, 0, 0, 0, 0, 0);
        assertThat(keys(fresh)).isEmpty();
        assertThat(keys(old)).containsExactly("kill-switch-long");
    }

    // ---- watchdog: no spam ----

    @Test
    void aLastingProblemAlertsOnceAndSaysResolvedWhenItClears() {
        AtomicReference<Snapshot> state = new AtomicReference<>(
                with(s -> new Snapshot(NOW, false, null, NOW, TODAY, TODAY, TODAY, 0, 0, 1, 0, 0)));
        List<String> alerts = new ArrayList<>();
        var dog = new Watchdog(state::get, (l, m) -> alerts.add(l + ":" + m), Clock.fixed(NOW, ZoneOffset.UTC));
        dog.check();
        dog.check();
        dog.check();
        assertThat(alerts).hasSize(1).first().asString().startsWith("CRITICAL:");
        state.set(healthy());
        dog.check();
        dog.check();
        assertThat(alerts).hasSize(2).last().asString().isEqualTo("INFO:Resolved: trading-day-stuck");
    }

    @Test
    void aNewProblemWhileAnotherLastsIsAlertedSeparately() {
        AtomicReference<Snapshot> state = new AtomicReference<>(
                new Snapshot(NOW, false, null, NOW, TODAY, TODAY, TODAY, 0, 0, 1, 0, 0));
        List<String> alerts = new ArrayList<>();
        var dog = new Watchdog(state::get, (l, m) -> alerts.add(l + ":" + m), Clock.fixed(NOW, ZoneOffset.UTC));
        dog.check();
        state.set(new Snapshot(NOW, false, null, NOW, TODAY, TODAY, TODAY, 0, 1, 1, 0, 0));
        dog.check();
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(1)).contains("unknown outcome");
    }

    @Test
    void aBrokenDatabaseReadIsAlertedOnceAndTheWatchdogKeepsRunning() {
        AtomicReference<Boolean> broken = new AtomicReference<>(true);
        List<String> alerts = new ArrayList<>();
        var dog = new Watchdog(() -> {
            if (broken.get()) {
                throw new IllegalStateException("db down");
            }
            return healthy();
        }, (l, m) -> alerts.add(l + ":" + m), Clock.fixed(NOW, ZoneOffset.UTC));
        dog.check();
        dog.check();
        assertThat(alerts).hasSize(1).first().asString().contains("db down");
        broken.set(false);
        dog.check(); // recovers silently; a later failure alerts again
        broken.set(true);
        dog.check();
        assertThat(alerts).hasSize(2);
    }

    // ---- status server ----

    @Test
    void theStatusServerShowsJsonAndPrometheusMetricsAndRefusesEverythingElse() throws Exception {
        var server = new StatusServer(() -> with(s -> new Snapshot(NOW, true, "manual", NOW, TODAY.minusDays(2),
                TODAY.minusDays(1), TODAY.minusDays(1), 3, 0, 0, 0, 0)), 0);
        server.start();
        try {
            var http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            var status = http.send(HttpRequest.newBuilder(URI.create(base + "/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("\"killSwitchActive\":true").contains("\"openOrders\":3")
                    .contains("\"lastBarDate\":\"2025-06-16\"");
            var metrics = http.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.body()).contains("trading_kill_switch_active 1").contains("trading_open_orders 3")
                    .contains("trading_data_age_days 2").contains("# TYPE trading_open_orders gauge");
            var post = http.send(HttpRequest.newBuilder(URI.create(base + "/status"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(post.statusCode()).isEqualTo(404);
            var other = http.send(HttpRequest.newBuilder(URI.create(base + "/admin")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(other.statusCode()).isEqualTo(404);
        } finally {
            server.stop();
        }
    }

    @Test
    void whenTheStateCannotBeReadTheServerAnswers503NotAStackTrace() throws Exception {
        var server = new StatusServer(() -> {
            throw new IllegalStateException("secret internal detail");
        }, 0);
        server.start();
        try {
            var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).doesNotContain("secret");
        } finally {
            server.stop();
        }
    }
}
