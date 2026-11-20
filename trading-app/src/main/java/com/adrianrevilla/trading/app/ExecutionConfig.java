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

    /** Only created when KILL_SWITCH_TOKEN is set (16+ characters); listens on the loopback address only. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "!'${trading.killswitch.token:}'.isEmpty()")
    KillSwitchServer killSwitchServer(KillSwitch killSwitch, Alerter alerter,
            @Value("${trading.killswitch.token}") String token,
            @Value("${trading.killswitch.port:8099}") int port) throws java.io.IOException {
        return new KillSwitchServer(killSwitch, token, port, alerter);
    }

    /** Only when the Telegram token and chat are configured. Polls every few seconds. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "!'${trading.telegram.bot-token:}'.isEmpty() && !'${trading.telegram.chat-id:}'.isEmpty()")
    TelegramCommands telegramCommands(KillSwitch killSwitch, Alerter alerter,
            @Value("${trading.telegram.bot-token}") String token, @Value("${trading.telegram.chat-id}") String chatId,
            @Value("${trading.telegram.base-url:https://api.telegram.org}") String baseUrl) {
        return new TelegramCommands(new TelegramCommandListener(baseUrl, token, chatId, killSwitch, alerter));
    }

    public static class TelegramCommands {
        private final TelegramCommandListener listener;

        TelegramCommands(TelegramCommandListener listener) {
            this.listener = listener;
        }

        @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${trading.telegram.poll-ms:5000}")
        public void poll() {
            listener.pollOnce();
        }
    }

    @Bean
    OrderRepository orderRepository(DataSource dataSource) {
        return new JdbcOrderRepository(dataSource);
    }
}
