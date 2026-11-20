package com.adrianrevilla.trading.adapter.out.metrics;

import com.adrianrevilla.trading.application.port.out.BusinessReadModel;
import com.adrianrevilla.trading.application.port.out.MonitoringQueries;
import com.adrianrevilla.trading.application.read.Snapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * Outbound adapter: publishes what happens with the money and the pipeline as Prometheus metrics. It only translates
 * what the read ports return into Micrometer meters; it has no SQL and no business rules.
 *
 * <p>The values are read on a fixed schedule and cached, so scraping never queries the database and a slow or broken
 * database cannot stall a scrape: the previous values stay, and {@code trading_metrics_refresh_age_seconds} grows so an
 * alert can fire. Amounts are only exposed on the internal network (see docs/observability.md).
 */
public class BusinessMetrics {
    private final BusinessReadModel readModel;
    private final MonitoringQueries monitoring;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>();
    private final Counter refreshFailures;
    // Micrometer gauges hold their state object weakly: it must stay referenced from a field or the gauge reads NaN after a GC
    private final Path backupMarker;

    private final MultiGauge equity;
    private final MultiGauge drawdown;
    private final MultiGauge agentStatus;
    private final MultiGauge cash;
    private final MultiGauge invested;
    private final MultiGauge orders;
    private final MultiGauge fills;
    private final MultiGauge fillNotional;
    private final MultiGauge fees;

    public BusinessMetrics(BusinessReadModel readModel, MonitoringQueries monitoring, MeterRegistry registry, Clock clock) {
        this(readModel, monitoring, registry, clock, null);
    }

    /** @param backupMarker file the backup job touches after each verified dump; null when backups are not mounted */
    public BusinessMetrics(BusinessReadModel readModel, MonitoringQueries monitoring, MeterRegistry registry, Clock clock,
            Path backupMarker) {
        this.readModel = readModel;
        this.monitoring = monitoring;
        this.clock = clock;
        this.backupMarker = backupMarker;

        snap(registry, "trading_kill_switch_active", "1 when the kill switch is on", s -> s.killSwitchActive() ? 1 : 0);
        snap(registry, "trading_open_orders", "orders submitted or partially filled", Snapshot::openOrders);
        snap(registry, "trading_unknown_orders_stale", "orders of unknown outcome older than a day",
                Snapshot::staleUnknownOrders);
        snap(registry, "trading_days_stuck", "trading days started and never finished", Snapshot::stuckTradingDays);
        snap(registry, "trading_days_failed_3d", "trading days that failed in the last 3 days",
                Snapshot::failedTradingDays);
        snap(registry, "trading_data_issues_critical_30d", "critical data issues in the last 30 days",
                Snapshot::criticalIssues30d);
        snap(registry, "trading_data_age_days", "age in days of the newest clean bar (-1 when no data)",
                s -> ageDays(s.lastBarDate(), s));
        snap(registry, "trading_arena_age_days", "days since the last arena day was recorded (-1 when never)",
                s -> ageDays(s.lastArenaDay(), s));
        snap(registry, "trading_last_trading_day_age_days", "days since a trading agent last ran (-1 when never)",
                s -> ageDays(s.lastTradingDay(), s));
        Gauge.builder("trading_metrics_refresh_age_seconds", lastRefresh, r -> {
            Instant t = r.get();
            return t == null ? Double.NaN : Duration.between(t, this.clock.instant()).toSeconds();
        }).description("seconds since the business metrics were last read from the database").register(registry);
        if (backupMarker != null) {
            Gauge.builder("trading_backup_age_seconds", this.backupMarker, p -> {
                try {
                    return Duration.between(Files.getLastModifiedTime(p).toInstant(), this.clock.instant()).toSeconds();
                } catch (IOException e) {
                    return Double.NaN; // no verified backup yet
                }
            }).description("seconds since the last verified database backup (NaN when none exists)").register(registry);
        }
        this.refreshFailures = Counter.builder("trading_metrics_refresh_failures_total")
                .description("failed reads of the business metrics").register(registry);

        this.equity = MultiGauge.builder("trading_agent_equity_eur").description("latest equity of an arena agent")
                .register(registry);
        this.drawdown = MultiGauge.builder("trading_agent_drawdown_ratio")
                .description("1 - latest equity / highest equity so far, per arena agent").register(registry);
        this.agentStatus = MultiGauge.builder("trading_agent_status")
                .description("1 for the current lifecycle status of an arena agent").register(registry);
        this.cash = MultiGauge.builder("trading_cash_eur").description("cash per agent from the ledger")
                .register(registry);
        this.invested = MultiGauge.builder("trading_invested_cost_eur")
                .description("net amount bought minus sold per agent (cost, not market value)").register(registry);
        this.orders = MultiGauge.builder("trading_orders").description("orders per agent and state").register(registry);
        this.fills = MultiGauge.builder("trading_fills").description("cumulative fills per agent").register(registry);
        this.fillNotional = MultiGauge.builder("trading_fill_notional_eur")
                .description("cumulative traded amount per agent").register(registry);
        this.fees = MultiGauge.builder("trading_fees_eur").description("cumulative fees per agent").register(registry);
    }

