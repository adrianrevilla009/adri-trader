package com.adrianrevilla.trading.core;

/** Definitive refusal: the order does not exist at the broker. Safe to mark the order REJECTED. */
public class BrokerRejectedException extends BrokerException {
    public BrokerRejectedException(String message) {
        super(message);
    }
}
