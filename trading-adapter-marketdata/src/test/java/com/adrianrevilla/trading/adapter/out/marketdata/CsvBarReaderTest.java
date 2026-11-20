package com.adrianrevilla.trading.adapter.out.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class CsvBarReaderTest {
    @Test
    void parsesStooqFormatAndRejectsGarbage() throws IOException {
        var bars = new CsvBarReader().read(new StringReader(
                "Date,Open,High,Low,Close,Volume\n2025-06-02,10,11,9,10.5,1000\n2025-06-03,10.5,12,10,11,0\n"));
        assertThat(bars).hasSize(2);
        assertThat(bars.get(1).close()).isEqualByComparingTo("11");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new CsvBarReader().read(new StringReader("Date,Open\n2025-06-02,abc\n")))
                .isInstanceOf(IOException.class);
    }
}
