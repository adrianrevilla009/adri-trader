package com.adrianrevilla.trading.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataQualityTest {
    private final XetraCalendar calendar = new XetraCalendar();
    private final DataQualityValidator validator = new DataQualityValidator(calendar);

    private static Bar bar(String date, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        return new Bar(LocalDate.parse(date), c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, volume);
    }

    @Test
    void calendarSkipsWeekendsAndHolidays() {
        assertThat(calendar.isTradingDay(LocalDate.parse("2025-04-18"))).isFalse(); // Good Friday
        assertThat(calendar.isTradingDay(LocalDate.parse("2025-04-21"))).isFalse(); // Easter Monday
        assertThat(calendar.isTradingDay(LocalDate.parse("2025-12-24"))).isFalse();
        assertThat(calendar.isTradingDay(LocalDate.parse("2025-06-14"))).isFalse(); // Saturday
        assertThat(calendar.isTradingDay(LocalDate.parse("2025-06-16"))).isTrue();
        assertThat(calendar.nextTradingDay(LocalDate.parse("2025-04-17"))).isEqualTo(LocalDate.parse("2025-04-22"));
    }

    @Test
    void cleanSeriesHasNoIssues() {
        List<Bar> bars = new ArrayList<>();
        for (LocalDate d : calendar.tradingDays(LocalDate.parse("2025-06-02"), LocalDate.parse("2025-06-13"))) {
            bars.add(new Bar(d, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1000));
        }
        assertThat(validator.validate(bars, Set.of())).isEmpty();
    }

    @Test
    void detectsGapDuplicateJumpZeroVolumeAndBadPrices() {
        List<Bar> bars = List.of(
                bar("2025-06-02", "100", 10),
                bar("2025-06-02", "100", 10),   // duplicate
                bar("2025-06-04", "100", 0),    // 06-03 missing, zero volume
                bar("2025-06-05", "130", 10),   // +30 %
                bar("2025-06-06", "-5", 10));   // non-positive
        var types = validator.validate(bars, Set.of()).stream().map(QualityIssue::type).toList();
        assertThat(types).contains(QualityIssue.Type.DUPLICATE, QualityIssue.Type.GAP, QualityIssue.Type.ZERO_VOLUME,
                QualityIssue.Type.PRICE_JUMP, QualityIssue.Type.NON_POSITIVE_PRICE);
    }

    @Test
    void jumpWithCorporateEventIsAccepted() {
        List<Bar> bars = List.of(bar("2025-06-02", "100", 10), bar("2025-06-03", "130", 10));
        assertThat(validator.validate(bars, Set.of(LocalDate.parse("2025-06-03")))).isEmpty();
    }

    @Test
    void reportsSourceMismatchAboveHalfPercent() {
        var a = List.of(bar("2025-06-02", "100", 10), bar("2025-06-03", "100", 10));
        var b = List.of(bar("2025-06-02", "100.3", 10), bar("2025-06-03", "101", 10));
        var issues = validator.compareSources(a, b);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).date()).isEqualTo(LocalDate.parse("2025-06-03"));
    }

    @Test
    void cleanerDropsInvalidBarsAndPrefersFirstSource() {
        Bar invalid = new Bar(LocalDate.parse("2025-06-02"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, 1);
        var primary = List.of(invalid, bar("2025-06-03", "100", 1));
        var secondary = List.of(bar("2025-06-02", "50", 1), bar("2025-06-03", "999", 1));
        var clean = new BarCleaner().clean(List.of(primary, secondary));
        assertThat(clean).hasSize(2);
        assertThat(clean.get(0).close()).isEqualByComparingTo("50");  // fallback to secondary
        assertThat(clean.get(1).close()).isEqualByComparingTo("100"); // primary wins
    }

    @Test
    void csvReaderParsesStooqFormatAndRejectsGarbage() throws IOException {
        var bars = new CsvBarReader().read(new StringReader(
                "Date,Open,High,Low,Close,Volume\n2025-06-02,10,11,9,10.5,1000\n2025-06-03,10.5,12,10,11,0\n"));
        assertThat(bars).hasSize(2);
        assertThat(bars.get(1).close()).isEqualByComparingTo("11");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new CsvBarReader().read(new StringReader("Date,Open\n2025-06-02,abc\n")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void datasetHashIsStableAndSensitiveToChanges() {
        Isin i = new Isin("IE00B4L5Y983");
        var s1 = Map.of(i, List.of(bar("2025-06-02", "100", 1)));
        var s2 = Map.of(i, List.of(bar("2025-06-02", "100.0", 1)));
        var s3 = Map.of(i, List.of(bar("2025-06-02", "100.01", 1)));
        assertThat(DatasetHash.of(s1)).isEqualTo(DatasetHash.of(s2)).isNotEqualTo(DatasetHash.of(s3));
    }
}
