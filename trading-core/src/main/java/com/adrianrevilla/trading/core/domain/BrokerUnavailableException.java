package com.adrianrevilla.trading.core.domain;

/** Outcome unknown (timeout, connection cut, 5xx). Never retry blindly: look the order up first. */
public class BrokerUnavailableException extends BrokerException {
    public BrokerUnavailableException(String message) {
        super(message);
    }

    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
