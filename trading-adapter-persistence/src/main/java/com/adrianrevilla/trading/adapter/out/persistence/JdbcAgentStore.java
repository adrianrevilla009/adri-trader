package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.AgentRunState;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.EscalationStep;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.TradingMode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC adapter for {@link AgentStore} (ADR-0030). A save is one transaction: the agent, its params, its steps and the
 * audit rows are written together or not at all. Rows are rebuilt through the domain, so a row that breaks the
 * rules (edited by hand in the database) fails loudly instead of being used.
 */
public class JdbcAgentStore implements AgentStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CapitalPolicy policy;

    public JdbcAgentStore(DataSource dataSource, CapitalPolicy policy) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public void save(AgentConfig agent, String changedBy) {
        Objects.requireNonNull(agent, "agent");
        if (changedBy == null || changedBy.isBlank()) {
            throw new IllegalArgumentException("changedBy is required for the audit trail");
        }
        tx.executeWithoutResult(status -> {
            // Lock the row so two saves of the same agent cannot interleave their audit rows.
            jdbc.queryForList("SELECT id FROM agent WHERE id = ? FOR UPDATE", agent.id());
            AgentConfig old = find(agent.id()).orElse(null);
            if (old == null) {
                insert(agent);
                audit(agent.id(), changedBy, "created", null, agent.strategyId());
                return;
            }
            if (old.equals(agent)) {
                return;
            }
            writeChangedFields(old, agent, changedBy);
            jdbc.update("""
                    UPDATE agent SET strategy_id = ?, capital = ?, mode = ?, state = ?, updated_at = now()
                    WHERE id = ?""", agent.strategyId(), agent.capital(), agent.mode().name(),
                    agent.state().name(), agent.id());
            if (!paramsText(old).equals(paramsText(agent))) {
                jdbc.update("DELETE FROM agent_param WHERE agent_id = ?", agent.id());
                insertParams(agent);
            }
            if (!universeText(old).equals(universeText(agent))) {
                jdbc.update("DELETE FROM agent_universe WHERE agent_id = ?", agent.id());
                insertUniverse(agent);
            }
            if (!stepsText(old).equals(stepsText(agent))) {
                jdbc.update("DELETE FROM agent_step WHERE agent_id = ?", agent.id());
                insertSteps(agent);
            }
        });
    }

    private void writeChangedFields(AgentConfig old, AgentConfig now, String by) {
        if (!old.strategyId().equals(now.strategyId())) {
            audit(now.id(), by, "strategy", old.strategyId(), now.strategyId());
        }
        if (old.capital().compareTo(now.capital()) != 0) {
            audit(now.id(), by, "capital", old.capital().toPlainString(), now.capital().toPlainString());
        }
        if (old.state() != now.state()) {
            audit(now.id(), by, "state", old.state().name(), now.state().name());
        }
        if (old.mode() != now.mode()) {
            audit(now.id(), by, "mode", old.mode().name(), now.mode().name());
        }
        if (!paramsText(old).equals(paramsText(now))) {
            audit(now.id(), by, "params", paramsText(old), paramsText(now));
        }
        if (!universeText(old).equals(universeText(now))) {
            audit(now.id(), by, "universe", universeText(old), universeText(now));
        }
        if (!stepsText(old).equals(stepsText(now))) {
            audit(now.id(), by, "steps", stepsText(old), stepsText(now));
        }
    }

    private void insert(AgentConfig a) {
        jdbc.update("INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES (?, ?, ?, ?, ?)",
                a.id(), a.strategyId(), a.capital(), a.mode().name(), a.state().name());
        insertParams(a);
        insertUniverse(a);
        insertSteps(a);
    }

    private void insertParams(AgentConfig a) {
        a.params().forEach((name, value) ->
                jdbc.update("INSERT INTO agent_param (agent_id, name, value) VALUES (?, ?, ?)", a.id(), name, value));
    }

    private void insertUniverse(AgentConfig a) {
        int position = 0;
        for (Isin isin : a.universe()) {
            jdbc.update("INSERT INTO agent_universe (agent_id, position, isin) VALUES (?, ?, ?)", a.id(), position++,
                    isin.value());
        }
    }

    private void insertSteps(AgentConfig a) {
        int position = 0;
        for (EscalationStep s : a.steps()) {
            jdbc.update("INSERT INTO agent_step (agent_id, position, after_days, capital) VALUES (?, ?, ?, ?)",
                    a.id(), position++, s.afterDays(), s.capital());
        }
    }

    private void audit(String agentId, String by, String field, String oldValue, String newValue) {
        jdbc.update("""
                INSERT INTO agent_config_audit (agent_id, changed_by, field, old_value, new_value)
                VALUES (?, ?, ?, ?, ?)""", agentId, by, field, oldValue, newValue);
    }

    /** Stable text of the params (sorted by name, numbers without trailing zeros) used to compare and to audit. */
    private static String paramsText(AgentConfig a) {
        return new TreeMap<>(a.params()).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    }

    private static String universeText(AgentConfig a) {
        return a.universe().stream().map(Isin::value).collect(Collectors.joining(","));
    }

    private static String stepsText(AgentConfig a) {
        return a.steps().stream()
                .map(s -> s.afterDays() + ":" + s.capital().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    }

    @Override
    public Optional<AgentConfig> find(String id) {
        List<AgentConfig> found = query("WHERE id = ?", id);
        return found.stream().findFirst();
    }

    @Override
    public List<AgentConfig> findAll() {
        return query("");
    }

    @Override
    public List<AgentConfig> findActive() {
        return query("WHERE state = 'ACTIVE'");
    }

    private List<AgentConfig> query(String where, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, strategy_id, capital, mode, state FROM agent " + where + " ORDER BY id", args);
        List<AgentConfig> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            out.add(AgentConfig.restore(id, (String) r.get("strategy_id"), params(id), universe(id),
                    (BigDecimal) r.get("capital"), steps(id), TradingMode.valueOf((String) r.get("mode")),
                    AgentRunState.valueOf((String) r.get("state")), policy));
        }
        return out;
    }

    private Map<String, BigDecimal> params(String id) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("SELECT name, value FROM agent_param WHERE agent_id = ? ORDER BY name",
                rs -> {
                    out.put(rs.getString(1), rs.getBigDecimal(2));
                }, id);
        return out;
    }

    private List<Isin> universe(String id) {
        return jdbc.query("SELECT isin FROM agent_universe WHERE agent_id = ? ORDER BY position",
                (rs, i) -> new Isin(rs.getString(1).trim()), id);
    }

    private List<EscalationStep> steps(String id) {
        return jdbc.query("SELECT after_days, capital FROM agent_step WHERE agent_id = ? ORDER BY position",
                (rs, i) -> new EscalationStep(rs.getInt(1), rs.getBigDecimal(2)), id);
    }

    @Override
    public List<AgentAuditEntry> audit(String agentId) {
        return jdbc.query("""
                        SELECT agent_id, changed_by, field, old_value, new_value FROM agent_config_audit
                        WHERE agent_id = ? ORDER BY id""",
                (rs, i) -> new AgentAuditEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), agentId);
    }
}
