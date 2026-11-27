import { useMemo, useState } from "react";
import type { Agent, CreateAgentBody, Limits, Strategy, UpdateAgentBody } from "../types";

export type FormResult =
  | { kind: "create"; body: CreateAgentBody }
  | { kind: "update"; id: string; body: UpdateAgentBody };

interface Props {
  strategies: Strategy[];
  instruments: string[];
  limits: Limits;
  /** When given the form edits this agent: its id and strategy cannot change. */
  initial?: Agent;
  busy?: boolean;
  onSubmit: (result: FormResult) => void;
  onCancel: () => void;
}

interface StepRow {
  afterDays: string;
  capital: string;
}

const ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;

function defaultsOf(strategy: Strategy | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const p of strategy?.params ?? []) {
    out[p.name] = String(p.defaultValue);
  }
  return out;
}

/**
 * Create or edit an agent. The parameter fields are built from the strategy catalog, so a new strategy in the backend
 * shows up here without any change. The checks here only save a round trip: the server validates everything again.
 */
export function AgentForm({ strategies, instruments, limits, initial, busy = false, onSubmit, onCancel }: Props) {
  const editing = initial !== undefined;
  const [id, setId] = useState(initial?.id ?? "");
  const [strategyId, setStrategyId] = useState(initial?.strategyId ?? strategies[0]?.id ?? "");
  const strategy = useMemo(() => strategies.find((s) => s.id === strategyId), [strategies, strategyId]);
  const [params, setParams] = useState<Record<string, string>>(
    initial ? Object.fromEntries(Object.entries(initial.params).map(([k, v]) => [k, String(v)])) : defaultsOf(strategy),
  );
  const [universe, setUniverse] = useState<string[]>(initial?.universe ?? []);
  const [capital, setCapital] = useState(String(initial?.capital ?? limits.minimumCapital));
  const [steps, setSteps] = useState<StepRow[]>(
    initial?.steps.map((s) => ({ afterDays: String(s.afterDays), capital: String(s.capital) })) ?? [],
  );
  const [errors, setErrors] = useState<string[]>([]);

  function chooseStrategy(next: string) {
    setStrategyId(next);
    setParams(defaultsOf(strategies.find((s) => s.id === next)));
  }

  function toggleInstrument(isin: string) {
    setUniverse((u) => (u.includes(isin) ? u.filter((x) => x !== isin) : [...u, isin]));
  }

  function validate(): { params: Record<string, number>; capital: number; steps: { afterDays: number; capital: number }[] } | null {
    const found: string[] = [];
    if (!editing && !ID_PATTERN.test(id)) {
      found.push("El identificador usa minúsculas, números, '-' o '_' (1 a 64 caracteres).");
    }
    const numericParams: Record<string, number> = {};
    for (const p of strategy?.params ?? []) {
      const raw = params[p.name] ?? "";
      const value = Number(raw);
      if (raw.trim() === "" || Number.isNaN(value)) {
        found.push(`${p.name}: escribe un número.`);
      } else if (p.integer && !Number.isInteger(value)) {
        found.push(`${p.name}: debe ser un número entero.`);
      } else if (value < p.min || value > p.max) {
        found.push(`${p.name}: debe estar entre ${p.min} y ${p.max}.`);
      } else {
        numericParams[p.name] = value;
      }
    }
    if (universe.length === 0) {
      found.push("Elige al menos un instrumento.");
    }
    const capitalValue = Number(capital);
    if (capital.trim() === "" || Number.isNaN(capitalValue) || capitalValue < limits.minimumCapital) {
      found.push(`El capital mínimo es ${limits.minimumCapital} (la posición más grande debe llegar al mínimo por orden).`);
    }
    const stepValues = steps.map((s) => ({ afterDays: Number(s.afterDays), capital: Number(s.capital) }));
    let lastDays = 0;
    let lastCapital = capitalValue;
    for (const s of stepValues) {
      if (!Number.isInteger(s.afterDays) || s.afterDays <= lastDays) {
        found.push("Los pasos van en orden creciente de días (enteros).");
        break;
      }
      if (Number.isNaN(s.capital) || s.capital <= lastCapital) {
        found.push("Cada paso debe subir el capital por encima del anterior.");
        break;
      }
      lastDays = s.afterDays;
      lastCapital = s.capital;
    }
    setErrors(found);
    return found.length === 0 ? { params: numericParams, capital: capitalValue, steps: stepValues } : null;
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const valid = validate();
    if (valid === null) {
      return;
    }
    if (initial) {
      onSubmit({
        kind: "update",
        id: initial.id,
        body: { params: valid.params, universe, capital: valid.capital, steps: valid.steps },
      });
    } else {
      onSubmit({
        kind: "create",
        body: { id, strategyId, params: valid.params, universe, capital: valid.capital, steps: valid.steps },
      });
    }
  }

  return (
    <form className="card form" noValidate onSubmit={submit} aria-label={editing ? "Editar agente" : "Crear agente"}>
      <h2>{editing ? `Editar ${initial.id}` : "Crear agente"}</h2>

      <label>
        Identificador
        <input value={id} onChange={(e) => setId(e.target.value)} disabled={editing} placeholder="trend-a" />
      </label>

      <label>
        Estrategia
        <select value={strategyId} onChange={(e) => chooseStrategy(e.target.value)} disabled={editing}>
          {strategies.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>
      {strategy && <p className="hint">{strategy.description}</p>}

      {strategy && strategy.params.length > 0 && (
        <fieldset>
          <legend>Parámetros</legend>
          {strategy.params.map((p) => (
            <label key={p.name}>
              {p.name}
              <input
                type="number"
                value={params[p.name] ?? ""}
                min={p.min}
                max={p.max}
                step={p.integer ? 1 : "any"}
                onChange={(e) => setParams((cur) => ({ ...cur, [p.name]: e.target.value }))}
              />
              <span className="hint">
                {p.description} ({p.min} a {p.max})
              </span>
            </label>
          ))}
        </fieldset>
      )}

      <fieldset>
        <legend>Instrumentos (el primero es el principal)</legend>
        {instruments.map((isin) => (
          <label key={isin} className="check">
            <input aria-label={isin} type="checkbox" checked={universe.includes(isin)} onChange={() => toggleInstrument(isin)} />
            {isin}
            {universe.includes(isin) && <span className="badge">{universe.indexOf(isin) + 1}</span>}
          </label>
        ))}
      </fieldset>

      <label>
        Capital
        <input aria-label="Capital" type="number" value={capital} min={limits.minimumCapital} step="any" onChange={(e) => setCapital(e.target.value)} />
        <span className="hint">
          Mínimo {limits.minimumCapital}: con el límite del {limits.maxWeightPerAsset * 100}% por activo, la posición más grande
          debe llegar al mínimo por orden ({limits.minOrderAmount}).
        </span>
      </label>

      <fieldset>
        <legend>Pasos de escalado (días desde el inicio → capital)</legend>
        {steps.map((s, i) => (
          <div className="row" key={i}>
            <input
              aria-label={`Días del paso ${i + 1}`}
              type="number"
              value={s.afterDays}
              min={1}
              onChange={(e) => setSteps((cur) => cur.map((x, j) => (j === i ? { ...x, afterDays: e.target.value } : x)))}
            />
            <input
              aria-label={`Capital del paso ${i + 1}`}
              type="number"
              value={s.capital}
              step="any"
              onChange={(e) => setSteps((cur) => cur.map((x, j) => (j === i ? { ...x, capital: e.target.value } : x)))}
            />
            <button type="button" onClick={() => setSteps((cur) => cur.filter((_, j) => j !== i))}>
              Quitar
            </button>
          </div>
        ))}
        <button type="button" onClick={() => setSteps((cur) => [...cur, { afterDays: "", capital: "" }])}>
          Añadir paso
        </button>
        <p className="hint">Subir de paso lo confirma una persona: el capital no cambia solo.</p>
      </fieldset>

      {errors.length > 0 && (
        <ul className="errors" role="alert">
          {errors.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      )}

      <div className="row">
        <button type="submit" className="primary" disabled={busy}>
          {editing ? "Guardar cambios" : "Crear agente"}
        </button>
        <button type="button" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </form>
  );
}
