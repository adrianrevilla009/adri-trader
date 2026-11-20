package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.data.BarRepository;
import com.adrianrevilla.trading.data.CsvDirectoryBarSource;
import com.adrianrevilla.trading.data.DataQualityValidator;
import com.adrianrevilla.trading.data.IngestionService;
import com.adrianrevilla.trading.data.TradingCalendar;
import com.adrianrevilla.trading.data.XetraCalendar;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
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
        return new BarRepository(dataSource);
    }

    @Bean
    IngestionService ingestionService(BarRepository repository, TradingCalendar calendar) {
        return new IngestionService(repository, new DataQualityValidator(calendar));
    }

    /** Disabled by default: needs a configured data directory and universe. */
    @Bean
    @ConditionalOnProperty(name = "trading.ingestion.enabled", havingValue = "true")
    ScheduledIngestion scheduledIngestion(IngestionService service, Alerter alerter,
            @Value("${trading.ingestion.source:csv}") String sourceName,
            @Value("${trading.ingestion.csv-dir:}") String csvDir,
            @Value("${trading.ingestion.yahoo-base-url:https://query1.finance.yahoo.com}") String yahooUrl,
            @Value("${trading.ingestion.yahoo-tickers:}") List<String> yahooTickers,
            @Value("${trading.ingestion.from-year:2010}") int fromYear,
            @Value("${trading.ingestion.run-on-startup:false}") boolean runOnStartup,
            @Value("${trading.ingestion.isins}") List<String> isins, java.time.Clock clock) {
        com.adrianrevilla.trading.data.BarSource source;
        if (sourceName.equals("yahoo")) {
            // entries look like IE00B4L5Y983=EUNL.DE
            java.util.Map<Isin, String> tickers = new java.util.HashMap<>();
            yahooTickers.stream().filter(e -> !e.isBlank()).forEach(e -> {
                String[] kv = e.split("=", 2);
                tickers.put(new Isin(kv[0].trim()), kv[1].trim());
            });
            source = new com.adrianrevilla.trading.data.YahooBarSource(yahooUrl, tickers, fromYear,
                    java.time.LocalDate.now(clock));
        } else {
            source = new CsvDirectoryBarSource("csv", Path.of(csvDir));
        }
        var job = new DailyIngestionJob(service, source, isins.stream().map(Isin::new).toList(), "XETRA", alerter, 2000, clock, 30);
        return new ScheduledIngestion(job, runOnStartup);
    }

    @Bean
    JdbcArenaJournal arenaJournal(DataSource dataSource) {
        return new JdbcArenaJournal(dataSource);
    }

    /** Disabled by default: needs the universe and the benchmark. Runs after the ingestion. */
    @Bean
    @ConditionalOnProperty(name = "trading.arena.enabled", havingValue = "true")
    ScheduledArena scheduledArena(BarRepository repository, JdbcArenaJournal journal, Alerter alerter,
            @Value("${trading.ingestion.isins}") List<String> isins,
            @Value("${trading.arena.benchmark}") String benchmark,
            @Value("${trading.arena.capital:100}") java.math.BigDecimal capital,
            @Value("${trading.arena.report-dir:reports/arena}") String reportDir,
            @Value("${trading.arena.start-date:}") String startDate) {
        var job = new ArenaDailyJob(repository, isins.stream().map(Isin::new).toList(), new Isin(benchmark), "XETRA",
                capital, journal, alerter, Path.of(reportDir),
                startDate.isBlank() ? null : java.time.LocalDate.parse(startDate));
        return new ScheduledArena(job);
    }

    public static class ScheduledArena {
        private final ArenaDailyJob job;

        ScheduledArena(ArenaDailyJob job) {
            this.job = job;
        }

        @Scheduled(cron = "${trading.arena.cron:0 30 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            job.run();
        }
    }

    public static class ScheduledIngestion {
        private final DailyIngestionJob job;
        private final boolean runOnStartup;

        ScheduledIngestion(DailyIngestionJob job, boolean runOnStartup) {
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
        @Scheduled(cron = "${trading.ingestion.cron:0 0 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            job.run();
        }
    }
}
