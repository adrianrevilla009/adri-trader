package com.adrianrevilla.trading.arena.adapter.out.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.arena.application.Arena;
import com.adrianrevilla.trading.arena.domain.Agent;
import com.adrianrevilla.trading.arena.domain.DayReport;
import com.adrianrevilla.trading.backtest.domain.CostModel;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.MarketSnapshot;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.core.domain.TargetPortfolio;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArenaHtmlReportTest {
    private static final Isin A = new Isin("IE00B4L5Y983");

    private static List<Bar> bars(int days) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = LocalDate.parse("2025-03-03"); // a Monday
        for (int i = 0; i < days; i++) {
            BigDecimal p = BigDecimal.valueOf(100 + i);
            out.add(new Bar(d.plusDays(i), p, p, p, p, 1000));
        }
        return out;
    }

    @Test
    void agentNamesAndErrorsAreEscapedSoTheReportCannotInjectHtml() {
        var arena = new Arena(Map.of(A, bars(4)), CostModel.none(), (l, m) -> {});
        var risk = RiskConfig.defaults(Set.of(A));
        arena.addAgent("<script>alert(1)</script>", Agent.Kind.STRATEGY, new BuyAndHold("x", A), BigDecimal.valueOf(100), risk);
        Strategy exploding = new Strategy() {
            @Override public String id() { return "boom"; }
            @Override public TargetPortfolio decide(MarketSnapshot s, PortfolioState st) {
                throw new IllegalStateException("<img src=x onerror=alert(2)>");
            }
        };
        arena.addAgent("boom", Agent.Kind.STRATEGY, exploding, BigDecimal.valueOf(100), risk);
        DayReport report = null;
        for (int i = 0; i < 4; i++) {
            report = arena.runDay(LocalDate.parse("2025-03-03").plusDays(i));
        }
        String html = ArenaHtmlReport.render(arena, report);
        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;img src=x onerror=alert(2)&gt;");
    }

    @Test
    void aFreshArenaWithOneDayRendersWithoutACrashAndWithoutAChart() {
        var arena = new Arena(Map.of(A, bars(1)), CostModel.none(), (l, m) -> {});
        arena.addAgent("hold", Agent.Kind.BENCHMARK, new BuyAndHold("hold", A), BigDecimal.valueOf(100),
                RiskConfig.defaults(Set.of(A)));
        var report = arena.runDay(LocalDate.parse("2025-03-03"));
        String html = ArenaHtmlReport.render(arena, report);
        assertThat(html).contains("hold").contains("Not enough data yet").doesNotContain("<svg");
    }

    @Test
    void theReportLoadsNothingFromOutside() {
        var arena = new Arena(Map.of(A, bars(5)), CostModel.none(), (l, m) -> {});
        arena.addAgent("hold", Agent.Kind.BENCHMARK, new BuyAndHold("hold", A), BigDecimal.valueOf(100),
                RiskConfig.defaults(Set.of(A)));
        DayReport report = null;
        for (int i = 0; i < 5; i++) {
            report = arena.runDay(LocalDate.parse("2025-03-03").plusDays(i));
        }
        String html = ArenaHtmlReport.render(arena, report);
        assertThat(html).doesNotContain("http://").doesNotContain("https://").doesNotContain("<script");
    }
}
