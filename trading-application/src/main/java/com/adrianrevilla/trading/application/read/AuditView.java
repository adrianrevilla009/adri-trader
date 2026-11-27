package com.adrianrevilla.trading.application.read;

/** One audited change of an agent: who changed which field, from which value to which. */
public record AuditView(String agentId, String actor, String field, String oldValue, String newValue) {
}
