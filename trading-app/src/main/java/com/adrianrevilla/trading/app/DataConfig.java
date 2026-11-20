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
            @Value("${trading.ingestion.csv-dir}") String csvDir,
            @Value("${trading.ingestion.isins}") List<String> isins) {
        var job = new DailyIngestionJob(service, new CsvDirectoryBarSource("csv", Path.of(csvDir)),
                isins.stream().map(Isin::new).toList(), "XETRA", alerter, 2000);
        return new ScheduledIngestion(job);
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
            @Value("${trading.arena.report-dir:reports/arena}") String reportDir) {
        var job = new ArenaDailyJob(repository, isins.stream().map(Isin::new).toList(), new Isin(benchmark), "XETRA",
                capital, journal, alerter, Path.of(reportDir));
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

        ScheduledIngestion(DailyIngestionJob job) {
            this.job = job;
        }

        /** After the Xetra close (17:30 CET), Monday to Friday. */
        @Scheduled(cron = "${trading.ingestion.cron:0 0 19 * * MON-FRI}", zone = "Europe/Berlin")
        public void run() {
            job.run();
        }
    }
}
