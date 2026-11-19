package com.adrianrevilla.trading.core;

/** The broker refused our credentials or revoked access (401/403). Nothing was processed; trading must stop. */
public class BrokerAccessDeniedException extends BrokerRejectedException {
    public BrokerAccessDeniedException(String message) {
        super(message);
    }
}
