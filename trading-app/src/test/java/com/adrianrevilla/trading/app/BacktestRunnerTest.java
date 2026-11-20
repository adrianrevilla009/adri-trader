package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.data.QualityIssue;
import com.adrianrevilla.trading.data.XetraCalendar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the whole backtest pipeline on synthetic CSV files. The numbers mean nothing; the plumbing does. */
class BacktestRunnerTest {
    private static final List<Isin> UNIVERSE = List.of(new Isin("IE00B4L5Y983"), new Isin("IE00B3XXRP09"),
            new Isin("IE00BK5BQT80"), new Isin("IE00B5BMR087"));

    private static void writeCsv(Path dir, Isin isin, long seed, LocalDate from, LocalDate to) throws Exception {
        Random rnd = new Random(seed);
        StringBuilder sb = new StringBuilder("Date,Open,High,Low,Close,Volume\n");
        double p = 100;
        for (LocalDate d : new XetraCalendar().tradingDays(from, to)) {
            p *= 1 + 0.0003 + 0.01 * rnd.nextGaussian();
            sb.append(String.format(Locale.ROOT, "%s,%.4f,%.4f,%.4f,%.4f,100000%n", d, p, p * 1.001, p * 0.999, p));
        }
        Files.writeString(dir.resolve(isin.value() + ".csv"), sb.toString());
    }

    @Test
    void runsAllStrategiesWritesReportsAndNeverTouchesTheHoldout(@TempDir Path tmp) throws Exception {
        Path csv = Files.createDirectory(tmp.resolve("csv"));
        LocalDate from = LocalDate.parse("2012-01-02");
        LocalDate to = LocalDate.parse("2024-12-30");
        long seed = 1;
        for (Isin i : UNIVERSE) {
            writeCsv(csv, i, seed++, from, to);
        }
        Path out = tmp.resolve("out");

        var outcome = new BacktestRunner().run(csv, UNIVERSE, UNIVERSE.get(0), out, "abc1234", 3);

        assertThat(outcome.criticalIssues()).isEmpty();
        assertThat(outcome.designEnd()).isBefore(LocalDate.parse("2022-01-01"));
        assertThat(outcome.verdicts()).extracting(BacktestRunner.Verdict::strategy)
                .containsExactly("trend", "momentum", "vol-target", "swing");
        assertThat(outcome.verdicts()).allSatisfy(v -> assertThat(v.windows()).isGreaterThanOrEqualTo(6));
        String summary = Files.readString(outcome.summary());
        assertThat(summary).contains("Holdout (sin abrir)").contains("Hash del dataset").contains("registro de intentos");
        try (var files = Files.list(out)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).anyMatch(n -> n.startsWith("trend-") && n.endsWith(".html"))
                    .anyMatch(n -> n.startsWith("swing-") && n.endsWith(".csv")).contains("summary.md");
        }
    }

    @Test
    void shortHistoryIsReportedAsNotEnoughForWalkForward(@TempDir Path tmp) throws Exception {
        Path csv = Files.createDirectory(tmp.resolve("csv"));
        for (Isin i : UNIVERSE) {
            writeCsv(csv, i, 9, LocalDate.parse("2018-01-02"), LocalDate.parse("2024-12-30"));
        }
        var outcome = new BacktestRunner().run(csv, UNIVERSE, UNIVERSE.get(0), tmp.resolve("out"), "x", 3);
        assertThat(outcome.verdicts()).allSatisfy(v -> {
            assertThat(v.passes()).isFalse();
            assertThat(String.join(" ", v.reasons())).contains("not enough history");
        });
    }

    @Test
    void dataProblemsAreReportedInTheSummary(@TempDir Path tmp) throws Exception {
        Path csv = Files.createDirectory(tmp.resolve("csv"));
        for (Isin i : UNIVERSE) {
            writeCsv(csv, i, 5, LocalDate.parse("2012-01-02"), LocalDate.parse("2024-12-30"));
        }
        // break one file: a duplicated date and a zero price
        Path bad = csv.resolve(UNIVERSE.get(1).value() + ".csv");
        var lines = new java.util.ArrayList<>(Files.readAllLines(bad));
        lines.add(2, lines.get(2));
        lines.add(10, "2012-01-20,0,0,0,0,1");
        Files.write(bad, lines);
        var outcome = new BacktestRunner().run(csv, UNIVERSE, UNIVERSE.get(0), tmp.resolve("out"), "x", 3);
        assertThat(outcome.criticalIssues()).isNotEmpty();
        assertThat(Files.readString(outcome.summary())).contains("incidencias críticas");
    }

    @Test
    void aDayMissingInEveryInstrumentIsAClosureAndOldIssuesAreIgnored() {
        var closed = LocalDate.parse("2020-05-01");
        var lonely = LocalDate.parse("2020-06-02");
        var old = LocalDate.parse("2009-01-05");
        java.util.function.BiFunction<QualityIssue.Type, LocalDate, QualityIssue> q =
                (type, d) -> new QualityIssue(type, QualityIssue.Severity.CRITICAL, d, "x");
        var a = List.of(q.apply(QualityIssue.Type.GAP, closed), q.apply(QualityIssue.Type.GAP, lonely),
                q.apply(QualityIssue.Type.PRICE_JUMP, old));
        var b = List.of(q.apply(QualityIssue.Type.GAP, closed));
        var result = BacktestRunner.relevantIssues(java.util.Map.of(UNIVERSE.get(0), a, UNIVERSE.get(1), b),
                LocalDate.parse("2014-01-01"));
        assertThat(result).extracting(QualityIssue::date).containsExactly(lonely);
    }
}
