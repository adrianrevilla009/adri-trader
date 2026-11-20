package com.adrianrevilla.trading.core.domain;

/** Order lifecycle; persisted before sending to the broker. */
public enum OrderState { CREATED, SUBMITTED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }
