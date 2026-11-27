import type { AuditEntry } from "../types";

interface Props {
  agentId: string;
  entries: AuditEntry[];
  onClose: () => void;
}

/** Who changed what on an agent, oldest first (the backend keeps it append-only). */
export function AuditPanel({ agentId, entries, onClose }: Props) {
  return (
    <section className="card" aria-label={`Historial de ${agentId}`}>
      <div className="row between">
        <h2>Historial de {agentId}</h2>
        <button onClick={onClose}>Cerrar</button>
      </div>
      {entries.length === 0 ? (
        <p className="empty">Sin cambios registrados.</p>
      ) : (
        <ol className="audit">
          {entries.map((e, i) => (
            <li key={i}>
              <strong>{e.field}</strong> por {e.actor}
              {e.oldValue !== null ? (
                <>
                  : <code>{e.oldValue}</code> → <code>{e.newValue}</code>
                </>
              ) : (
                <>
                  : <code>{e.newValue}</code>
                </>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
