package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroBroker;
import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroConfig;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBrokerRefStore;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcTradingDayLog;
import com.adrianrevilla.trading.application.port.in.ManageMode;
import com.adrianrevilla.trading.application.port.in.RunAgentsDay;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.application.port.out.ArenaJournal;
import com.adrianrevilla.trading.application.port.out.TradingDayLog;
import com.adrianrevilla.trading.application.service.AgentsDayService;
import com.adrianrevilla.trading.application.service.LiveAgentDayFactory;
import com.adrianrevilla.trading.core.application.RateLimiter;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.application.port.out.InstrumentCatalog;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.KillSwitch;
import com.adrianrevilla.trading.core.domain.TradingMode;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.execution.application.ExecutionService;
import com.adrianrevilla.trading.execution.application.Reconciler;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
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
        Map<Isin, Long> ids = etoro.instrumentIds();
        if (!live.isin().isBlank() && !ids.containsKey(new Isin(live.isin()))) {
            throw new IllegalStateException("trading.live.isin " + live.isin()
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
        LOG.info("Live cycle on eToro {}: agents come from the database, max order {}", etoro.mode(),
                etoro.maxOrderAmount());
        return broker; // the whole account; each agent gets its own budget view of it (LiveAgentDayFactory)
    }

    @Bean
    ExecutionService liveExecution(Broker liveBroker, OrderRepository orders, KillSwitch killSwitch, Alerter alerter) {
        return new ExecutionService(liveBroker, orders, killSwitch, alerter, new Reconciler(new BigDecimal("0.000001")));
    }

    /** Every ACTIVE agent of the database runs each day, each with its own strategy, budget and risk limits (ADR-0030). */
    @Bean
    RunAgentsDay runAgentsDay(AgentStore agentStore, StrategyCatalog catalog, Broker liveBroker,
            ExecutionService liveExecution, OrderRepository orders, KillSwitch killSwitch, BarRepository bars,
            TradingDayLog dayLog, ArenaJournal journal, Alerter alerter, EtoroProperties etoro,
            ManageMode modes) {
        Function<Set<Isin>, RiskConfig> riskFor = universe -> {
            RiskConfig d = RiskConfig.defaults(universe);
            // eToro's minimum order replaces the default minimum: smaller orders are dropped, not sent and rejected later
            return new RiskConfig(d.maxWeightPerAsset(), d.maxExposure(), d.maxDailyLoss(), d.agentMaxDrawdown(),
                    d.maxParticipation(), etoro.minOrderAmount(), d.fatFingerPct(), d.whitelist());
        };
        var factory = new LiveAgentDayFactory(catalog, bars, liveExecution, orders, liveBroker, killSwitch, dayLog,
                journal, alerter, riskFor, etoro.instrumentIds().keySet(), "XETRA");
        return new AgentsDayService(agentStore, factory, alerter, () -> TradingMode.valueOf(modes.current().mode()));
    }

    /**
     * Migration path of the single-agent cycle (ADR-0028): while the store has no agent and trading.live.isin is set,
     * the first agent is created from trading.live.*. Agents created later come from the API.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void seedFirstAgent(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        var live = ctx.getBean(LiveProperties.class);
        var store = ctx.getBean(AgentStore.class);
        if (live.isin().isBlank() || !store.findAll().isEmpty()) {
            return;
        }
        var policy = ctx.getBean(CapitalPolicy.class);
        try {
            store.save(AgentConfig.create(live.agentId(), live.strategy(), Map.of(), List.of(new Isin(live.isin())),
                    live.capital(), List.of(), TradingMode.DEMO, policy), "startup-seed");
            LOG.info("First agent {} created from trading.live.* ({} on {}, capital {})", live.agentId(),
                    live.strategy(), live.isin(), live.capital());
        } catch (IllegalArgumentException e) {
            LOG.warn("The first agent was not created from trading.live.*: {}", e.getMessage());
        }
    }

    @Bean
    ScheduledLiveDay scheduledLiveDay(RunAgentsDay job, Clock clock, LiveProperties live) {
        return new ScheduledLiveDay(job, clock, live.startupDay());
    }

    public static class ScheduledLiveDay {
        private final RunAgentsDay job;
        private final Clock clock;

        private final LocalDate startupDay;

        ScheduledLiveDay(RunAgentsDay job, Clock clock, LocalDate startupDay) {
            this.job = job;
            this.clock = clock;
            this.startupDay = startupDay;
        }

        private static void log(LocalDate day, String when, List<RunAgentsDay.AgentResult> results) {
            if (results.isEmpty()) {
                LOG.info("Live day {}{}: no active agents", day, when);
            }
            results.forEach(r -> LOG.info("Live day {}{}: agent {} {} ({})", day, when, r.agentId(),
                    r.result().outcome(), r.result().detail()));
        }

        /**
         * Optional single run of a given day when the app is ready (after the ingestion, which has order 1). It uses the
         * same job, so the day is claimed in the database and can only run once.
         */
        @EventListener(ApplicationReadyEvent.class)
        @Order(2)
        public void onReady() {
            if (startupDay != null) {
                log(startupDay, " (on startup)", job.run(startupDay));
            }
        }

        /** After the ingestion (19:00); the decision uses the close of today, orders wait for the next open. */
        // nosemgrep: java-scheduled-without-lock -- exactly one instance runs by design (ADR-0009/0026) and the day is claimed atomically in the database (trading_day), so a distributed lock adds nothing
        @Scheduled(cron = "${trading.live.cron:0 15 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            LocalDate day = LocalDate.now(clock.withZone(BERLIN));
            log(day, "", job.run(day));
        }
    }
}
