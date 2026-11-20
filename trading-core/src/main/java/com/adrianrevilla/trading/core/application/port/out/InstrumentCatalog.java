package com.adrianrevilla.trading.core.application.port.out;

import com.adrianrevilla.trading.core.domain.Isin;
import java.util.Optional;

/** Maps ISIN to the identifier a broker uses for the instrument (eToro instrument id). */
public interface InstrumentCatalog {
    Optional<Long> brokerId(Isin isin);

    Optional<Isin> isin(long brokerId);
}
