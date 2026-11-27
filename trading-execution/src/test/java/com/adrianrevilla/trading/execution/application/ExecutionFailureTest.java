package com.adrianrevilla.trading.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.brokersim.domain.FaultInjectingBroker;
import com.adrianrevilla.trading.brokersim.domain.FaultInjectingBroker.Fault;
import com.adrianrevilla.trading.brokersim.domain.SimulatedBroker;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.InMemoryKillSwitch;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderRequest.Side;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.adapter.out.dryrun.DryRunBroker;
import com.adrianrevilla.trading.execution.adapter.out.memory.InMemoryOrderRepository;
import com.adrianrevilla.trading.execution.application.ExecutionService.SendOutcome;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import com.adrianrevilla.trading.execution.domain.OrderStateMachine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Failure tests of phase 3: the system never duplicates an order nor loses track of one. */
class ExecutionFailureTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Map<Isin, BigDecimal> OPEN = Map.of(A, new BigDecimal("10"));

    private SimulatedBroker sim;
    private FaultInjectingBroker broker;
    private InMemoryOrderRepository repo;
    private InMemoryKillSwitch kill;
    private List<String> alerts;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        sim = new SimulatedBroker(isin -> BigDecimal.ZERO, new BigDecimal("1000"));
        broker = new FaultInjectingBroker(sim);
        repo = new InMemoryOrderRepository();
        kill = new InMemoryKillSwitch();
        alerts = new ArrayList<>();
        Alerter alerter = (level, msg) -> alerts.add(level + ":" + msg);
        service = new ExecutionService(broker, repo, kill, alerter, new Reconciler(new BigDecimal("0.000001")));
    }

    private static OrderRequest buy(String amount) {
        return new OrderRequest(UUID.randomUUID(), "agent", A, Side.BUY, new BigDecimal(amount), null);
    }

    @Test
    void happyPathFillsRecordsAndReconciles() {
        var o = buy("100");
        assertThat(service.send(o)).isEqualTo(SendOutcome.SUBMITTED);
        sim.executePending(OPEN);
        assertThat(service.syncOpenOrders()).isEqualTo(1);
        assertThat(repo.find(o.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(repo.netUnits("agent").get(A)).isEqualByComparingTo("10");
        assertThat(service.reconcile("agent").ok()).isTrue();
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void definitiveRejectionMarksRejectedAndCreatesNoFill() {
        broker.failNextSubmit(Fault.REJECT);
        var o = buy("100");
        assertThat(service.send(o)).isEqualTo(SendOutcome.REJECTED);
        assertThat(repo.find(o.internalId()).orElseThrow().state()).isEqualTo(OrderState.REJECTED);
        assertThat(sim.orderCount()).isZero();
    }

    @Test
    void responseLostAfterDeliveryIsResolvedByLookupWithoutResending() {
        broker.failNextSubmit(Fault.TIMEOUT_AFTER_DELIVERY);
        var o = buy("100");
        assertThat(service.send(o)).isEqualTo(SendOutcome.UNKNOWN);
        // an immediate second send of the same order is refused: no duplicate can reach the broker
        assertThatThrownBy(() -> service.send(o)).isInstanceOf(OrderRepository.DuplicateOrderException.class);
        assertThat(sim.orderCount()).isEqualTo(1);

        sim.executePending(OPEN);
        service.syncOpenOrders();
        assertThat(repo.find(o.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(sim.orderCount()).isEqualTo(1);
        assertThat(service.reconcile("agent").ok()).isTrue();
    }

    @Test
    void severalAgentsOnOneAccountReconcileAgainstTheSumOfTheirLedgers() {
        var a = new OrderRequest(UUID.randomUUID(), "agent-a", A, Side.BUY, new BigDecimal("100"), null);
        var b = new OrderRequest(UUID.randomUUID(), "agent-b", A, Side.BUY, new BigDecimal("50"), null);
        service.send(a);
        service.send(b);
        sim.executePending(OPEN);
        service.syncOpenOrders();

        // the account holds 15 units: 10 of agent-a and 5 of agent-b
        assertThat(service.reconcile("agent-a").ok()).isFalse(); // one agent alone cannot explain the account
        kill.deactivate();
        alerts.clear();
        assertThat(service.reconcileAll().ok()).isTrue();
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void aRealDifferenceStillHaltsWhenReconcilingEveryAgent() {
        var a = new OrderRequest(UUID.randomUUID(), "agent-a", A, Side.BUY, new BigDecimal("100"), null);
        service.send(a);
        sim.executePending(OPEN);
        service.syncOpenOrders();
        // the ledger claims one unit more than the broker holds
        repo.recordFill(a.internalId(), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN);

        assertThat(service.reconcileAll().ok()).isFalse();
        assertThat(kill.isActive()).isTrue();
    }

    @Test
    void reconcilingAnEmptyLedgerAgainstAnEmptyAccountIsFine() {
        assertThat(service.reconcileAll().ok()).isTrue();
    }

    @Test
    void orderThatNeverArrivedHaltsTradingInsteadOfBeingResent() {
        broker.failNextSubmit(Fault.TIMEOUT_BEFORE_DELIVERY);
        var o = buy("100");
        assertThat(service.send(o)).isEqualTo(SendOutcome.UNKNOWN);
        service.syncOpenOrders();
        assertThat(kill.isActive()).isTrue();
        assertThat(sim.orderCount()).isZero(); // never resent
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:TRADING HALTED"));
        assertThat(service.send(buy("10"))).isEqualTo(SendOutcome.BLOCKED);
    }

    @Test
    void failingLookupsAreRetriedLaterWithoutGuessing() {
        broker.failNextSubmit(Fault.TIMEOUT_AFTER_DELIVERY);
        var o = buy("100");
        service.send(o);
        sim.executePending(OPEN);
        broker.failNextLookups(2);
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(kill.isActive()).isFalse();
        assertThat(service.syncOpenOrders()).isEqualTo(1);
        assertThat(repo.find(o.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void partialFillsAccumulateUntilFilled() {
        sim.setMaxFillFraction(new BigDecimal("0.4"));
        var o = buy("100");
        service.send(o);
        sim.executePending(OPEN);
        service.syncOpenOrders();
        assertThat(repo.find(o.internalId()).orElseThrow().state()).isEqualTo(OrderState.PARTIALLY_FILLED);
        assertThat(repo.recordedFillAmount(o.internalId())).isEqualByComparingTo("40");
        sim.executePending(OPEN);
        sim.executePending(OPEN);
        service.syncOpenOrders();
        assertThat(repo.find(o.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(repo.recordedFillAmount(o.internalId())).isEqualByComparingTo("100");
        assertThat(service.reconcile("agent").ok()).isTrue();
    }

    @Test
    void positionMismatchStopsEverything() {
        var o = buy("100");
        service.send(o);
        sim.executePending(OPEN);
        // sync is skipped, so the ledger knows nothing about units the broker already holds
        var report = service.reconcile("agent");
        assertThat(report.ok()).isFalse();
        assertThat(kill.isActive()).isTrue();
    }

    @Test
    void killSwitchBlocksSendingWithoutTouchingTheStore() {
        kill.activate("manual");
        assertThat(service.send(buy("10"))).isEqualTo(SendOutcome.BLOCKED);
        assertThat(repo.size()).isZero();
        assertThat(sim.orderCount()).isZero();
    }

    @Test
    void illegalTransitionsAreRejectedAndTerminalStatesAreFinal() {
        assertThat(OrderStateMachine.canTransition(OrderState.CREATED, OrderState.FILLED)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderState.FILLED, OrderState.CANCELLED)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderState.PARTIALLY_FILLED, OrderState.PARTIALLY_FILLED)).isTrue();
        assertThatThrownBy(() -> OrderStateMachine.require(OrderState.REJECTED, OrderState.SUBMITTED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokedApiAccessHaltsTradingAndAlerts() {
        var denied = new com.adrianrevilla.trading.core.domain.Broker() {
            public void submit(OrderRequest r) throws com.adrianrevilla.trading.core.domain.BrokerException {
                throw new com.adrianrevilla.trading.core.domain.BrokerAccessDeniedException("401");
            }
            public void cancel(UUID id) {}
            public java.util.Optional<com.adrianrevilla.trading.core.domain.BrokerOrder> lookup(UUID id) { return java.util.Optional.empty(); }
            public Map<Isin, BigDecimal> positions() { return Map.of(); }
            public BigDecimal cash() { return BigDecimal.ZERO; }
        };
        var svc = new ExecutionService(denied, repo, kill, (l, m) -> alerts.add(l + ":" + m), new Reconciler(BigDecimal.ZERO));
        var o = buy("10");
        assertThat(svc.send(o)).isEqualTo(SendOutcome.REJECTED);
        assertThat(kill.isActive()).isTrue();
        assertThat(alerts).anyMatch(a -> a.contains("denied access"));
        assertThat(svc.send(buy("10"))).isEqualTo(SendOutcome.BLOCKED);
    }

    @Test
    void dryRunNeverReachesTheBroker() {
        var dry = new DryRunBroker(sim);
        var svc = new ExecutionService(dry, repo, kill, (l, m) -> {}, new Reconciler(BigDecimal.ZERO));
        svc.send(buy("50"));
        assertThat(dry.recorded()).hasSize(1);
        assertThat(sim.orderCount()).isZero();
    }

    /** Randomised faults: each order reaches the broker at most once and every broker order is tracked. */
    @Test
    void randomFaultsNeverDuplicateNorLoseAnOrder() {
        Random rnd = new Random(12345);
        List<OrderRequest> sent = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            if (kill.isActive()) {
                kill.deactivate(); // a person reviewed and re-enabled; the invariant must keep holding
            }
            int r = rnd.nextInt(10);
            if (r == 0) {
                broker.failNextSubmit(Fault.REJECT);
            } else if (r == 1) {
                broker.failNextSubmit(Fault.TIMEOUT_BEFORE_DELIVERY);
            } else if (r == 2) {
                broker.failNextSubmit(Fault.TIMEOUT_AFTER_DELIVERY);
            }
            var o = buy("5");
            sent.add(o);
            service.send(o);
            if (rnd.nextInt(3) == 0) {
                sim.executePending(OPEN);
                service.syncOpenOrders();
            }
        }
        // every order that exists at the broker has a record in the store, exactly once
        for (OrderRequest o : sent) {
            boolean atBroker = broker.lookupQuietly(o.internalId());
            boolean tracked = repo.find(o.internalId()).isPresent();
            assertThat(tracked).as("order %s tracked", o.internalId()).isTrue();
            if (atBroker) {
                assertThat(repo.find(o.internalId()).orElseThrow().state()).isNotEqualTo(OrderState.CREATED);
            }
        }
        assertThat(sim.orderCount()).isLessThanOrEqualTo(sent.size());
        assertThat(repo.size()).isEqualTo(sent.size());
    }
}
