package com.adrianrevilla.trading.tax;

import com.adrianrevilla.trading.core.Isin;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gains and losses per ISIN with the FIFO criterion (the oldest units are understood sold first) and the
 * two-month rule: a loss on a sale is not computable when homogeneous securities were bought in the two months
 * before or after; the deferred loss is added to the cost of the repurchased units and comes out when those are sold.
 *
 * <p>This is an engine for the bot own records, not tax advice. An adviser must review it before the first
 * declaration with real operations (ADR-0017). Not implemented: carry-forward of net losses, currencies other
 * than EUR, rates above 200,000 EUR, dividends with foreign withholding credits.
 */
public final class TaxLedger {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DUST = new BigDecimal("1e-12");

    /** One sale. {@code provisional} means the +2 months window is not closed yet at the {@code asOf} date. */
    public static final class Sale {
        private final LocalDate date;
        private final Isin isin;
        private final BigDecimal quantity;
        private final BigDecimal proceeds;
        private final BigDecimal costBasis;
        private BigDecimal deferredLoss = BigDecimal.ZERO;
        private boolean provisional;

        Sale(LocalDate date, Isin isin, BigDecimal quantity, BigDecimal proceeds, BigDecimal costBasis) {
            this.date = date;
            this.isin = isin;
            this.quantity = quantity;
            this.proceeds = proceeds;
            this.costBasis = costBasis;
        }

        public LocalDate date() { return date; }
        public Isin isin() { return isin; }
        public BigDecimal quantity() { return quantity; }
        public BigDecimal proceeds() { return proceeds; }
        public BigDecimal costBasis() { return costBasis; }
        public BigDecimal gainLoss() { return proceeds.subtract(costBasis); }
        public BigDecimal deferredLoss() { return deferredLoss; }
        public boolean provisional() { return provisional; }

        /** Result that counts now: the gain or loss minus the part of the loss that is deferred. */
        public BigDecimal computable() {
            return gainLoss().add(deferredLoss);
        }
    }

    public record Report(List<Sale> sales, BigDecimal netComputable, BigDecimal totalDeferred) {
        public List<Sale> year(int year) {
            return sales.stream().filter(s -> s.date().getYear() == year).toList();
        }

        public BigDecimal netComputableOf(int year) {
            return year(year).stream().map(Sale::computable).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final class Lot {
        final LocalDate date;
        BigDecimal quantity;
        BigDecimal cost; // total cost of the remaining quantity, including fees and deferred losses
        BigDecimal covered = BigDecimal.ZERO; // units already used to defer a loss

        Lot(LocalDate date, BigDecimal quantity, BigDecimal cost) {
            this.date = date;
            this.quantity = quantity;
            this.cost = cost;
        }
    }

    /** Units bought after a loss sale that still have to be covered by the deferral. */
    private static final class PendingCover {
        final Sale sale;
        final LocalDate windowEnd;
        final BigDecimal lossPerUnit;
        BigDecimal unitsLeft;

        PendingCover(Sale sale, LocalDate windowEnd, BigDecimal lossPerUnit, BigDecimal unitsLeft) {
            this.sale = sale;
            this.windowEnd = windowEnd;
            this.lossPerUnit = lossPerUnit;
            this.unitsLeft = unitsLeft;
        }
    }

    /**
     * @param trades all trades, in chronological order (same-day trades in the order they happened)
     * @param asOf date of the report; windows that end after it are marked provisional
     */
    public Report compute(List<TaxTrade> trades, LocalDate asOf) {
        Map<Isin, Deque<Lot>> lots = new HashMap<>();
        Map<Isin, List<PendingCover>> pending = new HashMap<>();
        List<Sale> sales = new ArrayList<>();

        for (TaxTrade t : trades) {
            if (t.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Quantity must be positive: " + t);
            }
            Deque<Lot> deque = lots.computeIfAbsent(t.isin(), k -> new ArrayDeque<>());
            if (t.side() == TaxTrade.Side.BUY) {
                Lot lot = new Lot(t.date(), t.quantity(), t.amountEur().add(t.feeEur()));
                cover(pending.getOrDefault(t.isin(), List.of()), lot, t.date());
                deque.addLast(lot);
            } else {
                sales.add(sell(t, deque, pending, asOf));
            }
        }

        BigDecimal net = BigDecimal.ZERO;
        BigDecimal deferred = BigDecimal.ZERO;
        for (Sale s : sales) {
            net = net.add(s.computable());
            deferred = deferred.add(s.deferredLoss());
        }
        return new Report(List.copyOf(sales), scale(net), scale(deferred));
    }

    private Sale sell(TaxTrade t, Deque<Lot> deque, Map<Isin, List<PendingCover>> pending, LocalDate asOf) {
        BigDecimal toSell = t.quantity();
        BigDecimal basis = BigDecimal.ZERO;
        while (toSell.compareTo(DUST) > 0) {
            Lot first = deque.peekFirst();
            if (first == null) {
                throw new IllegalStateException("Sale of " + t.quantity() + " " + t.isin().value() + " on " + t.date()
                        + " exceeds the units held (no short positions in v1)");
            }
            BigDecimal take = first.quantity.min(toSell);
            BigDecimal part = first.cost.multiply(take, MC).divide(first.quantity, MC);
            basis = basis.add(part);
            first.cost = first.cost.subtract(part);
            first.quantity = first.quantity.subtract(take);
            toSell = toSell.subtract(take);
            if (first.quantity.compareTo(DUST) <= 0) {
                deque.pollFirst();
            }
        }
        Sale sale = new Sale(t.date(), t.isin(), t.quantity(), t.amountEur().subtract(t.feeEur()), basis);
        LocalDate windowEnd = t.date().plusMonths(2);
        sale.provisional = windowEnd.isAfter(asOf);

        BigDecimal loss = sale.gainLoss();
        if (loss.signum() < 0) {
            BigDecimal lossPerUnit = loss.negate().divide(t.quantity(), MC);
            BigDecimal unitsToCover = t.quantity();
            LocalDate windowStart = t.date().minusMonths(2);
            // units still held that were bought in the two months before the sale
            for (Lot lot : deque) {
                if (unitsToCover.compareTo(DUST) <= 0) {
                    break;
                }
                if (!lot.date.isBefore(windowStart) && !lot.date.isAfter(t.date())) {
                    BigDecimal covered = lot.quantity.subtract(lot.covered).min(unitsToCover);
                    if (covered.compareTo(DUST) <= 0) {
                        continue;
                    }
                    defer(sale, lot, covered, lossPerUnit);
                    unitsToCover = unitsToCover.subtract(covered);
                }
            }
            // units that will be bought in the two months after: wait for them
            if (unitsToCover.compareTo(DUST) > 0) {
                pending.computeIfAbsent(t.isin(), k -> new ArrayList<>())
                        .add(new PendingCover(sale, windowEnd, lossPerUnit, unitsToCover));
            }
        }
        return sale;
    }

    private void cover(List<PendingCover> list, Lot lot, LocalDate buyDate) {
        for (PendingCover p : list) {
            if (p.unitsLeft.compareTo(DUST) <= 0 || buyDate.isAfter(p.windowEnd)) {
                continue;
            }
            BigDecimal available = lot.quantity.subtract(lot.covered);
            if (available.compareTo(DUST) <= 0) {
                break;
            }
            BigDecimal covered = available.min(p.unitsLeft);
            defer(p.sale, lot, covered, p.lossPerUnit);
            p.unitsLeft = p.unitsLeft.subtract(covered);
        }
    }

    /** The loss of {@code covered} units is not computable now; it raises the cost of the repurchased lot. */
    private void defer(Sale sale, Lot lot, BigDecimal covered, BigDecimal lossPerUnit) {
        BigDecimal amount = lossPerUnit.multiply(covered, MC);
        sale.deferredLoss = sale.deferredLoss.add(amount);
        lot.cost = lot.cost.add(amount);
        lot.covered = lot.covered.add(covered);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_EVEN);
    }
}
