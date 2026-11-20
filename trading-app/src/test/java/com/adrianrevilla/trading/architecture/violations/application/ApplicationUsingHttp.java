package com.adrianrevilla.trading.architecture.violations.application;

/** Fixture: a use case that talks HTTP directly instead of through a port. */
public class ApplicationUsingHttp {
    java.net.http.HttpClient client;
}
