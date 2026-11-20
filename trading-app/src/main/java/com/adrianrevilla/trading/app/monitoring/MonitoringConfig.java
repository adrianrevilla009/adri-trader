package com.adrianrevilla.trading.app.monitoring;

import com.adrianrevilla.trading.core.Alerter;
import java.io.IOException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class MonitoringConfig {

    @Bean
    MonitoringRepository monitoringRepository(DataSource dataSource, Clock clock) {
        return new MonitoringRepository(dataSource, clock);
    }

    @Bean
    Watchdog watchdog(MonitoringRepository repository, Alerter alerter, Clock clock) {
        return new Watchdog(repository::snapshot, alerter, clock);
    }

    /** Runs the watchdog every 30 minutes (first run 1 minute after start). */
    @Bean
    @ConditionalOnProperty(name = "trading.monitoring.watchdog-enabled", havingValue = "true", matchIfMissing = true)
    ScheduledWatchdog scheduledWatchdog(Watchdog watchdog) {
        return new ScheduledWatchdog(watchdog);
    }

    public static class ScheduledWatchdog {
        private final Watchdog watchdog;

        ScheduledWatchdog(Watchdog watchdog) {
            this.watchdog = watchdog;
        }

        @Scheduled(initialDelayString = "${trading.monitoring.initial-delay-ms:60000}",
                fixedDelayString = "${trading.monitoring.interval-ms:1800000}")
        public void run() {
            watchdog.check();
        }
    }

    /** Read-only status page on the loopback address. Port 0 picks a free one; a negative port disables it. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${trading.monitoring.port:8098} >= 0")
    StatusServer statusServer(MonitoringRepository repository, @Value("${trading.monitoring.port:8098}") int port)
            throws IOException {
        return new StatusServer(repository::snapshot, port);
    }
}
