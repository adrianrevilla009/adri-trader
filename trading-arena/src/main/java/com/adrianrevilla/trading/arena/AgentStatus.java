package com.adrianrevilla.trading.arena;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Life cycle of an agent: CANDIDATE passes phase 2, ARENA competes, FINALIST meets the criteria, REAL_SMALL trades real. */
public enum AgentStatus {
    CANDIDATE, ARENA, FINALIST, REAL_SMALL, RETIRED;

    private static final Map<AgentStatus, Set<AgentStatus>> ALLOWED = new EnumMap<>(AgentStatus.class);

    static {
        ALLOWED.put(CANDIDATE, EnumSet.of(ARENA, RETIRED));
        ALLOWED.put(ARENA, EnumSet.of(FINALIST, RETIRED));
        ALLOWED.put(FINALIST, EnumSet.of(REAL_SMALL, ARENA, RETIRED));
        ALLOWED.put(REAL_SMALL, EnumSet.of(RETIRED));
        ALLOWED.put(RETIRED, EnumSet.noneOf(AgentStatus.class));
    }

    public boolean canMoveTo(AgentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** True while the agent still makes decisions in the arena. */
    public boolean decides() {
        return this == ARENA || this == FINALIST || this == REAL_SMALL;
    }
}
