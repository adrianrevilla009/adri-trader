package com.adrianrevilla.trading.arena.domain;

import java.math.BigDecimal;

/** What one agent did on one arena day: its lifecycle status, equity at the close, orders sent and any failure. */
public record AgentDay(String agentId, AgentStatus status, BigDecimal equity, int ordersSent, String error) {}
