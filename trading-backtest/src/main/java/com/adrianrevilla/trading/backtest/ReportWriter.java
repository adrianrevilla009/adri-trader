package com.adrianrevilla.trading.backtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Writes the HTML and CSV report of a run; every run carries what is needed to repeat it. */
public final class ReportWriter {

    /** @param runId unique id; {@code config} human-readable parameters; hash and commit give reproducibility */
    public record RunMeta(String runId, String strategyId, String config, String datasetHash, String gitCommit) {}

    public Path write(Path dir, RunMeta meta, BacktestResult result, Metrics m, Metrics benchmark) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(meta.runId() + ".csv"), equityCsv(result), StandardCharsets.UTF_8);
        Path html = dir.resolve(meta.runId() + ".html");
        Files.writeString(html, html(meta, m, benchmark), StandardCharsets.UTF_8);
        return html;
    }

    static String equityCsv(BacktestResult r) {
        StringBuilder sb = new StringBuilder("date,equity,invested\n");
        r.equity().forEach(p -> sb.append(p.date()).append(',').append(p.equity().setScale(6, java.math.RoundingMode.HALF_EVEN))
                .append(',').append(p.invested().setScale(6, java.math.RoundingMode.HALF_EVEN)).append('\n'));
        return sb.toString();
    }

    static String html(RunMeta meta, Metrics m, Metrics b) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>")
                .append(esc(meta.strategyId())).append("</title></head><body>");
        sb.append("<h1>").append(esc(meta.strategyId())).append("</h1><ul>");
        sb.append("<li>Run: ").append(esc(meta.runId())).append("</li>");
        sb.append("<li>Config: ").append(esc(meta.config())).append("</li>");
        sb.append("<li>Dataset hash: ").append(esc(meta.datasetHash())).append("</li>");
        sb.append("<li>Commit: ").append(esc(meta.gitCommit())).append("</li></ul>");
        sb.append("<table border=\"1\" cellpadding=\"4\"><tr><th>Métrica (neta)</th><th>Estrategia</th><th>Benchmark</th></tr>");
        row(sb, "CAGR", m.cagr(), b.cagr(), true);
        row(sb, "Volatilidad", m.volatility(), b.volatility(), true);
        row(sb, "Sharpe", m.sharpe(), b.sharpe(), false);
        row(sb, "Sortino", m.sortino(), b.sortino(), false);
        row(sb, "Caída máxima", m.maxDrawdown(), b.maxDrawdown(), true);
        row(sb, "Calmar", m.calmar(), b.calmar(), false);
        row(sb, "Rotación anual", m.turnover(), b.turnover(), true);
        row(sb, "% tiempo invertido", m.investedShare(), b.investedShare(), true);
        sb.append("<tr><td>Operaciones</td><td>").append(m.trades()).append("</td><td>").append(b.trades())
                .append("</td></tr></table></body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String name, double a, double b, boolean pct) {
        sb.append("<tr><td>").append(name).append("</td><td>").append(fmt(a, pct)).append("</td><td>")
                .append(fmt(b, pct)).append("</td></tr>");
    }

    private static String fmt(double v, boolean pct) {
        if (Double.isNaN(v)) {
            return "n/d";
        }
        return pct ? String.format(Locale.ROOT, "%.2f%%", v * 100) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
