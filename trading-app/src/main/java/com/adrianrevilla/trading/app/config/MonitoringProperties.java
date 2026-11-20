package com.adrianrevilla.trading.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Status page, watchdog and metrics (prefix {@code trading.monitoring}).
 *
 * @param port a negative port disables the status page; 0 picks a free one
 * @param bindAddress loopback by default; the container sets 0.0.0.0 and never publishes the port
 * @param backupMarker file the backup job touches after each verified dump; empty means backups are not exported
 */
@ConfigurationProperties("trading.monitoring")
public record MonitoringProperties(
        @DefaultValue("8098") int port,
        @DefaultValue("127.0.0.1") String bindAddress,
        @DefaultValue("") String backupMarker,
        @DefaultValue("true") boolean watchdogEnabled) {

    public MonitoringProperties {
        if (port > 65535) {
            throw new IllegalArgumentException("trading.monitoring.port out of range: " + port);
        }
    }

    public boolean statusPageEnabled() {
        return port >= 0;
    }
}
