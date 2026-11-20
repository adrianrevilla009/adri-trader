package com.adrianrevilla.trading.tax.adapter.out.csv;

import com.adrianrevilla.trading.tax.domain.TaxLedger;
import java.math.RoundingMode;
import java.util.List;

/** Annual CSV with the sales of a year, to contrast with the broker statement. */
public final class TaxCsv {
    private TaxCsv() {}

    public static String sales(List<TaxLedger.Sale> sales) {
        StringBuilder sb = new StringBuilder(
                "date,isin,quantity,proceeds_eur,cost_basis_eur,gain_loss_eur,deferred_loss_eur,computable_eur,provisional\n");
        for (TaxLedger.Sale s : sales) {
            sb.append(s.date()).append(',').append(s.isin().value()).append(',')
                    .append(s.quantity().setScale(8, RoundingMode.HALF_EVEN)).append(',')
                    .append(s.proceeds().setScale(2, RoundingMode.HALF_EVEN)).append(',')
                    .append(s.costBasis().setScale(2, RoundingMode.HALF_EVEN)).append(',')
                    .append(s.gainLoss().setScale(2, RoundingMode.HALF_EVEN)).append(',')
                    .append(s.deferredLoss().setScale(2, RoundingMode.HALF_EVEN)).append(',')
                    .append(s.computable().setScale(2, RoundingMode.HALF_EVEN)).append(',')
                    .append(s.provisional()).append('\n');
        }
        return sb.toString();
    }
}
