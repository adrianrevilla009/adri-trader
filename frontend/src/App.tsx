import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, createApi } from "./api";
import type { Api } from "./api";
import { AgentForm } from "./components/AgentForm";
import type { FormResult } from "./components/AgentForm";
import { AgentTable } from "./components/AgentTable";
import { AuditPanel } from "./components/AuditPanel";
import { ProdSwitchDialog } from "./components/ProdSwitchDialog";
import { TokenGate } from "./components/TokenGate";
import type { Agent, AuditEntry, Limits, ModeInfo, Strategy } from "./types";

const TOKEN_KEY = "agents-api-token";
const API_URL: string = import.meta.env.VITE_API_URL ?? "";

interface Data {
  mode: ModeInfo;
  strategies: Strategy[];
  instruments: string[];
  limits: Limits;
  agents: Agent[];
}

type View = { kind: "prod" } | { kind: "list" } | { kind: "create" } | { kind: "edit"; agent: Agent } | { kind: "audit"; id: string; entries: AuditEntry[] };

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "Error desconocido";
}

/** The whole screen: token, mode, agent list and the forms. All the rules are enforced by the backend. */
export function App({ api: injected }: { api?: Api } = {}) {
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(TOKEN_KEY));
  const api = useMemo(() => injected ?? (token === null ? null : createApi(API_URL, token, "ui")), [injected, token]);
  const [data, setData] = useState<Data | null>(null);
  const [view, setView] = useState<View>({ kind: "list" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const logout = useCallback(() => {
    sessionStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setData(null);
  }, []);

  const load = useCallback(
    async (client: Api) => {
      try {
        const [mode, strategies, instruments, limits, agents] = await Promise.all([
          client.system(),
          client.strategies(),
          client.instruments(),
          client.limits(),
          client.agents(),
        ]);
        setData({ mode, strategies, instruments, limits, agents });
        setError(null);
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          logout();
          setError("Token incorrecto.");
          return;
        }
        setError(messageOf(e));
      }
    },
    [logout],
  );

  useEffect(() => {
    if (api !== null) {
      void load(api);
    }
  }, [api, load]);

  /** Runs one command, then reloads the list; an error is shown and nothing else changes. */
  async function run(action: (client: Api) => Promise<unknown>, after?: () => void) {
    if (api === null) {
      return;
    }
    setBusy(true);
    try {
      await action(api);
      setError(null);
      after?.();
      await load(api);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  /** Going back to DEMO is the safe direction: one confirmation, no MFA. */
  function backToDemo() {
    if (window.confirm("¿Volver a DEMO?")) {
      void run((client) => client.setMode("DEMO"));
    }
  }

  async function submit(result: FormResult) {
    await run(
      (client) => (result.kind === "create" ? client.createAgent(result.body) : client.updateAgent(result.id, result.body)),
      () => setView({ kind: "list" }),
    );
  }

  async function showAudit(id: string) {
    if (api === null) {
      return;
    }
    try {
      setView({ kind: "audit", id, entries: await api.audit(id) });
    } catch (e) {
      setError(messageOf(e));
    }
  }

  if (api === null) {
    return (
      <main>
        <TokenGate
          error={error}
          onSubmit={(t) => {
            sessionStorage.setItem(TOKEN_KEY, t);
            setError(null);
            setToken(t);
          }}
        />
      </main>
    );
  }

  return (
    <main>
      <header className="bar">
        <h1>Agentes de trading</h1>
        <div className="row">
          <span className="mode" aria-label="Modo">
            Modo: <strong>{data?.mode.mode ?? "DEMO"}</strong>
          </span>
          {data?.mode.mode === "PROD" ? (
            <button disabled={busy} onClick={() => backToDemo()}>
              Volver a DEMO
            </button>
          ) : (
            data?.mode.prodEnabled === true && (
              <button disabled={busy || data.mode.lockedUntil !== null} onClick={() => setView({ kind: "prod" })}>
                Cambiar a PROD…
              </button>
            )
          )}
          {injected === undefined && <button onClick={logout}>Salir</button>}
        </div>
      </header>

      {data?.mode.mode === "PROD" && (
        <p className="prod-banner" role="status">
          MODO PROD ACTIVO. Hoy no existe un bróker real: el ciclo diario no opera hasta volver a DEMO.
        </p>
      )}
      {data?.mode.lockedUntil != null && (
        <p className="banner" role="alert">
          El cambio a PROD está bloqueado hasta {data.mode.lockedUntil} por intentos fallidos.
        </p>
      )}

      {error && (
        <p className="banner" role="alert">
          {error}
        </p>
      )}

      {data === null ? (
        <p className="empty">Cargando…</p>
      ) : (
        <>
          {view.kind === "prod" && (
            <ProdSwitchDialog
              api={api}
              phrase={data.mode.confirmPhrase}
              onCancel={() => {
                setView({ kind: "list" });
                void load(api);
              }}
              onDone={() => {
                setView({ kind: "list" });
                void load(api);
              }}
            />
          )}
          {view.kind === "list" && (
            <section className="card">
              <div className="row between">
                <h2>Agentes</h2>
                <button className="primary" onClick={() => setView({ kind: "create" })}>
                  Crear agente
                </button>
              </div>
              <AgentTable
                agents={data.agents}
                busy={busy}
                onStop={(id) => void run((client) => client.stopAgent(id))}
                onResume={(id) => void run((client) => client.resumeAgent(id))}
                onEdit={(agent) => setView({ kind: "edit", agent })}
                onAudit={(id) => void showAudit(id)}
              />
            </section>
          )}
          {view.kind === "create" && (
            <AgentForm
              strategies={data.strategies}
              instruments={data.instruments}
              limits={data.limits}
              busy={busy}
              onSubmit={(r) => void submit(r)}
              onCancel={() => setView({ kind: "list" })}
            />
          )}
          {view.kind === "edit" && (
            <AgentForm
              strategies={data.strategies}
              instruments={data.instruments}
              limits={data.limits}
              initial={view.agent}
              busy={busy}
              onSubmit={(r) => void submit(r)}
              onCancel={() => setView({ kind: "list" })}
            />
          )}
          {view.kind === "audit" && (
            <AuditPanel agentId={view.id} entries={view.entries} onClose={() => setView({ kind: "list" })} />
          )}
        </>
      )}
    </main>
  );
}
