package com.adrianrevilla.trading.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentConfigTest {
    private static final CapitalPolicy POLICY = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25"));
    private static final List<Isin> UNIVERSE = List.of(new Isin("IE00B4L5Y983"), new Isin("IE00B5BMR087"));

    private static AgentConfig agent(String id, BigDecimal capital) {
        return AgentConfig.create(id, "trend", Map.of("window", new BigDecimal("200")), UNIVERSE, capital, List.of(),
                TradingMode.DEMO, POLICY);
    }

    @Test
    void createsAnActiveAgent() {
        var a = agent("trend-1", new BigDecimal("40"));
        assertThat(a.id()).isEqualTo("trend-1");
        assertThat(a.strategyId()).isEqualTo("trend");
        assertThat(a.state()).isEqualTo(AgentRunState.ACTIVE);
        assertThat(a.mode()).isEqualTo(TradingMode.DEMO);
        assertThat(a.params()).containsEntry("window", new BigDecimal("200"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "has space", "UPPER", "a/b"})
    void rejectsBadIds(String id) {
        assertThatThrownBy(() -> agent(id, new BigDecimal("40"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idLengthLimitIs64() {
        assertThat(agent("a".repeat(64), new BigDecimal("40")).id()).hasSize(64);
        assertThatThrownBy(() -> agent("a".repeat(65), new BigDecimal("40")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsBlankStrategy(String strategy) {
        assertThatThrownBy(() -> AgentConfig.create("a", strategy, Map.of(), UNIVERSE, new BigDecimal("40"), List.of(),
                TradingMode.DEMO, POLICY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCapitalBelowThePolicyMinimum() {
        assertThatThrownBy(() -> agent("a", new BigDecimal("39.99")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("40");
    }

    @Test
    void rejectsNullCapitalAndZeroCapital() {
        assertThatThrownBy(() -> agent("a", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent("a", BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProdUntilItIsBuilt() {
        assertThatThrownBy(() -> AgentConfig.create("a", "trend", Map.of(), UNIVERSE, new BigDecimal("40"), List.of(),
                TradingMode.PROD, POLICY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PROD");
    }

    @Test
    void doesNotKeepAReferenceToTheParamsOfTheCaller() {
        Map<String, BigDecimal> params = new HashMap<>();
        params.put("window", new BigDecimal("200"));
        var a = AgentConfig.create("a", "trend", params, UNIVERSE, new BigDecimal("40"), List.of(), TradingMode.DEMO, POLICY);
        params.put("window", BigDecimal.ONE);
        assertThat(a.params()).containsEntry("window", new BigDecimal("200"));
        assertThatThrownBy(() -> a.params().put("x", BigDecimal.ONE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullParamValues() {
        Map<String, BigDecimal> params = new HashMap<>();
        params.put("window", null);
        assertThatThrownBy(() -> AgentConfig.create("a", "trend", params, UNIVERSE, new BigDecimal("40"), List.of(),
                TradingMode.DEMO, POLICY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theUniverseIsRequiredWithoutDuplicatesOrNullsAndIsCopied() {
        assertThatThrownBy(() -> AgentConfig.create("a", "t", Map.of(), List.of(), new BigDecimal("40"), List.of(),
                TradingMode.DEMO, POLICY)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("universe");
        assertThatThrownBy(() -> AgentConfig.create("a", "t", Map.of(), List.of(UNIVERSE.get(0), UNIVERSE.get(0)),
                new BigDecimal("40"), List.of(), TradingMode.DEMO, POLICY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        var withNull = new java.util.ArrayList<Isin>();
        withNull.add(null);
        assertThatThrownBy(() -> AgentConfig.create("a", "t", Map.of(), withNull, new BigDecimal("40"), List.of(),
                TradingMode.DEMO, POLICY)).isInstanceOf(IllegalArgumentException.class);

        var mutable = new java.util.ArrayList<>(UNIVERSE);
        var a = AgentConfig.create("a", "t", Map.of(), mutable, new BigDecimal("40"), List.of(), TradingMode.DEMO,
                POLICY);
        mutable.clear();
        assertThat(a.universe()).containsExactlyElementsOf(UNIVERSE);
        assertThatThrownBy(() -> a.universe().add(UNIVERSE.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theOrderOfTheUniverseMattersForEquality() {
        var a = AgentConfig.create("a", "t", Map.of(), List.of(UNIVERSE.get(0), UNIVERSE.get(1)),
                new BigDecimal("40"), List.of(), TradingMode.DEMO, POLICY);
        var b = AgentConfig.create("a", "t", Map.of(), List.of(UNIVERSE.get(1), UNIVERSE.get(0)),
                new BigDecimal("40"), List.of(), TradingMode.DEMO, POLICY);
        assertThat(a).isNotEqualTo(b); // buy-and-hold uses the first one
    }

    @Test
    void equalityIgnoresTheScaleOfNumbers() {
        var a = AgentConfig.create("a", "t", Map.of("w", new BigDecimal("200")), UNIVERSE, new BigDecimal("40"),
                List.of(new EscalationStep(30, new BigDecimal("60"))), TradingMode.DEMO, POLICY);
        var b = AgentConfig.create("a", "t", Map.of("w", new BigDecimal("200.0000000000")), UNIVERSE,
                new BigDecimal("40.000000"), List.of(new EscalationStep(30, new BigDecimal("60.00"))),
                TradingMode.DEMO, POLICY);
        assertThat(b).isEqualTo(a).hasSameHashCodeAs(a);
        assertThat(agent("x", new BigDecimal("400")).capital()).isEqualTo(new BigDecimal("400"));
    }

    @Test
    void stopAndResumeAreIdempotentAndReturnNewValues() {
        var a = agent("a", new BigDecimal("40"));
        var stopped = a.stopped();
        assertThat(stopped.state()).isEqualTo(AgentRunState.STOPPED);
        assertThat(stopped.stopped()).isEqualTo(stopped);
        assertThat(a.state()).isEqualTo(AgentRunState.ACTIVE);
        assertThat(stopped.resumed()).isEqualTo(a);
        assertThat(a.resumed()).isEqualTo(a);
    }

    @Test
    void changesCapitalOnlyWithinThePolicy() {
        var a = agent("a", new BigDecimal("40"));
        assertThat(a.withCapital(new BigDecimal("60"), POLICY).capital()).isEqualByComparingTo("60");
        assertThatThrownBy(() -> a.withCapital(new BigDecimal("30"), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(a.capital()).isEqualByComparingTo("40");
    }

    @Test
    void acceptsIncreasingEscalationSteps() {
        var a = AgentConfig.create("a", "trend", Map.of(), UNIVERSE, new BigDecimal("40"),
                List.of(new EscalationStep(30, new BigDecimal("60")), new EscalationStep(60, new BigDecimal("100"))),
                TradingMode.DEMO, POLICY);
        assertThat(a.steps()).hasSize(2);
    }

    @Test
    void rejectsStepsOutOfOrderNotAboveTheCapitalOrBelowThePolicy() {
        var late = new EscalationStep(60, new BigDecimal("100"));
        var early = new EscalationStep(30, new BigDecimal("60"));
        assertThatThrownBy(() -> AgentConfig.create("a", "t", Map.of(), UNIVERSE, new BigDecimal("40"), List.of(late, early),
                TradingMode.DEMO, POLICY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentConfig.create("a", "t", Map.of(), UNIVERSE, new BigDecimal("40"),
                List.of(new EscalationStep(30, new BigDecimal("40"))), TradingMode.DEMO, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EscalationStep(0, new BigDecimal("60")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
