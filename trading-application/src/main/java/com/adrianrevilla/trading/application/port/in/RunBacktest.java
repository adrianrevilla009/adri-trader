package com.adrianrevilla.trading.application.port.in;

import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.port.out.BarSource;
import com.adrianrevilla.trading.data.domain.QualityIssue;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Input port (use case): the phase 2 backtest of the v1 strategies with the ADR-0014 criteria. */
public interface RunBacktest {
    record Verdict(String strategy, double winRate, int windows, boolean sensitivityStable, int trades,
            boolean passes, List<String> reasons) {}

    record Outcome(Map<Isin, List<Bar>> data, List<QualityIssue> criticalIssues, LocalDate designEnd,
            List<Verdict> verdicts, Path summary) {}

    Outcome run(BarSource source, List<Isin> universe, Isin benchmark, Path outDir, String gitCommit, int holdoutYears)
            throws IOException;
}
