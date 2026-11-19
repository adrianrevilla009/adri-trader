package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.KillSwitch;
import com.adrianrevilla.trading.execution.OrderRepository;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutionConfig {

    /** Telegram when a token is configured, otherwise the log. The token only comes from the environment. */
    @Bean
    Alerter alerter(@Value("${trading.telegram.bot-token:}") String token,
            @Value("${trading.telegram.chat-id:}") String chatId,
            @Value("${trading.telegram.base-url:https://api.telegram.org}") String baseUrl) {
        return token.isBlank() || chatId.isBlank() ? new LoggingAlerter() : new TelegramAlerter(baseUrl, token, chatId);
    }

    @Bean
    KillSwitch killSwitch(DataSource dataSource) {
        return new JdbcKillSwitch(dataSource);
    }

    @Bean
    OrderRepository orderRepository(DataSource dataSource) {
        return new JdbcOrderRepository(dataSource);
    }
}
