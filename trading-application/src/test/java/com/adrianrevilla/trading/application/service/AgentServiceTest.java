package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.application.port.in.ManageAgents.AgentAlreadyExistsException;
import com.adrianrevilla.trading.application.port.in.ManageAgents.AgentNotFoundException;
import com.adrianrevilla.trading.application.port.in.ManageAgents.CreateAgent;
import com.adrianrevilla.trading.application.port.in.ManageAgents.StepSpec;
import com.adrianrevilla.trading.application.port.in.ManageAgents.UpdateAgent;
import com.adrianrevilla.trading.application.read.AgentView;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.strategies.domain.BuiltinStrategies;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentServiceTest {
    private static final String A = "IE00B4L5Y983";
    private static final String B = "IE00B5BMR087";
    private static final String UNMAPPED = "IE00BKM4GZ66";

    private InMemoryAgentStore store;
    private AgentService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentStore();
        service = new AgentService(store, new StrategyCatalog(BuiltinStrategies.all()),
                new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25")), Set.of(new Isin(A), new Isin(B)));
    }

    private static CreateAgent create(String id) {
        return new CreateAgent(id, "trend", Map.of("window", new BigDecimal("100")), List.of(A, B),
                new BigDecimal("40"), List.of());
    }

    @Test
    void createsAnAgentWithTheCompleteParamsAndWritesTheAudit() {
        AgentView v = service.create(create("t1"), "alice");

        assertThat(v.id()).isEqualTo("t1");
        assertThat(v.state()).isEqualTo("ACTIVE");
        assertThat(v.mode()).isEqualTo("DEMO");
        assertThat(v.params()).containsEntry("window", new BigDecimal("100")).containsKey("band"); // default filled
        assertThat(v.universe()).containsExactly(A, B);
        assertThat(store.audit("t1")).hasSize(1);
        assertThat(store.audit("t1").get(0).changedBy()).isEqualTo("alice");
    }

    @Test
    void aMissingStrategyOrCapitalIsABadRequestNotACrash() {
        assertThatThrownBy(() -> service.create(new CreateAgent("x1", null, Map.of(), List.of(A), new BigDecimal("40"), List.of()), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strategyId");
        assertThatThrownBy(() -> service.create(new CreateAgent("x1", " ", Map.of(), List.of(A), new BigDecimal("40"), List.of()), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CreateAgent("x1", "trend", Map.of(), List.of(A), null, List.of()), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capital");
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void rejectsADuplicateIdWithAConflictNotAnOverwrite() {
        service.create(create("t1"), "alice");
        assertThatThrownBy(() -> service.create(create("t1"), "bob")).isInstanceOf(AgentAlreadyExistsException.class);
        assertThat(store.audit("t1")).hasSize(1);
    }

    @Test
    void rejectsBadInputWithClearMessagesAndStoresNothing() {
        assertThatThrownBy(() -> service.create(new CreateAgent("t1", "magic", Map.of(), List.of(A),
                new BigDecimal("40"), List.of()), "a")).hasMessageContaining("magic");
        assertThatThrownBy(() -> service.create(new CreateAgent("t1", "trend", Map.of("nope", BigDecimal.ONE),
                List.of(A), new BigDecimal("40"), List.of()), "a")).hasMessageContaining("nope");
        assertThatThrownBy(() -> service.create(new CreateAgent("t1", "trend", Map.of(), List.of(UNMAPPED),
                new BigDecimal("40"), List.of()), "a")).hasMessageContaining(UNMAPPED);
        assertThatThrownBy(() -> service.create(new CreateAgent("t1", "trend", Map.of(), List.of("not-an-isin"),
                new BigDecimal("40"), List.of()), "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CreateAgent("t1", "trend", Map.of(), List.of(A),
                new BigDecimal("39"), List.of()), "a")).hasMessageContaining("40");
        assertThatThrownBy(() -> service.create(new CreateAgent("t1", "trend", Map.of(), List.of(A), null, List.of()),
                "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CreateAgent("Bad Id", "trend", Map.of(), List.of(A),
                new BigDecimal("40"), List.of()), "a")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void anActorIsRequiredForTheAudit() {
        assertThatThrownBy(() -> service.create(create("t1"), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void createsWithEscalationStepsAndRejectsBadOnes() {
        var ok = new CreateAgent("t1", "trend", Map.of(), List.of(A), new BigDecimal("40"),
                List.of(new StepSpec(30, new BigDecimal("60")), new StepSpec(60, new BigDecimal("100"))));
        assertThat(service.create(ok, "a").steps()).hasSize(2);

        var backwards = new CreateAgent("t2", "trend", Map.of(), List.of(A), new BigDecimal("40"),
                List.of(new StepSpec(30, new BigDecimal("40"))));
        assertThatThrownBy(() -> service.create(backwards, "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stopAndResumeKeepEverythingElseAndAreIdempotent() {
        service.create(create("t1"), "alice");

        assertThat(service.stop("t1", "bob").state()).isEqualTo("STOPPED");
        assertThat(service.stop("t1", "bob").state()).isEqualTo("STOPPED");
        assertThat(store.findActive()).isEmpty();
        assertThat(service.resume("t1", "bob").state()).isEqualTo("ACTIVE");
        assertThat(store.find("t1").orElseThrow().capital()).isEqualByComparingTo("40");
        // created + stopped + resumed: the second stop changed nothing and wrote no audit row
        assertThat(store.audit("t1")).hasSize(3);
    }

    @Test
    void stopResumeAndUpdateOfAMissingAgentAreNotFound() {
        assertThatThrownBy(() -> service.stop("nobody", "a")).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> service.resume("nobody", "a")).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> service.update("nobody", new UpdateAgent(null, null, null, null), "a"))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void updateChangesOnlyWhatIsGivenAndKeepsTheStateAndTheStrategy() {
        service.create(create("t1"), "alice");
        service.stop("t1", "alice");

        AgentView v = service.update("t1", new UpdateAgent(Map.of("window", new BigDecimal("150")), List.of(B),
                new BigDecimal("80"), null), "bob");

        assertThat(v.params()).containsEntry("window", new BigDecimal("150"));
        assertThat(v.universe()).containsExactly(B);
        assertThat(v.capital()).isEqualByComparingTo("80");
        assertThat(v.state()).isEqualTo("STOPPED");
        assertThat(v.strategyId()).isEqualTo("trend");

        AgentView same = service.update("t1", new UpdateAgent(null, null, null, null), "bob");
        assertThat(same).isEqualTo(v);
    }

    @Test
    void updateIsAllOrNothingWhenOnePartIsInvalid() {
        service.create(create("t1"), "alice");
        var before = store.find("t1").orElseThrow();

        assertThatThrownBy(() -> service.update("t1", new UpdateAgent(Map.of("window", new BigDecimal("150")), null,
                new BigDecimal("5"), null), "bob")).isInstanceOf(IllegalArgumentException.class);

        assertThat(store.find("t1").orElseThrow()).isEqualTo(before);
    }

    @Test
    void emptyListsReplaceAndNullKeeps() {
        service.create(new CreateAgent("t1", "trend", Map.of(), List.of(A), new BigDecimal("40"),
                List.of(new StepSpec(30, new BigDecimal("60")))), "a");

        assertThat(service.update("t1", new UpdateAgent(null, null, null, null), "a").steps()).hasSize(1);
        assertThat(service.update("t1", new UpdateAgent(null, null, null, List.of()), "a").steps()).isEmpty();
        assertThatThrownBy(() -> service.update("t1", new UpdateAgent(null, List.of(), null, null), "a"))
                .isInstanceOf(IllegalArgumentException.class); // an agent cannot lose its whole universe
    }

    @Test
    void queriesListInIdOrderAndExposeTheAuditAndTheCatalog() {
        service.create(create("b"), "a");
        service.create(create("a"), "a");

        assertThat(service.list()).extracting(AgentView::id).containsExactly("a", "b");
        assertThat(service.find("a")).isPresent();
        assertThat(service.find("zzz")).isEmpty();
        assertThat(service.audit("a")).hasSize(1);
        assertThatThrownBy(() -> service.audit("zzz")).isInstanceOf(AgentNotFoundException.class);
        assertThat(service.strategies()).extracting(s -> s.id())
                .containsExactly("buy-and-hold", "momentum", "swing", "trend", "vol-target");
        assertThat(service.strategies().stream().filter(s -> s.id().equals("trend")).findFirst().orElseThrow().params())
                .extracting(p -> p.name()).containsExactly("window", "band");
        assertThat(service.instruments()).containsExactlyInAnyOrder(A, B);
        assertThat(service.limits().minimumCapital()).isEqualByComparingTo("40");
        assertThat(service.limits().minOrderAmount()).isEqualByComparingTo("10");
        assertThat(service.limits().maxWeightPerAsset()).isEqualByComparingTo("0.25");
    }

    // ---- found by mutation testing

    @Test
    void everyCommandNeedsAnActorForTheAuditTrail() {
        service.create(create("t1"), "alice");
        assertThatThrownBy(() -> service.update("t1", new UpdateAgent(null, null, new BigDecimal("50"), null), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.stop("t1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resume("t1", "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.find("t1").orElseThrow().state()).isEqualTo("ACTIVE");
        assertThat(service.find("t1").orElseThrow().capital()).isEqualByComparingTo("40");
    }

    @Test
    void theAuditAndTheViewCarryEveryValue() {
        service.create(new CreateAgent("t2", "trend", Map.of("window", new BigDecimal("100")), List.of(A),
                new BigDecimal("40"), List.of(new StepSpec(30, new BigDecimal("60")))), "alice");
        var audit = service.audit("t2");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).agentId()).isEqualTo("t2");
        assertThat(audit.get(0).actor()).isEqualTo("alice");
        assertThat(audit.get(0).field()).isEqualTo("created");
        assertThat(audit.get(0).newValue()).isEqualTo("trend");
        var view = service.find("t2").orElseThrow();
        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().get(0).afterDays()).isEqualTo(30);
        assertThat(view.steps().get(0).capital()).isEqualByComparingTo("60");
        assertThat(view.universe()).containsExactly(A);
    }
}
