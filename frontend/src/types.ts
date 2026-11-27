/** Shapes of the JSON the backend sends (AgentsApiServer, ADR-0030). */
export interface Param {
  name: string;
  description: string;
  defaultValue: number;
  min: number;
  max: number;
  integer: boolean;
}

export interface Strategy {
  id: string;
  name: string;
  description: string;
  params: Param[];
}

export interface Step {
  afterDays: number;
  capital: number;
}

export type AgentState = "ACTIVE" | "STOPPED";

export interface Agent {
  id: string;
  strategyId: string;
  params: Record<string, number>;
  universe: string[];
  capital: number;
  steps: Step[];
  mode: string;
  state: AgentState;
}

export interface AuditEntry {
  agentId: string;
  actor: string;
  field: string;
  oldValue: string | null;
  newValue: string | null;
}

export interface ModeInfo {
  mode: string;
  available: string[];
}

export interface Limits {
  minimumCapital: number;
  minOrderAmount: number;
  maxWeightPerAsset: number;
}

/** Body of POST /api/agents. */
export interface CreateAgentBody {
  id: string;
  strategyId: string;
  params: Record<string, number>;
  universe: string[];
  capital: number;
  steps: Step[];
}

/** Body of PUT /api/agents/{id}: only what changes. */
export interface UpdateAgentBody {
  params?: Record<string, number>;
  universe?: string[];
  capital?: number;
  steps?: Step[];
}
