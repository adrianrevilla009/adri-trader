package com.adrianrevilla.trading.data;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import java.io.IOException;
import java.util.List;

/** A provider of daily bars. Implementations: CSV directory now; eToro candles and others later. */
public interface BarSource {
    String name();

    List<Bar> fetch(Isin isin) throws IOException;
}
