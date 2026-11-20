package com.adrianrevilla.trading.arena;

import com.adrianrevilla.trading.backtest.BacktestResult.EquityPoint;
import java.util.List;
import java.util.Locale;

/** Self-contained HTML report (no external resources): one table and one equity chart per agent. */
public final class ArenaHtmlReport {
    private ArenaHtmlReport() {}

    public static String render(Arena arena, Arena.DayReport day) {
        StringBuilder sb = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Arena ")
                .append(day.day()).append("</title><style>body{font-family:sans-serif;margin:2rem}"
                        + "table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 10px;text-align:right}"
                        + "td:first-child,th:first-child{text-align:left}.err{color:#b00020}</style></head><body>")
                .append("<h1>Arena ").append(day.day()).append("</h1><table><tr><th>Agent</th><th>Status</th>"
                        + "<th>Equity (EUR)</th><th>Return</th><th>Orders</th><th>Error</th></tr>");
        for (Arena.AgentDay a : day.agents()) {
            double init = arena.agent(a.agentId()).initialCapital().doubleValue();
            sb.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%s</td><td>%.2f</td><td>%+.2f%%</td><td>%d</td>"
                            + "<td class=\"err\">%s</td></tr>", esc(a.agentId()), a.status(), a.equity().doubleValue(),
                    (a.equity().doubleValue() - init) / init * 100, a.ordersSent(),
                    a.error() == null ? "" : esc(a.error())));
        }
        sb.append("</table>");
        for (Agent a : arena.agents()) {
            sb.append("<h2>").append(esc(a.id())).append("</h2>").append(chart(a.equity()));
        }
        return sb.append("<p><small>Simulated results. Not investment advice.</small></p></body></html>").toString();
    }

    private static String chart(List<EquityPoint> eq) {
        if (eq.size() < 2) {
            return "<p>Not enough data yet.</p>";
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (EquityPoint p : eq) {
            min = Math.min(min, p.equity().doubleValue());
            max = Math.max(max, p.equity().doubleValue());
        }
        double span = Math.max(max - min, 1e-9);
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < eq.size(); i++) {
            double x = 600.0 * i / (eq.size() - 1);
            double y = 120 - 120 * (eq.get(i).equity().doubleValue() - min) / span;
            pts.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return "<svg viewBox=\"0 0 600 120\" width=\"600\" height=\"120\"><polyline fill=\"none\" stroke=\"#1565c0\" "
                + "points=\"" + pts + "\"/></svg><div><small>min " + String.format(Locale.ROOT, "%.2f", min) + " / max "
                + String.format(Locale.ROOT, "%.2f", max) + "</small></div>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
