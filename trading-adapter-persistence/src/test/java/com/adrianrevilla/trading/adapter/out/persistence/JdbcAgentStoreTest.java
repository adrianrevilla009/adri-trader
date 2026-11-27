package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.application.port.out.AgentStore.AgentAuditEntry;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.EscalationStep;
import com.adrianrevilla.trading.core.domain.TradingMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcAgentStoreTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final CapitalPolicy POLICY = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25"));

    private PGSimpleDataSource ds;
    private JdbcAgentStore store;

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        store = new JdbcAgentStore(ds, POLICY);
    }

    private static AgentConfig trend(String id) {
        return AgentConfig.create(id, "trend", Map.of("window", new BigDecimal("200"), "band", new BigDecimal("0.01")),
                new BigDecimal("40"), List.of(new EscalationStep(30, new BigDecimal("60"))), TradingMode.DEMO, POLICY);
    }

    @Test
    void savesAndReadsBackEverything() {
        store.save(trend("a1"), "tester");

        var got = store.find("a1").orElseThrow();
        assertThat(got).isEqualTo(trend("a1"));
        assertThat(got.steps()).containsExactly(new EscalationStep(30, new BigDecimal("60")));
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(store.find("nobody")).isEmpty();
    }

    @Test
    void listsAllAndOnlyTheActiveOnesInOrder() {
        store.save(trend("b"), "t");
        store.save(trend("a"), "t");
        store.save(trend("c").stopped(), "t");

        assertThat(store.findAll()).extracting(AgentConfig::id).containsExactly("a", "b", "c");
        assertThat(store.findActive()).extracting(AgentConfig::id).containsExactly("a", "b");
    }

    @Test
    void emptyStoreListsNothing() {
        assertThat(store.findAll()).isEmpty();
        assertThat(store.findActive()).isEmpty();
    }

    @Test
    void auditsCreationAndEachChangedField() {
        store.save(trend("a1"), "alice");
        store.save(trend("a1").stopped(), "bob");
        store.save(trend("a1").stopped().withCapital(new BigDecimal("50"), POLICY), "carol");

        List<AgentAuditEntry> audit = store.audit("a1");
        assertThat(audit).extracting(AgentAuditEntry::field).containsExactly("created", "state", "capital");
        assertThat(audit.get(0).oldValue()).isNull();
        assertThat(audit.get(1)).isEqualTo(new AgentAuditEntry("a1", "bob", "state", "ACTIVE", "STOPPED"));
        assertThat(audit.get(2).changedBy()).isEqualTo("carol");
        assertThat(new BigDecimal(audit.get(2).oldValue())).isEqualByComparingTo("40");
        assertThat(new BigDecimal(audit.get(2).newValue())).isEqualByComparingTo("50");
    }

    @Test
    void savingTheSameAgentAgainWritesNoAudit() {
        store.save(trend("a1"), "alice");
        store.save(trend("a1"), "alice");

        assertThat(store.audit("a1")).hasSize(1);
    }

    @Test
    void replacingParamsAndStepsIsAuditedAndStored() {
        store.save(trend("a1"), "alice");
        var changed = AgentConfig.create("a1", "trend", Map.of("window", new BigDecimal("100")),
                new BigDecimal("40"), List.of(), TradingMode.DEMO, POLICY);
        store.save(changed, "alice");

        assertThat(store.find("a1").orElseThrow()).isEqualTo(changed);
        assertThat(store.audit("a1")).extracting(AgentAuditEntry::field).containsExactly("created", "params", "steps");
    }

    @Test
    void auditTableRejectsUpdateAndDelete() {
        store.save(trend("a1"), "alice");
        var jdbc = new JdbcTemplate(ds);

        assertThatThrownBy(() -> jdbc.update("UPDATE agent_config_audit SET changed_by = 'x'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM agent_config_audit"))
                .hasMessageContaining("append-only");
    }

    @Test
    void rejectsBlankAuthorAndNullAgent() {
        assertThatThrownBy(() -> store.save(trend("a1"), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(null, "alice")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aStoredRowThatBreaksTheDomainRulesFailsLoudlyOnRead() {
        store.save(trend("a1"), "alice");
        new JdbcTemplate(ds).update("UPDATE agent SET capital = 5 WHERE id = 'a1'");

        assertThatThrownBy(() -> store.find("a1")).isInstanceOf(IllegalArgumentException.class);
    }
}
