package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingAlerter implements Alerter {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingAlerter.class);

    @Override
    public void alert(Level level, String message) {
        switch (level) {
            case INFO -> LOG.info("ALERT {}", message);
            case WARNING -> LOG.warn("ALERT {}", message);
            case CRITICAL -> LOG.error("ALERT {}", message);
        }
    }
}
