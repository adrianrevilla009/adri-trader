import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { ApiError } from "./api";
import type { Api } from "./api";
import type { Agent } from "./types";

const trendAgent: Agent = {
  id: "trend-a",
  strategyId: "trend",
  params: { window: 100, band: 0.01 },
  universe: ["IE00B4L5Y983"],
  capital: 40,
  steps: [],
  mode: "DEMO",
  state: "ACTIVE",
};

function fakeApi(agents: Agent[] = [trendAgent]): { api: Api; mocks: Record<string, ReturnType<typeof vi.fn>> } {
  const mocks = {
    system: vi.fn().mockResolvedValue({ mode: "DEMO", available: ["DEMO"] }),
    setMode: vi.fn().mockRejectedValue(new ApiError(409, "PROD is not available yet")),
    strategies: vi.fn().mockResolvedValue([
      { id: "buy-and-hold", name: "Buy and hold", description: "d", params: [] },
      { id: "trend", name: "Trend", description: "t", params: [{ name: "window", description: "Días", defaultValue: 200, min: 20, max: 400, integer: true }] },
    ]),
    instruments: vi.fn().mockResolvedValue(["IE00B4L5Y983", "IE00B5BMR087"]),
    limits: vi.fn().mockResolvedValue({ minimumCapital: 40, minOrderAmount: 10, maxWeightPerAsset: 0.25 }),
    agents: vi.fn().mockResolvedValue(agents),
    createAgent: vi.fn().mockResolvedValue(trendAgent),
    updateAgent: vi.fn().mockResolvedValue(trendAgent),
    stopAgent: vi.fn().mockResolvedValue({ ...trendAgent, state: "STOPPED" }),
    resumeAgent: vi.fn().mockResolvedValue(trendAgent),
    audit: vi.fn().mockResolvedValue([{ agentId: "trend-a", actor: "api:ui", field: "created", oldValue: null, newValue: "trend" }]),
  };
  return { api: mocks as unknown as Api, mocks };
}

beforeEach(() => {
  sessionStorage.clear();
});

describe("App", () => {
  it("asks for the token first and keeps it only in the session", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    const user = userEvent.setup();
    render(<App />);

    expect(screen.getByRole("heading", { name: "Agentes de trading" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("Token"), "0123456789abcdef");
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    expect(sessionStorage.getItem("agents-api-token")).toBe("0123456789abcdef");
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("No se puede conectar"));
    vi.unstubAllGlobals();
  });

  it("goes back to the token screen when the backend answers 401", async () => {
    sessionStorage.setItem("agents-api-token", "wrong-token-123456");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => Promise.resolve(new Response('{"error":"unauthorized"}', { status: 401 }))),
    );

    render(<App />);

    await waitFor(() => expect(screen.getByLabelText("Token")).toBeInTheDocument());
    expect(screen.getByRole("alert")).toHaveTextContent("Token incorrecto");
    expect(sessionStorage.getItem("agents-api-token")).toBeNull();
    vi.unstubAllGlobals();
  });

  it("lists the agents with their state", async () => {
    const { api } = fakeApi([trendAgent, { ...trendAgent, id: "hold-b", strategyId: "buy-and-hold", state: "STOPPED" }]);
    render(<App api={api} />);

    expect(await screen.findByText("trend-a")).toBeInTheDocument();
    const stoppedRow = screen.getByText("hold-b").closest("tr") as HTMLElement;
    expect(within(stoppedRow).getByText("Parado")).toBeInTheDocument();
    expect(within(stoppedRow).getByRole("button", { name: "Reanudar hold-b" })).toBeInTheDocument();
  });

  it("shows a friendly message when there are no agents", async () => {
    render(<App api={fakeApi([]).api} />);
    expect(await screen.findByText(/Todavía no hay agentes/)).toBeInTheDocument();
  });

  it("stops an agent and reloads the list", async () => {
    const { api, mocks } = fakeApi();
    const user = userEvent.setup();
    render(<App api={api} />);

    await user.click(await screen.findByRole("button", { name: "Parar trend-a" }));

    expect(mocks.stopAgent).toHaveBeenCalledWith("trend-a");
    await waitFor(() => expect(mocks.agents).toHaveBeenCalledTimes(2));
  });

  it("creates an agent from the form, goes back to the list and reloads", async () => {
    const { api, mocks } = fakeApi([]);
    const user = userEvent.setup();
    render(<App api={api} />);

    await user.click(await screen.findByRole("button", { name: "Crear agente" }));
    await user.type(screen.getByLabelText("Identificador"), "hold-a");
    await user.selectOptions(screen.getByLabelText("Estrategia"), "buy-and-hold");
    await user.click(screen.getByLabelText("IE00B4L5Y983"));
    await user.click(screen.getByRole("button", { name: "Crear agente", description: "" }));

    await waitFor(() =>
      expect(mocks.createAgent).toHaveBeenCalledWith({
        id: "hold-a",
        strategyId: "buy-and-hold",
        params: {},
        universe: ["IE00B4L5Y983"],
        capital: 40,
        steps: [],
      }),
    );
    expect(await screen.findByText(/Todavía no hay agentes/)).toBeInTheDocument();
    expect(mocks.agents).toHaveBeenCalledTimes(2);
  });

  it("shows the error of the server and stays on the form when creating fails", async () => {
    const { api, mocks } = fakeApi([]);
    mocks.createAgent!.mockRejectedValue(new ApiError(409, "agent already exists: hold-a"));
    const user = userEvent.setup();
    render(<App api={api} />);

    await user.click(await screen.findByRole("button", { name: "Crear agente" }));
    await user.type(screen.getByLabelText("Identificador"), "hold-a");
    await user.selectOptions(screen.getByLabelText("Estrategia"), "buy-and-hold");
    await user.click(screen.getByLabelText("IE00B4L5Y983"));
    await user.click(screen.getByRole("button", { name: "Crear agente", description: "" }));

    expect(await screen.findByText("agent already exists: hold-a")).toBeInTheDocument();
    expect(screen.getByRole("form", { name: "Crear agente" })).toBeInTheDocument();
  });

  it("opens the history of an agent", async () => {
    const { api, mocks } = fakeApi();
    const user = userEvent.setup();
    render(<App api={api} />);

    await user.click(await screen.findByRole("button", { name: "Historial de trend-a" }));

    expect(mocks.audit).toHaveBeenCalledWith("trend-a");
    expect(await screen.findByRole("heading", { name: "Historial de trend-a" })).toBeInTheDocument();
    expect(screen.getByText("created")).toBeInTheDocument();
  });

  it("does not offer PROD and shows the refusal if the backend still says no", async () => {
    const { api, mocks } = fakeApi();
    render(<App api={api} />);

    const option = (await screen.findByRole("option", { name: "PROD (no disponible)" })) as HTMLOptionElement;
    expect(option.disabled).toBe(true);
    expect(mocks.setMode).not.toHaveBeenCalled();
  });
});
