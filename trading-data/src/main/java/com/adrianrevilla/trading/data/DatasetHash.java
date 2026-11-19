package com.adrianrevilla.trading.data;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** SHA-256 over the whole dataset, stored with every backtest so it can be repeated exactly. */
public final class DatasetHash {
    private DatasetHash() {}

    public static String of(Map<Isin, List<Bar>> series) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (var e : new TreeMap<>(toStringKeys(series)).entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                for (Bar b : e.getValue()) {
                    String row = b.date() + "|" + b.open().stripTrailingZeros().toPlainString()
                            + "|" + b.high().stripTrailingZeros().toPlainString()
                            + "|" + b.low().stripTrailingZeros().toPlainString()
                            + "|" + b.close().stripTrailingZeros().toPlainString()
                            + "|" + b.volume() + "|" + b.availableOn() + "\n";
                    md.update(row.getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, List<Bar>> toStringKeys(Map<Isin, List<Bar>> series) {
        Map<String, List<Bar>> m = new TreeMap<>();
        series.forEach((k, v) -> m.put(k.value(), v));
        return m;
    }
}