    private void snap(MeterRegistry registry, String name, String help, ToDoubleFunction<Snapshot> value) {
        Gauge.builder(name, snapshot, ref -> {
            Snapshot s = ref.get();
            return s == null ? Double.NaN : value.applyAsDouble(s);
        }).description(help).register(registry);
    }

    private static double ageDays(LocalDate date, Snapshot s) {
        return date == null ? -1 : ChronoUnit.DAYS.between(date, LocalDate.ofInstant(s.takenAt(), ZoneOffset.UTC));
    }

    /** Reads everything again. Never throws: a failure is counted and the previous values are kept. */
    public void refresh() {
        try {
            Snapshot s = monitoring.snapshot();

            List<MultiGauge.Row<?>> eq = new ArrayList<>();
            List<MultiGauge.Row<?>> dd = new ArrayList<>();
            List<MultiGauge.Row<?>> st = new ArrayList<>();
            for (BusinessReadModel.AgentEquity a : readModel.agentEquity()) {
                Tags t = Tags.of("agent", a.agentId());
                eq.add(MultiGauge.Row.of(t, a.equity().doubleValue()));
                dd.add(MultiGauge.Row.of(t, a.drawdown()));
                st.add(MultiGauge.Row.of(Tags.of("agent", a.agentId(), "status", a.status()), 1));
            }
            equity.register(eq, true);
            drawdown.register(dd, true);
            agentStatus.register(st, true);

            registerAmounts(cash, readModel.cashByAgent());
            registerAmounts(invested, readModel.investedCostByAgent());
            registerAmounts(fillNotional, readModel.tradedAmountByAgent());
            registerAmounts(fees, readModel.feesByAgent());
            List<MultiGauge.Row<?>> fl = new ArrayList<>();
            readModel.fillsByAgent().forEach((agent, n) -> fl.add(MultiGauge.Row.of(Tags.of("agent", agent), n)));
            fills.register(fl, true);

            List<MultiGauge.Row<?>> ord = new ArrayList<>();
            for (BusinessReadModel.OrderCount c : readModel.ordersByAgentAndState()) {
                ord.add(MultiGauge.Row.of(Tags.of("agent", c.agentId(), "state", c.state()), c.count()));
            }
            orders.register(ord, true);

            snapshot.set(s);
            lastRefresh.set(clock.instant());
        } catch (RuntimeException e) {
            refreshFailures.increment();
        }
    }

    private static void registerAmounts(MultiGauge gauge, Map<String, BigDecimal> byAgent) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        byAgent.forEach((agent, v) -> rows.add(MultiGauge.Row.of(Tags.of("agent", agent), v.doubleValue())));
        gauge.register(rows, true);
    }

}
