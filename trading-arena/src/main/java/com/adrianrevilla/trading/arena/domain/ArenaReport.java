package com.adrianrevilla.trading.arena.domain;

import com.adrianrevilla.trading.arena.application.Arena;
import java.util.Locale;

/** Plain-text daily summary, short enough to be sent by Telegram. */
public final class ArenaReport {
    private ArenaReport() {}

    public static String summary(DayReport report, java.math.BigDecimal initialCapital) {
        StringBuilder sb = new StringBuilder("Arena ").append(report.day()).append('\n');
        for (AgentDay a : report.agents()) {
            double pct = a.equity().subtract(initialCapital).doubleValue() / initialCapital.doubleValue() * 100;
            sb.append(String.format(Locale.ROOT, "%s [%s] %.2f EUR (%+.2f%%), orders %d",
                    a.agentId(), a.status(), a.equity().doubleValue(), pct, a.ordersSent()));
            if (a.error() != null) {
                sb.append(" ERROR ").append(a.error());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
