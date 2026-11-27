package com.adrianrevilla.trading.core.domain;

/** Whether an agent sends new orders. STOPPED keeps its positions; closing them is a separate, explicit action. */
public enum AgentRunState {
    ACTIVE,
    STOPPED
}
