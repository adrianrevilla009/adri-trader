package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.out.marketdata.CsvDirectoryBarSource;
import com.adrianrevilla.trading.adapter.out.marketdata.YahooBarSource;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcArenaJournal;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBarRepository;
import com.adrianrevilla.trading.application.port.in.IngestMarketData;
import com.adrianrevilla.trading.application.port.in.RunArenaDay;
import com.adrianrevilla.trading.application.port.out.ArenaJournal;
import com.adrianrevilla.trading.application.service.ArenaDayService;
import com.adrianrevilla.trading.application.service.DailyIngestionService;
import com.adrianrevilla.trading.arena.adapter.out.report.FileArenaReportPublisher;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.IngestionService;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.data.application.port.out.BarSource;
import com.adrianrevilla.trading.data.domain.DataQualityValidator;
import com.adrianrevilla.trading.data.domain.TradingCalendar;
import com.adrianrevilla.trading.data.domain.XetraCalendar;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class DataConfig {

    @Bean
    TradingCalendar xetraCalendar() {
        return new XetraCalendar();
    }

    @Bean
    BarRepository barRepository(DataSource dataSource) {
        return new JdbcBarRepository(dataSource);
    }

    @Bean
    IngestionService ingestionService(BarRepository repository, TradingCalendar calendar) {
        return new IngestionService(repository, new DataQualityValidator(calendar));
    }

    /** Disabled by default: needs a configured data directory and universe. */
    @Bean
    @ConditionalOnProperty(name = "trading.ingestion.enabled", havingValue = "true")
    ScheduledIngestion scheduledIngestion(IngestionService service, Alerter alerter, IngestionProperties props,
            java.time.Clock clock) {
        BarSource source;
        if (props.source().equals("yahoo")) {
            // entries look like IE00B4L5Y983=EUNL.DE
            java.util.Map<Isin, String> tickers = new java.util.HashMap<>();
            props.yahooTickers().stream().filter(e -> !e.isBlank()).forEach(e -> {
                String[] kv = e.split("=", 2);
                tickers.put(new Isin(kv[0].trim()), kv[1].trim());
            });
            source = new YahooBarSource(props.yahooBaseUrl(), tickers, props.fromYear(),
                    java.time.LocalDate.now(clock));
        } else {
            source = new CsvDirectoryBarSource("csv", Path.of(props.csvDir()));
        }
        IngestMarketData job = new DailyIngestionService(service, source, props.isins().stream().map(Isin::new).toList(), "XETRA", alerter, 2000, clock, 30);
        return new ScheduledIngestion(job, props.runOnStartup());
    }

    @Bean
    ArenaJournal arenaJournal(DataSource dataSource) {
        return new JdbcArenaJournal(dataSource);
    }

    /** Disabled by default: needs the universe and the benchmark. Runs after the ingestion. */
    @Bean
    @ConditionalOnProperty(name = "trading.arena.enabled", havingValue = "true")
    ScheduledArena scheduledArena(BarRepository repository, ArenaJournal journal, Alerter alerter,
            IngestionProperties ingestion, ArenaProperties props) {
        RunArenaDay job = new ArenaDayService(repository, ingestion.isins().stream().map(Isin::new).toList(),
                new Isin(props.benchmark()), "XETRA", props.capital(), journal, alerter,
                new FileArenaReportPublisher(Path.of(props.reportDir())), props.start());
        return new ScheduledArena(job);
    }

    public static class ScheduledArena {
        private final RunArenaDay job;

        ScheduledArena(RunArenaDay job) {
            this.job = job;
        }

        // nosemgrep: java-scheduled-without-lock -- exactly one instance runs by design (ADR-0009/0026) and the day is claimed atomically in the database (trading_day), so a distributed lock adds nothing
        @Scheduled(cron = "${trading.arena.cron:0 30 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            job.run();
        }
    }

    public static class ScheduledIngestion {
        private final IngestMarketData job;
        private final boolean runOnStartup;

        ScheduledIngestion(IngestMarketData job, boolean runOnStartup) {
            this.job = job;
            this.runOnStartup = runOnStartup;
        }

        /** One-off backfill: set trading.ingestion.run-on-startup=true for the first run. */
        @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
        public void onReady() {
            if (runOnStartup) {
                job.run();
            }
        }

        /** After the Xetra close (17:30 CET), Monday to Friday. */
        // nosemgrep: java-scheduled-without-lock -- exactly one instance runs by design (ADR-0009/0026) and the day is claimed atomically in the database (trading_day), so a distributed lock adds nothing
        @Scheduled(cron = "${trading.ingestion.cron:0 0 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            job.run();
        }
    }
}
