package com.adrianrevilla.trading.backtest.application.port.out;

import com.adrianrevilla.trading.backtest.domain.BacktestResult;
import com.adrianrevilla.trading.backtest.domain.Metrics;
import java.io.IOException;
import java.nio.file.Path;

/** Output port: where the reports of a backtest run go (HTML and CSV files today). */
public interface BacktestReportPublisher {
    record RunMeta(String runId, String strategyId, String config, String datasetHash, String gitCommit) {}

    Path write(Path dir, RunMeta meta, BacktestResult result, Metrics m, Metrics benchmark) throws IOException;

    /** Writes the final text summary of a run and returns where it went. */
    Path writeSummary(Path dir, String text) throws IOException;
}
