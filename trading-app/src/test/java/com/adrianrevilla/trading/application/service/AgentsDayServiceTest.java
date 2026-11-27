package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.application.port.in.RunAgentsDay.AgentResult;
import com.adrianrevilla.trading.application.port.in.RunTradingDay;
import com.adrianrevilla.trading.application.port.in.RunTradingDay.Outcome;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.TradingMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentsDayServiceTest {
    private static final CapitalPolicy POLICY = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25"));
    private static final LocalDate DAY = LocalDate.parse("2025-06-13");

    private final Map<String, AgentConfig> agents = new TreeMap<>();
    private final List<String> alerts = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();

    private final AgentStore store = new AgentStore() {
        public void save(AgentConfig agent, String changedBy) { agents.put(agent.id(), agent); }
        public Optional<AgentConfig> find(String id) { return Optional.ofNullable(agents.get(id)); }
        public List<AgentConfig> findAll() { return List.copyOf(agents.values()); }
        public List<AgentConfig> findActive() {
            return agents.values().stream().filter(a -> a.state() == com.adrianrevilla.trading.core.domain.AgentRunState.ACTIVE).toList();
        }
        public List<AgentAuditEntry> audit(String agentId) { return List.of(); }
    };

    private AgentsDayService service;

    @BeforeEach
    void setUp() {
        service = new AgentsDayService(store, agent -> day -> {
            ran.add(agent.id());
            if (agent.id().startsWith("boom")) {
                throw new IllegalStateException("cannot build " + agent.id());
            }
            return RunTradingDay.Result.of(Outcome.TRADED, "ok " + agent.id());
        }, (level, msg) -> alerts.add(level + ":" + msg));
    }

    private static AgentConfig agent(String id) {
        return AgentConfig.create(id, "trend", Map.of(), List.of(new Isin("IE00B4L5Y983")), new BigDecimal("40"),
                List.of(), TradingMode.DEMO, POLICY);
    }

    @Test
    void noAgentsMeansNothingToDoAndNoError() {
        assertThat(service.run(DAY)).isEmpty();
        assertThat(ran).isEmpty();
    }

    @Test
    void runsEveryActiveAgentInIdOrderAndSkipsTheStoppedOnes() {
        store.save(agent("b"), "t");
        store.save(agent("a"), "t");
        store.save(agent("c").stopped(), "t");

        List<AgentResult> results = service.run(DAY);

        assertThat(ran).containsExactly("a", "b");
        assertThat(results).extracting(AgentResult::agentId).containsExactly("a", "b");
        assertThat(results).allMatch(r -> r.result().outcome() == Outcome.TRADED);
    }

    @Test
    void anAgentThatCannotBeBuiltFailsAloneAndTheOthersStillRun() {
        store.save(agent("a"), "t");
        store.save(agent("boom"), "t");
        store.save(agent("z"), "t");

        List<AgentResult> results = service.run(DAY);

        assertThat(ran).containsExactly("a", "boom", "z");
        assertThat(results).extracting(r -> r.result().outcome())
                .containsExactly(Outcome.TRADED, Outcome.FAILED, Outcome.TRADED);
        assertThat(results.get(1).result().detail()).contains("cannot build boom");
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL") && a.contains("boom"));
    }

    @Test
    void anAgentStoppedAfterTheListWasReadStillRunsOnlyWhatTheStoreSaysActive() {
        store.save(agent("a"), "t");
        service.run(DAY);
        store.save(agent("a").stopped(), "t");
        ran.clear();

        assertThat(service.run(DAY)).isEmpty();
        assertThat(ran).isEmpty();
    }
}
