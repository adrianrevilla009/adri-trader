package com.adrianrevilla.trading.core;

/** Order lifecycle; persisted before sending to the broker. */
public enum OrderState { CREATED, SUBMITTED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }
