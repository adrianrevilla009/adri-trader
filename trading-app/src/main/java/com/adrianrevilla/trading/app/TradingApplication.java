package com.adrianrevilla.trading.app;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }

    /** Injectable clock, always UTC (ADR-0013); never call Instant.now() directly in domain code. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
