package com.adrianrevilla.trading.architecture.violations.domain;

/** Fixture: a domain class that knows JDBC. ArchitectureRules must reject it. */
public class DomainUsingJdbc {
    java.sql.Connection connection;
}
