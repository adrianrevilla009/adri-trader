import type { Agent } from "../types";

interface Props {
  agents: Agent[];
  busy: boolean;
  onStop: (id: string) => void;
  onResume: (id: string) => void;
  onEdit: (agent: Agent) => void;
  onAudit: (id: string) => void;
}

/** One row per agent with its actions. Stopping keeps the positions: it only stops new orders. */
export function AgentTable({ agents, busy, onStop, onResume, onEdit, onAudit }: Props) {
  if (agents.length === 0) {
    return <p className="empty">Todavía no hay agentes. Crea el primero con el botón de arriba.</p>;
  }
  return (
    <table>
      <thead>
        <tr>
          <th>Agente</th>
          <th>Estrategia</th>
          <th className="num">Capital</th>
          <th>Instrumentos</th>
          <th>Estado</th>
          <th>Acciones</th>
        </tr>
      </thead>
      <tbody>
        {agents.map((a) => (
          <tr key={a.id}>
            <td>
              <strong>{a.id}</strong>
            </td>
            <td>{a.strategyId}</td>
            <td className="num">{a.capital}</td>
            <td className="isins">{a.universe.join(", ")}</td>
            <td>
              <span className={a.state === "ACTIVE" ? "pill ok" : "pill off"}>{a.state === "ACTIVE" ? "Activo" : "Parado"}</span>
            </td>
            <td className="actions">
              {a.state === "ACTIVE" ? (
                <button disabled={busy} onClick={() => onStop(a.id)} aria-label={`Parar ${a.id}`}>
                  Parar
                </button>
              ) : (
                <button disabled={busy} onClick={() => onResume(a.id)} aria-label={`Reanudar ${a.id}`}>
                  Reanudar
                </button>
              )}
              <button disabled={busy} onClick={() => onEdit(a)} aria-label={`Editar ${a.id}`}>
                Editar
              </button>
              <button disabled={busy} onClick={() => onAudit(a.id)} aria-label={`Historial de ${a.id}`}>
                Historial
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
