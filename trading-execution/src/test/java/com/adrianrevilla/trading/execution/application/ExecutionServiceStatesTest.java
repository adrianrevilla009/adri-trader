package com.adrianrevilla.trading.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.BrokerAccessDeniedException;
import com.adrianrevilla.trading.core.domain.BrokerException;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.BrokerRejectedException;
import com.adrianrevilla.trading.core.domain.BrokerUnavailableException;
import com.adrianrevilla.trading.core.domain.InMemoryKillSwitch;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderRequest.Side;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.adapter.out.dryrun.DryRunBroker;
import com.adrianrevilla.trading.execution.adapter.out.memory.InMemoryOrderRepository;
import com.adrianrevilla.trading.execution.application.ExecutionService.SendOutcome;
import com.adrianrevilla.trading.execution.domain.OrderStateMachine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the order flow leaves behind for every broker answer: the stored state, the alert, the kill switch and the
 * returned outcome, with a broker that does exactly what the test says. Pinned by mutation testing: removing any of
 * these effects used to go unnoticed.
 */
class ExecutionServiceStatesTest {
    private static final Isin A = new Isin("IE00B4L5Y983");

    /** A broker whose every answer the test decides. */
    private static final class Scripted implements Broker {
        BrokerException submitFails;
        BrokerException lookupFails;
        BrokerException positionsFail;
        Optional<BrokerOrder> lookup = Optional.empty();
        Map<Isin, BigDecimal> positions = Map.of();

        public void submit(OrderRequest r) throws BrokerException {
            if (submitFails != null) {
                throw submitFails;
            }
        }

        public void cancel(UUID id) { }

        public Optional<BrokerOrder> lookup(UUID id) throws BrokerException {
            if (lookupFails != null) {
                throw lookupFails;
            }
            return lookup;
        }

        public Map<Isin, BigDecimal> positions() throws BrokerException {
            if (positionsFail != null) {
                throw positionsFail;
            }
            return positions;
        }

        public BigDecimal cash() {
            return new BigDecimal("1000");
        }
    }

