package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.in.web.StatusServer;
import com.adrianrevilla.trading.adapter.out.metrics.BusinessMetrics;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBusinessReadModel;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcMonitoringQueries;
import com.adrianrevilla.trading.application.port.in.CheckSystemHealth;
import com.adrianrevilla.trading.application.port.out.BusinessReadModel;
import com.adrianrevilla.trading.application.port.out.MonitoringQueries;
import com.adrianrevilla.trading.application.service.Watchdog;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class MonitoringConfig {

    @Bean
    MonitoringQueries monitoringQueries(DataSource dataSource, Clock clock) {
        return new JdbcMonitoringQueries(dataSource, clock);
    }

    @Bean
    CheckSystemHealth watchdog(MonitoringQueries queries, Alerter alerter, Clock clock) {
        return new Watchdog(queries::snapshot, alerter, clock);
    }

    /** Runs the watchdog every 30 minutes (first run 1 minute after start). */
    @Bean
    @ConditionalOnProperty(name = "trading.monitoring.watchdog-enabled", havingValue = "true", matchIfMissing = true)
    ScheduledWatchdog scheduledWatchdog(CheckSystemHealth watchdog) {
        return new ScheduledWatchdog(watchdog);
    }

    public static class ScheduledWatchdog {
        private final CheckSystemHealth watchdog;

        ScheduledWatchdog(CheckSystemHealth watchdog) {
            this.watchdog = watchdog;
        }

        @Scheduled(initialDelayString = "${trading.monitoring.initial-delay-ms:60000}",
                fixedDelayString = "${trading.monitoring.interval-ms:1800000}")
        public void run() {
            watchdog.check();
        }
    }

    /** Reads the business metrics from the database every 30 seconds (first read 5 seconds after start). */
    @Bean
    BusinessReadModel businessReadModel(DataSource dataSource) {
        return new JdbcBusinessReadModel(dataSource);
    }

    @Bean
    BusinessMetrics businessMetrics(BusinessReadModel readModel, MonitoringQueries queries, MeterRegistry registry,
            Clock clock, @Value("${trading.monitoring.backup-marker:}") String backupMarker) {
        return new BusinessMetrics(readModel, queries, registry, clock,
                backupMarker.isBlank() ? null : java.nio.file.Path.of(backupMarker));
    }

    @Bean
    @ConditionalOnProperty(name = "trading.monitoring.watchdog-enabled", havingValue = "true", matchIfMissing = true)
    ScheduledMetrics scheduledMetrics(BusinessMetrics metrics) {
        return new ScheduledMetrics(metrics);
    }

    public static class ScheduledMetrics {
        private final BusinessMetrics metrics;

        ScheduledMetrics(BusinessMetrics metrics) {
            this.metrics = metrics;
        }

        @Scheduled(initialDelayString = "${trading.monitoring.metrics-initial-delay-ms:5000}",
                fixedDelayString = "${trading.monitoring.metrics-interval-ms:30000}")
        public void run() {
            metrics.refresh();
        }
    }

    /**
     * Read-only status page. Loopback by default; the container sets trading.monitoring.bind-address=0.0.0.0 and the
     * port is only reachable on the private compose network. Port 0 picks a free one; a negative port disables it.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${trading.monitoring.port:8098} >= 0")
    StatusServer statusServer(MonitoringQueries queries, ObjectProvider<PrometheusMeterRegistry> prometheus,
            @Value("${trading.monitoring.port:8098}") int port,
            @Value("${trading.monitoring.bind-address:127.0.0.1}") String bindAddress) throws IOException {
        PrometheusMeterRegistry registry = prometheus.getIfAvailable();
        return new StatusServer(queries::snapshot, port, bindAddress, registry == null ? null : registry::scrape);
    }
}
