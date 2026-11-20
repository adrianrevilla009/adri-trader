package com.adrianrevilla.trading.execution.domain;

import com.adrianrevilla.trading.core.domain.OrderState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Legal order transitions. Terminal states (FILLED, CANCELLED, REJECTED) never change again. */
public final class OrderStateMachine {
    private static final Map<OrderState, Set<OrderState>> ALLOWED = new EnumMap<>(OrderState.class);

    static {
        ALLOWED.put(OrderState.CREATED, EnumSet.of(OrderState.SUBMITTED, OrderState.REJECTED, OrderState.CANCELLED));
        ALLOWED.put(OrderState.SUBMITTED, EnumSet.of(OrderState.PARTIALLY_FILLED, OrderState.FILLED,
                OrderState.CANCELLED, OrderState.REJECTED));
        ALLOWED.put(OrderState.PARTIALLY_FILLED, EnumSet.of(OrderState.PARTIALLY_FILLED, OrderState.FILLED,
                OrderState.CANCELLED));
        ALLOWED.put(OrderState.FILLED, EnumSet.noneOf(OrderState.class));
        ALLOWED.put(OrderState.CANCELLED, EnumSet.noneOf(OrderState.class));
        ALLOWED.put(OrderState.REJECTED, EnumSet.noneOf(OrderState.class));
    }

    private OrderStateMachine() {}

    public static boolean canTransition(OrderState from, OrderState to) {
        return from == to || ALLOWED.get(from).contains(to);
    }

    public static void require(OrderState from, OrderState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal order transition " + from + " -> " + to);
        }
    }

    public static boolean isTerminal(OrderState s) {
        return ALLOWED.get(s).isEmpty();
    }

    public static boolean isOpen(OrderState s) {
        return s == OrderState.SUBMITTED || s == OrderState.PARTIALLY_FILLED;
    }
}
