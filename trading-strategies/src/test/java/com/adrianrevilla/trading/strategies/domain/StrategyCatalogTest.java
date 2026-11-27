package com.adrianrevilla.trading.strategies.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.Strategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyCatalogTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");
    private final StrategyCatalog catalog = new StrategyCatalog(BuiltinStrategies.all());

    @Test
    void listsTheFiveBuiltinStrategiesInAStableOrder() {
        assertThat(catalog.list()).extracting(StrategyDefinition::id)
                .containsExactly("buy-and-hold", "momentum", "swing", "trend", "vol-target");
    }

    @Test
    void everyBuiltinCreatesAStrategyWithTheDefaultsAndTheAgentId() {
        for (var def : catalog.list()) {
            Strategy s = catalog.create("my-agent", def.id(), List.of(A, B), Map.of());
            assertThat(s.id()).as(def.id()).isEqualTo("my-agent");
        }
    }

    @Test
    void defaultsFillTheMissingParamsAndTheGivenOnesWin() {
        var resolved = catalog.resolveParams("trend", Map.of("window", new BigDecimal("100")));
        assertThat(resolved).containsEntry("window", new BigDecimal("100"));
        assertThat(resolved.get("band")).isEqualByComparingTo("0.01");
    }

    @Test
    void unknownStrategyUnknownParamAndOutOfRangeAreRejectedWithClearMessages() {
        assertThatThrownBy(() -> catalog.resolveParams("magic", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("magic");
        assertThatThrownBy(() -> catalog.resolveParams("trend", Map.of("nope", BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> catalog.resolveParams("trend", Map.of("window", new BigDecimal("5"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window");
        assertThatThrownBy(() -> catalog.resolveParams("trend", Map.of("window", new BigDecimal("100.5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyUniverseIsRejectedAndBuyAndHoldUsesTheFirstIsin() {
        assertThatThrownBy(() -> catalog.create("a", "trend", List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.create("a", "buy-and-hold", List.of(B, A), Map.of())).isInstanceOf(BuyAndHold.class);
    }

    @Test
    void topNAndMaxPositionsAreCappedByTheUniverseSize() {
        // 3 and 4 by default, but the universe has only 2 instruments: it must still be built, not fail
        assertThat(catalog.create("m", "momentum", List.of(A, B), Map.of())).isNotNull();
        assertThat(catalog.create("s", "swing", List.of(A, B), Map.of())).isNotNull();
    }

    @Test
    void duplicateIdsAreRejectedWhenBuildingTheCatalog() {
        var all = new java.util.ArrayList<>(BuiltinStrategies.all());
        all.add(all.get(0));
        assertThatThrownBy(() -> new StrategyCatalog(all)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewStrategyIsJustAnotherDefinitionAndShowsUpInTheCatalog() {
        StrategyDefinition custom = new StrategyDefinition() {
            public String id() { return "custom"; }
            public String name() { return "Custom"; }
            public String description() { return "test"; }
            public List<ParamSpec> params() { return List.of(ParamSpec.integer("n", "n", 5, 1, 10)); }
            public Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> params) {
                return new BuyAndHold(agentId, universe.get(0));
            }
        };
        var all = new java.util.ArrayList<>(BuiltinStrategies.all());
        all.add(custom);
        var extended = new StrategyCatalog(all);
        assertThat(extended.list()).extracting(StrategyDefinition::id).contains("custom");
        assertThat(extended.resolveParams("custom", Map.of())).containsEntry("n", new BigDecimal("5"));
    }
}
