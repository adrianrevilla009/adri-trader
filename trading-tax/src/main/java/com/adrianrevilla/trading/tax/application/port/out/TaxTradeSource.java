package com.adrianrevilla.trading.tax.application.port.out;

import com.adrianrevilla.trading.tax.domain.TaxTrade;
import java.util.List;

/** Output port: the executed trades of an agent, in chronological order, as the tax ledger needs them. */
public interface TaxTradeSource {
    List<TaxTrade> trades(String agentId);
}
