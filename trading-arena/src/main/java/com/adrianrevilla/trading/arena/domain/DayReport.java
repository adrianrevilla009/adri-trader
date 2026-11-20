package com.adrianrevilla.trading.arena.domain;

import java.time.LocalDate;
import java.util.List;

/** The result of one arena day for every agent. */
public record DayReport(LocalDate day, List<AgentDay> agents) {}
