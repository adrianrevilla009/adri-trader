package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroBroker;
import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroConfig;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBrokerRefStore;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcTradingDayLog;
import com.adrianrevilla.trading.application.port.in.RunTradingDay;
import com.adrianrevilla.trading.application.port.out.ArenaJournal;
import com.adrianrevilla.trading.application.port.out.TradingDayLog;
import com.adrianrevilla.trading.application.service.TradingDayService;
import com.adrianrevilla.trading.core.application.RateLimiter;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.application.port.out.InstrumentCatalog;
import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.CashCappedBroker;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.KillSwitch;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.execution.application.ExecutionService;
import com.adrianrevilla.trading.execution.application.Reconciler;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import com.adrianrevilla.trading.execution.domain.RealTradingGate;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.risk.domain.RiskManager;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Wiring only (ADR-0025): the daily cycle against the eToro DEMO account. Created only when trading.live.enabled. */
@Configuration
@ConditionalOnProperty(name = "trading.live.enabled", havingValue = "true")
public class LiveConfig {
    private static final Logger LOG = LoggerFactory.getLogger(LiveConfig.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Bean
    TradingDayLog tradingDayLog(DataSource dataSource) {
        return new JdbcTradingDayLog(dataSource);
    }

    @Bean
    Broker liveBroker(EtoroProperties etoro, LiveProperties live, DataSource dataSource) {
        if (!etoro.hasKeys()) {
            throw new IllegalStateException("trading.live.enabled needs ETORO_API_KEY and ETORO_USER_KEY");
        }
        Isin isin = new Isin(live.isin());
        Map<Isin, Long> ids = etoro.instrumentIds();
        if (!ids.containsKey(isin)) {
            throw new IllegalStateException("trading.live.isin " + isin.value()
                    + " has no eToro id in trading.etoro.instruments");
        }
        Map<Long, Isin> byId = new java.util.HashMap<>();
        ids.forEach((i, id) -> byId.put(id, i));
        InstrumentCatalog catalog = new InstrumentCatalog() {
            @Override
            public Optional<Long> brokerId(Isin i) {
                return Optional.ofNullable(ids.get(i));
            }

            @Override
            public Optional<Isin> isin(long id) {
                return Optional.ofNullable(byId.get(id));
            }
        };
        var config = new EToroConfig(etoro.baseUrl(), etoro.apiKey(), etoro.userKey(), etoro.mode(),
                EToroBroker.defaultTimeout(), EToroConfig.Endpoints.defaults(), etoro.maxOrderAmount());
        var broker = new EToroBroker(config, catalog, new JdbcBrokerRefStore(dataSource),
                RateLimiter.perMinute(etoro.maxReadsPerMinute()), RateLimiter.perMinute(etoro.maxWritesPerMinute()),
                Thread::sleep);
        LOG.info("Live cycle on eToro {} for agent {}: capital {}, max order {}", etoro.mode(), live.agentId(),
                live.capital(), etoro.maxOrderAmount());
        return new CashCappedBroker(broker, live.capital());
    }

    @Bean
    ExecutionService liveExecution(Broker liveBroker, OrderRepository orders, KillSwitch killSwitch, Alerter alerter) {
        return new ExecutionService(liveBroker, orders, killSwitch, alerter, new Reconciler(new BigDecimal("0.000001")));
    }

    @Bean
    RunTradingDay runTradingDay(LiveProperties live, Broker liveBroker, ExecutionService liveExecution,
            OrderRepository orders, KillSwitch killSwitch, BarRepository bars, TradingDayLog dayLog,
            ArenaJournal journal, Alerter alerter) {
        Isin isin = new Isin(live.isin());
        var risk = RiskManager.standard(RiskConfig.defaults(Set.of(isin)), killSwitch);
        return new TradingDayService(live.agentId(), new BuyAndHold(live.agentId(), isin), List.of(isin), "XETRA", bars,
                liveExecution, orders, liveBroker, killSwitch, risk, dayLog, journal, alerter, false,
                RealTradingGate.closed());
    }

    @Bean
    ScheduledLiveDay scheduledLiveDay(RunTradingDay job, Clock clock) {
        return new ScheduledLiveDay(job, clock);
    }

    public static class ScheduledLiveDay {
        private final RunTradingDay job;
        private final Clock clock;

        ScheduledLiveDay(RunTradingDay job, Clock clock) {
            this.job = job;
            this.clock = clock;
        }

        /** After the ingestion (19:00); the decision uses the close of today, orders wait for the next open. */
        // nosemgrep: java-scheduled-without-lock -- exactly one instance runs by design (ADR-0009/0026) and the day is claimed atomically in the database (trading_day), so a distributed lock adds nothing
        @Scheduled(cron = "${trading.live.cron:0 15 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            LocalDate day = LocalDate.now(clock.withZone(BERLIN));
            var result = job.run(day);
            LOG.info("Live day {}: {} ({})", day, result.outcome(), result.detail());
        }
    }
}
