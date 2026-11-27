import type {
  Agent,
  AuditEntry,
  CreateAgentBody,
  Limits,
  ModeInfo,
  Strategy,
  UpdateAgentBody,
} from "./types";

/** An error answered by the backend: the status and the message of {"error": "..."}. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Client of the agents API. The token is sent on every call and never stored here. */
export function createApi(baseUrl: string, token: string, actor?: string) {
  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (actor !== undefined && actor !== "") {
      headers["X-Actor"] = actor;
    }
    let response: Response;
    try {
      response = await fetch(`${baseUrl}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new ApiError(0, "No se puede conectar con el servidor (¿está arrancado y con TRADING_API_TOKEN?)");
    }
    const text = await response.text();
    let data: unknown = null;
    if (text !== "") {
      try {
        data = JSON.parse(text);
      } catch {
        data = null;
      }
    }
    if (!response.ok) {
      const message =
        typeof data === "object" && data !== null && "error" in data && typeof data.error === "string"
          ? data.error
          : response.statusText || `Error ${response.status}`;
      throw new ApiError(response.status, message);
    }
    return data as T;
  }

  const enc = encodeURIComponent;
  return {
    system: () => request<ModeInfo>("GET", "/api/system"),
    setMode: (mode: string) => request<ModeInfo>("PUT", "/api/system/mode", { mode }),
    strategies: () => request<Strategy[]>("GET", "/api/strategies"),
    instruments: () => request<string[]>("GET", "/api/instruments"),
    limits: () => request<Limits>("GET", "/api/limits"),
    agents: () => request<Agent[]>("GET", "/api/agents"),
    createAgent: (body: CreateAgentBody) => request<Agent>("POST", "/api/agents", body),
    updateAgent: (id: string, body: UpdateAgentBody) => request<Agent>("PUT", `/api/agents/${enc(id)}`, body),
    stopAgent: (id: string) => request<Agent>("POST", `/api/agents/${enc(id)}/stop`),
    resumeAgent: (id: string) => request<Agent>("POST", `/api/agents/${enc(id)}/resume`),
    audit: (id: string) => request<AuditEntry[]>("GET", `/api/agents/${enc(id)}/audit`),
  };
}

export type Api = ReturnType<typeof createApi>;
