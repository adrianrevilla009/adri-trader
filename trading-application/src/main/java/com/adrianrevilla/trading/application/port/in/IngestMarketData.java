package com.adrianrevilla.trading.application.port.in;

/** Input port (use case): downloads, validates and stores the latest market data of the whole universe. */
public interface IngestMarketData {
    /** @return how many instruments ended with a usable series */
    int run();
}
