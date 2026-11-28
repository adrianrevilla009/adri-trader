import { useEffect, useState } from "react";
import { ApiError } from "../api";
import type { Api } from "../api";
import type { ModeInfo, ProdChallenge } from "../types";

interface Props {
  api: Api;
  phrase: string;
  onDone: (mode: ModeInfo) => void;
  onCancel: () => void;
}

type Step = "warning" | "mfa" | "final";

const CHECKS = [
  "Entiendo que PROD es dinero real y que un error no se puede deshacer.",
  "Entiendo que hoy no existe un bróker real: en PROD el ciclo diario NO opera.",
  "Tengo a mano mi app de autenticación (MFA).",
];

function secondsLeft(expiresAt: string): number {
  return Math.max(0, Math.ceil((Date.parse(expiresAt) - Date.now()) / 1000));
}

/**
 * Three screens to switch to PROD: warning with checks, MFA code plus the typed phrase, and a last confirmation.
 * It only collects the factors; the server decides (the challenge, the code and the phrase are checked there).
 */
export function ProdSwitchDialog({ api, phrase, onDone, onCancel }: Props) {
  const [step, setStep] = useState<Step>("warning");
  const [checks, setChecks] = useState<boolean[]>(CHECKS.map(() => false));
  const [challenge, setChallenge] = useState<ProdChallenge | null>(null);
  const [code, setCode] = useState("");
  const [typed, setTyped] = useState("");
  const [left, setLeft] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (challenge === null) {
      return;
    }
    setLeft(secondsLeft(challenge.expiresAt));
    const timer = setInterval(() => setLeft(secondsLeft(challenge.expiresAt)), 1000);
    return () => clearInterval(timer);
  }, [challenge]);

  function restart(message: string) {
    setChallenge(null);
    setCode("");
    setTyped("");
    setChecks(CHECKS.map(() => false));
    setStep("warning");
    setError(message);
  }

  async function ask() {
    setBusy(true);
    try {
      setChallenge(await api.requestProd());
      setError(null);
      setStep("mfa");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Error desconocido");
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (challenge === null) {
      return;
    }
    setBusy(true);
    try {
      onDone(await api.confirmProd(challenge.challengeId, code, typed));
    } catch (e) {
      // any rejection consumes the challenge on the server: start again from the first screen
      restart(e instanceof ApiError && e.status === 423 ? e.message : `${e instanceof Error ? e.message : "Error"}.`);
    } finally {
      setBusy(false);
    }
  }

  const codeOk = /^[0-9]{6}$/.test(code);
  const phraseOk = typed === phrase;
  const expired = challenge !== null && left === 0;

  return (
    <section className="card prod" role="dialog" aria-labelledby="prod-title">
      <h2 id="prod-title">Cambiar a PROD</h2>
      {error && (
        <p className="errors" role="alert">
          {error}
        </p>
      )}

      {step === "warning" && (
        <>
          <p className="danger-text">Operación delicada. Se registra en el historial y avisa por Telegram.</p>
          {CHECKS.map((text, i) => (
            <label key={text} className="check">
              <input
                type="checkbox"
                checked={checks[i]}
                onChange={(e) => setChecks(checks.map((c, j) => (j === i ? e.target.checked : c)))}
              />
              {text}
            </label>
          ))}
          <div className="row">
            <button onClick={onCancel}>Cancelar</button>
            <button className="primary" disabled={busy || !checks.every(Boolean)} onClick={() => void ask()}>
              Pedir confirmación
            </button>
          </div>
        </>
      )}

      {step === "mfa" && (
        <>
          <p>
            Código de 6 cifras de tu app de autenticación y la frase exacta. Caduca en <strong>{left} s</strong>.
          </p>
          <label>
            Código MFA
            <input
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.trim())}
            />
          </label>
          <label>
            Escribe «{phrase}» para continuar
            <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          </label>
          <div className="row">
            <button onClick={onCancel}>Cancelar</button>
            <button className="primary" disabled={!codeOk || !phraseOk || expired} onClick={() => setStep("final")}>
              Continuar
            </button>
          </div>
        </>
      )}

      {step === "final" && (
        <>
          <p className="danger-text">Última confirmación: el sistema pasará de DEMO a PROD.</p>
          <p>Caduca en {left} s. Si no confirmas a tiempo, se cancela.</p>
          <div className="row">
            <button onClick={() => setStep("mfa")}>Atrás</button>
            <button onClick={onCancel}>Cancelar</button>
            <button className="danger" disabled={busy || expired} onClick={() => void confirm()}>
              Sí, cambiar a PROD ahora
            </button>
          </div>
        </>
      )}
    </section>
  );
}
