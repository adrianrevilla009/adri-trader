import { useState } from "react";

interface Props {
  onSubmit: (token: string) => void;
  error?: string | null;
}

/** Asks for the API token. It is kept only for this browser tab (sessionStorage), never sent anywhere else. */
export function TokenGate({ onSubmit, error }: Props) {
  const [token, setToken] = useState("");
  return (
    <form
      className="card gate"
      onSubmit={(e) => {
        e.preventDefault();
        if (token.trim() !== "") {
          onSubmit(token.trim());
        }
      }}
    >
      <h1>Agentes de trading</h1>
      <p>Escribe el token de la API (variable TRADING_API_TOKEN del servidor).</p>
      <label>
        Token
        <input type="password" value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" />
      </label>
      {error && (
        <p className="errors" role="alert">
          {error}
        </p>
      )}
      <button type="submit" className="primary">
        Entrar
      </button>
    </form>
  );
}