    private final Scripted broker = new Scripted();
    private final InMemoryOrderRepository repo = new InMemoryOrderRepository();
    private final InMemoryKillSwitch kill = new InMemoryKillSwitch();
    private final List<String> alerts = new ArrayList<>();
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionService(broker, repo, kill, (level, msg) -> alerts.add(level + ":" + msg),
                new Reconciler(new BigDecimal("0.000001")));
    }

    private static OrderRequest buy() {
        return new OrderRequest(UUID.randomUUID(), "agent", A, Side.BUY, new BigDecimal("10"), null);
    }

    private OrderState stored(OrderRequest o) {
        return repo.find(o.internalId()).orElseThrow().state();
    }

    // ---- sending

    @Test
    void anAcceptedOrderIsStoredAsSubmittedAndNothingIsAlerted() {
        var o = buy();
        assertThat(service.send(o)).isEqualTo(SendOutcome.SUBMITTED);
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED);
        assertThat(alerts).isEmpty();
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void aRejectedOrderIsStoredAsRejectedWithTheReasonAndDoesNotHalt() {
        broker.submitFails = new BrokerRejectedException("minimum order is 10");
        var o = buy();
        assertThat(service.send(o)).isEqualTo(SendOutcome.REJECTED);
        assertThat(stored(o)).isEqualTo(OrderState.REJECTED);
        assertThat(repo.find(o.internalId()).orElseThrow().note()).isEqualTo("minimum order is 10");
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void deniedAccessIsStoredAsRejectedAndHaltsAllTradingCritically() {
        broker.submitFails = new BrokerAccessDeniedException("401");
        var o = buy();
        assertThat(service.send(o)).isEqualTo(SendOutcome.REJECTED);
        assertThat(stored(o)).isEqualTo(OrderState.REJECTED);
        assertThat(kill.isActive()).isTrue();
        assertThat(kill.reason()).isEqualTo("Broker denied access: 401");
        assertThat(alerts).containsExactly("CRITICAL:TRADING HALTED: Broker denied access: 401");
    }

    @Test
    void anUnavailableBrokerLeavesTheOrderOpenAsUnknownAndWarns() {
        broker.submitFails = new BrokerUnavailableException("timeout");
        var o = buy();
        assertThat(service.send(o)).isEqualTo(SendOutcome.UNKNOWN);
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED);
        assertThat(OrderStateMachine.isOpen(stored(o))).isTrue();
        assertThat(repo.find(o.internalId()).orElseThrow().note()).isEqualTo(ExecutionService.UNKNOWN_NOTE);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).startsWith("WARNING:Order " + o.internalId() + " outcome unknown");
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void anyOtherBrokerFailureAlsoLeavesTheOrderOpenAsUnknown() {
        broker.submitFails = new BrokerException("something odd");
        var o = buy();
        assertThat(service.send(o)).isEqualTo(SendOutcome.UNKNOWN);
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED);
        assertThat(repo.find(o.internalId()).orElseThrow().note()).isEqualTo(ExecutionService.UNKNOWN_NOTE);
    }

    @Test
    void withTheKillSwitchOnNothingIsStoredOrSent() {
        kill.activate("manual");
        var o = buy();
        assertThat(service.send(o)).isEqualTo(SendOutcome.BLOCKED);
        assertThat(repo.find(o.internalId())).isEmpty();
    }

    // ---- syncing open orders

    private OrderRequest openOrder() {
        var o = buy();
        service.send(o);
        return o;
    }

    @Test
    void syncRecordsAFillMovesTheStateAndCountsTheChange() {
        var o = openOrder();
        broker.lookup = Optional.of(new BrokerOrder(o.internalId(), OrderState.FILLED, new BigDecimal("10"),
                new BigDecimal("1"), new BigDecimal("10")));
        assertThat(service.syncOpenOrders()).isEqualTo(1);
        assertThat(stored(o)).isEqualTo(OrderState.FILLED);
        assertThat(repo.recordedFillAmount(o.internalId())).isEqualByComparingTo("10");
        assertThat(repo.netUnits("agent").get(A)).isEqualByComparingTo("1");
        assertThat(service.syncOpenOrders()).isZero(); // a filled order is no longer open: nothing left to change
    }

    @Test
    void syncOfAnAlreadyRecordedStateChangesNothing() {
        var o = openOrder();
        broker.lookup = Optional.of(new BrokerOrder(o.internalId(), OrderState.SUBMITTED, BigDecimal.ZERO,
                BigDecimal.ZERO, null));
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED);
        assertThat(alerts).isEmpty();
    }

    @Test
    void aPartialFillThenTheRestIsAccumulatedNotDoubled() {
        var o = openOrder();
        broker.lookup = Optional.of(new BrokerOrder(o.internalId(), OrderState.PARTIALLY_FILLED, new BigDecimal("4"),
                new BigDecimal("0.4"), new BigDecimal("10")));
        assertThat(service.syncOpenOrders()).isEqualTo(1);
        assertThat(service.syncOpenOrders()).isZero(); // the same answer again changes nothing
        broker.lookup = Optional.of(new BrokerOrder(o.internalId(), OrderState.FILLED, new BigDecimal("10"),
                new BigDecimal("1"), new BigDecimal("10")));
        assertThat(service.syncOpenOrders()).isEqualTo(1);
        assertThat(repo.recordedFillAmount(o.internalId())).isEqualByComparingTo("10");
    }

    @Test
    void anOrderTheBrokerDoesNotKnowHaltsTradingAndIsNeverResent() {
        var o = openOrder();
        broker.lookup = Optional.empty();
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(kill.isActive()).isTrue();
        assertThat(kill.reason()).contains("unknown to the broker").contains(o.internalId().toString());
        assertThat(alerts).hasSize(1).allMatch(a -> a.startsWith("CRITICAL:TRADING HALTED"));
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED); // untouched: a person decides
    }

    @Test
    void anIllegalTransitionFromTheBrokerHaltsTradingAndKeepsTheStoredState() {
        var o = openOrder();
        broker.lookup = Optional.of(new BrokerOrder(o.internalId(), OrderState.CREATED, BigDecimal.ZERO,
                BigDecimal.ZERO, null));
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(kill.isActive()).isTrue();
        assertThat(kill.reason()).contains("illegal transition SUBMITTED -> CREATED");
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED);
    }

    @Test
    void aBrokerThatCannotBeAskedIsRetriedLaterWithoutGuessing() {
        var o = openOrder();
        broker.lookupFails = new BrokerUnavailableException("down");
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(kill.isActive()).isFalse();
        assertThat(stored(o)).isEqualTo(OrderState.SUBMITTED);
    }

    // ---- reconciliation

    @Test
    void reconciliationOkChangesNothing() {
        assertThat(service.reconcile("agent").ok()).isTrue();
        assertThat(service.reconcileAll().ok()).isTrue();
        assertThat(kill.isActive()).isFalse();
        assertThat(alerts).isEmpty();
    }

    @Test
    void aDifferenceHaltsTradingNamesWhoAndAlertsCritically() {
        broker.positions = Map.of(A, new BigDecimal("3"));
        var report = service.reconcile("agent");
        assertThat(report.ok()).isFalse();
        assertThat(report.differences()).hasSize(1);
        assertThat(kill.reason()).startsWith("Reconciliation difference for agent agent:");
        assertThat(alerts).hasSize(1).allMatch(a -> a.startsWith("CRITICAL:TRADING HALTED: Reconciliation difference"));

        kill.deactivate();
        alerts.clear();
        assertThat(service.reconcileAll().ok()).isFalse();
        assertThat(kill.reason()).startsWith("Reconciliation difference for all agents:");
    }

    @Test
    void anUnreachableBrokerMakesTheReportNotOkWithAWarningAndWithoutHalting() {
        broker.positionsFail = new BrokerUnavailableException("down");
        var report = service.reconcileAll();
        assertThat(report.ok()).isFalse();
        assertThat(report.differences().get(0).what()).isEqualTo("broker unreachable");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).isEqualTo("WARNING:Reconciliation not possible: down");
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void theToleranceIsInclusive() {
        var tolerant = new Reconciler(new BigDecimal("0.5"));
        assertThat(tolerant.compare(Map.of(A, BigDecimal.ONE), Map.of(A, new BigDecimal("1.5"))).ok()).isTrue();
        assertThat(tolerant.compare(Map.of(A, BigDecimal.ONE), Map.of(A, new BigDecimal("1.51"))).ok()).isFalse();
        assertThat(tolerant.compare(Map.of(), Map.of(A, new BigDecimal("0.6"))).differences()).hasSize(1);
    }

    // ---- the state machine and the dry-run broker

    @ParameterizedTest
    @EnumSource(OrderState.class)
    void terminalStatesAreExactlyTheOnesThatNeverChangeAndOpenOnesAreTheLiveOnes(OrderState s) {
        boolean terminal = s == OrderState.FILLED || s == OrderState.CANCELLED || s == OrderState.REJECTED;
        assertThat(OrderStateMachine.isTerminal(s)).isEqualTo(terminal);
        assertThat(OrderStateMachine.isOpen(s)).isEqualTo(s == OrderState.SUBMITTED || s == OrderState.PARTIALLY_FILLED);
    }

    @Test
    void theRepositoryRefusesAnIllegalTransitionAndListsOnlyOpenOrders() {
        var o = openOrder();
        var done = buy();
        service.send(done);
        repo.updateState(done.internalId(), OrderState.REJECTED, "x");
        assertThat(repo.findOpen()).extracting(r -> r.request().internalId()).containsExactly(o.internalId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.updateState(done.internalId(), OrderState.SUBMITTED, "back"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRepositoryRecordsFillsOnlyForTheDifference() {
        var o = openOrder();
        assertThat(repo.recordFillUpTo(o.internalId(), new BigDecimal("4"), new BigDecimal("10"))).isTrue();
        assertThat(repo.recordFillUpTo(o.internalId(), new BigDecimal("4"), new BigDecimal("10"))).isFalse();
        assertThat(repo.recordFillUpTo(o.internalId(), new BigDecimal("3"), new BigDecimal("10"))).isFalse(); // never goes back
        assertThat(repo.recordFillUpTo(o.internalId(), new BigDecimal("10"), null)).isTrue(); // no price: units unknown, amount kept
        assertThat(repo.recordedFillAmount(o.internalId())).isEqualByComparingTo("10");
        assertThat(repo.netUnits("agent").get(A)).isEqualByComparingTo("0.4");
    }

    @Test
    void theDryRunBrokerReadsCashAndPositionsFromTheRealOneButNeverSends() throws Exception {
        broker.positions = Map.of(A, new BigDecimal("2"));
        var dry = new DryRunBroker(broker);
        assertThat(dry.cash()).isEqualByComparingTo("1000");
        assertThat(dry.positions()).containsEntry(A, new BigDecimal("2"));
        broker.submitFails = new BrokerRejectedException("must never be called");
        dry.submit(buy()); // swallowed: it would have thrown if it reached the broker
    }
}
