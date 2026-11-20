package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Isin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command line entry for the backtest; it does not start Spring or touch the database.
 *
 * <pre>
 * java -Dloader.main=com.adrianrevilla.trading.app.BacktestMain \
 *   -cp trading-app/target/trading-app-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *   &lt;csvDir&gt; &lt;isinsFile&gt; &lt;benchmarkIsin&gt; &lt;outDir&gt; [gitCommit]
 * </pre>
 * The ISIN file has one ISIN per line (# for comments). CSV files are {@code <csvDir>/<ISIN>.csv}.
 */
public final class BacktestMain {
    private BacktestMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: BacktestMain <csvDir> <isinsFile> <benchmarkIsin> <outDir> [gitCommit]");
            System.exit(2);
        }
        List<Isin> universe = Files.readAllLines(Path.of(args[1])).stream().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).map(Isin::new).toList();
        var outcome = new BacktestRunner().run(Path.of(args[0]), universe, new Isin(args[2]), Path.of(args[3]),
                args.length > 4 ? args[4] : "unknown", 3);
        System.out.println(Files.readString(outcome.summary()));
    }
}
