package com.adrianrevilla.trading.core.domain;

public class BrokerException extends Exception {
    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
