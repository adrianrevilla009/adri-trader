package com.adrianrevilla.trading.tax.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.tax.adapter.out.csv.TaxCsv;
import com.adrianrevilla.trading.tax.domain.TaxTrade.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-computed cases. Not tax advice; an adviser reviews the module before the first real declaration. */
class TaxLedgerTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final LocalDate END = LocalDate.parse("2030-01-01");
    private final TaxLedger ledger = new TaxLedger();

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static TaxTrade buy(String date, String qty, String unitPrice) {
        return trade(date, Side.BUY, qty, unitPrice, "0");
    }

    private static TaxTrade sell(String date, String qty, String unitPrice) {
        return trade(date, Side.SELL, qty, unitPrice, "0");
    }

    private static TaxTrade trade(String date, Side side, String qty, String unitPrice, String fee) {
        return new TaxTrade(LocalDate.parse(date), A, side, bd(qty), bd(qty).multiply(bd(unitPrice)), bd(fee));
    }

    @Test
    void fifoSellsTheOldestUnitsFirst() {
        var r = ledger.compute(List.of(buy("2024-01-10", "10", "10"), buy("2024-02-10", "10", "12"),
                sell("2024-09-10", "15", "15")), END);
        var s = r.sales().get(0);
        assertThat(s.proceeds()).isEqualByComparingTo("225");
        assertThat(s.costBasis()).isEqualByComparingTo("160"); // 10*10 + 5*12
        assertThat(s.gainLoss()).isEqualByComparingTo("65");
        assertThat(r.netComputable()).isEqualByComparingTo("65");
    }

    @Test
    void remainingLotKeepsItsOwnCostForALaterSale() {
        var r = ledger.compute(List.of(buy("2024-01-10", "10", "10"), buy("2024-02-10", "10", "12"),
                sell("2024-09-10", "15", "15"), sell("2024-10-10", "5", "20")), END);
        assertThat(r.sales().get(1).costBasis()).isEqualByComparingTo("60"); // 5 units at 12
        assertThat(r.sales().get(1).gainLoss()).isEqualByComparingTo("40");
    }

    @Test
    void feesRaiseTheCostAndLowerTheProceeds() {
        var r = ledger.compute(List.of(trade("2024-01-10", Side.BUY, "10", "10", "2"),
                trade("2024-09-10", Side.SELL, "10", "11", "3")), END);
        var s = r.sales().get(0);
        assertThat(s.costBasis()).isEqualByComparingTo("102");
        assertThat(s.proceeds()).isEqualByComparingTo("107");
        assertThat(s.gainLoss()).isEqualByComparingTo("5");
    }

    @Test
    void aLossWithoutRepurchaseIsComputable() {
        var r = ledger.compute(List.of(buy("2024-01-10", "10", "10"), sell("2024-09-10", "10", "8")), END);
        assertThat(r.sales().get(0).computable()).isEqualByComparingTo("-20");
        assertThat(r.totalDeferred()).isEqualByComparingTo("0");
    }

    @Test
    void twoMonthRuleBeforeTheSaleDefersTheLossIntoTheRemainingLot() {
        // Jan lot is sold at a loss on 15 Jun; the lot bought on 1 Jun (within 2 months before) is still held
        var r = ledger.compute(List.of(buy("2024-01-01", "10", "10"), buy("2024-06-01", "10", "10"),
                sell("2024-06-15", "10", "8"), sell("2024-12-01", "10", "10")), END);
        var first = r.sales().get(0);
        assertThat(first.gainLoss()).isEqualByComparingTo("-20");
        assertThat(first.deferredLoss()).isEqualByComparingTo("20");
        assertThat(first.computable()).isEqualByComparingTo("0");
        // the repurchased lot now costs 120, so selling it for 100 realises the deferred loss
        assertThat(r.sales().get(1).costBasis()).isEqualByComparingTo("120");
        assertThat(r.sales().get(1).computable()).isEqualByComparingTo("-20");
        assertThat(r.netComputable()).isEqualByComparingTo("-20");
    }

    @Test
    void twoMonthRuleAfterTheSaleAlsoDefers() {
        var r = ledger.compute(List.of(buy("2024-01-01", "10", "10"), sell("2024-06-15", "10", "8"),
                buy("2024-07-10", "10", "8"), sell("2024-12-01", "10", "9")), END);
        assertThat(r.sales().get(0).deferredLoss()).isEqualByComparingTo("20");
        assertThat(r.sales().get(0).computable()).isEqualByComparingTo("0");
        assertThat(r.sales().get(1).costBasis()).isEqualByComparingTo("100"); // 80 + 20 deferred
        assertThat(r.sales().get(1).computable()).isEqualByComparingTo("-10");
    }

    @Test
    void partialRepurchaseDefersOnlyThatShareOfTheLoss() {
        var r = ledger.compute(List.of(buy("2024-01-01", "10", "10"), sell("2024-06-15", "10", "8"),
                buy("2024-07-10", "4", "8")), END);
        var s = r.sales().get(0);
        assertThat(s.deferredLoss()).isEqualByComparingTo("8");  // 20 * 4/10
        assertThat(s.computable()).isEqualByComparingTo("-12");
    }

    @Test
    void repurchaseOutsideTheTwoMonthsDoesNotDefer() {
        var r = ledger.compute(List.of(buy("2024-01-01", "10", "10"), sell("2024-06-15", "10", "8"),
                buy("2024-08-16", "10", "8")), END);
        assertThat(r.sales().get(0).deferredLoss()).isEqualByComparingTo("0");
        assertThat(r.sales().get(0).computable()).isEqualByComparingTo("-20");
    }

    @Test
    void gainsAreNeverDeferredEvenIfBoughtAgain() {
        var r = ledger.compute(List.of(buy("2024-01-01", "10", "10"), sell("2024-06-15", "10", "12"),
                buy("2024-06-20", "10", "12")), END);
        assertThat(r.sales().get(0).computable()).isEqualByComparingTo("20");
    }

    @Test
    void windowStillOpenIsMarkedProvisional() {
        var r = ledger.compute(List.of(buy("2024-01-01", "10", "10"), sell("2024-06-15", "10", "8")),
                LocalDate.parse("2024-07-01"));
        assertThat(r.sales().get(0).provisional()).isTrue();
    }

    @Test
    void sellingMoreThanHeldIsRefused() {
        assertThatThrownBy(() -> ledger.compute(List.of(buy("2024-01-01", "5", "10"), sell("2024-02-01", "6", "10")), END))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportFiltersByYearAndExportsCsv() {
        var r = ledger.compute(List.of(buy("2023-01-01", "10", "10"), sell("2023-06-01", "5", "12"),
                sell("2024-06-01", "5", "11")), END);
        assertThat(r.year(2023)).hasSize(1);
        assertThat(r.netComputableOf(2023)).isEqualByComparingTo("10");
        assertThat(r.netComputableOf(2024)).isEqualByComparingTo("5");
        String csv = TaxCsv.sales(r.year(2024));
        assertThat(csv).startsWith("date,isin,quantity").contains("2024-06-01,IE00B4L5Y983");
    }

    @Test
    void savingsTaxUsesTheBrackets() {
        assertThat(SavingsTax.estimate(bd("10000"))).isEqualByComparingTo("1980");   // 6000*19% + 4000*21%
        assertThat(SavingsTax.estimate(bd("60000"))).isEqualByComparingTo("12680");  // + 44000*21% + 10000*23%
        assertThat(SavingsTax.estimate(bd("-500"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> SavingsTax.estimate(bd("200001"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelo720WarnsBeforeTheLegalThreshold() {
        assertThat(Model720Monitor.check(bd("39999"))).isEqualTo(Model720Monitor.Level.NONE);
        assertThat(Model720Monitor.check(bd("40000"))).isEqualTo(Model720Monitor.Level.WARNING);
        assertThat(Model720Monitor.check(bd("50000"))).isEqualTo(Model720Monitor.Level.WARNING);
        assertThat(Model720Monitor.check(bd("50001"))).isEqualTo(Model720Monitor.Level.OBLIGATION);
    }
}
