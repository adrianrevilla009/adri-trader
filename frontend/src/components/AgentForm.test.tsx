import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AgentForm } from "./AgentForm";
import type { Agent, Limits, Strategy } from "../types";

const strategies: Strategy[] = [
  { id: "buy-and-hold", name: "Buy and hold", description: "Compra y mantiene.", params: [] },
  {
    id: "trend",
    name: "Trend",
    description: "Sigue la tendencia.",
    params: [
      { name: "window", description: "Días", defaultValue: 200, min: 20, max: 400, integer: true },
      { name: "band", description: "Banda", defaultValue: 0.01, min: 0, max: 0.1, integer: false },
    ],
  },
];
const limits: Limits = { minimumCapital: 40, minOrderAmount: 10, maxWeightPerAsset: 0.25 };
const instruments = ["IE00B4L5Y983", "IE00B5BMR087"];

function setup(initial?: Agent) {
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  render(
    <AgentForm strategies={strategies} instruments={instruments} limits={limits} initial={initial} onSubmit={onSubmit} onCancel={onCancel} />,
  );
  return { onSubmit, onCancel, user: userEvent.setup() };
}

describe("AgentForm (create)", () => {
  it("builds the parameter fields from the chosen strategy with their defaults", async () => {
    const { user } = setup();

    await user.selectOptions(screen.getByLabelText("Estrategia"), "trend");

    expect(screen.getByLabelText(/^window/)).toHaveValue(200);
    expect(screen.getByLabelText(/^band/)).toHaveValue(0.01);
    expect(screen.getByText("Sigue la tendencia.")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Estrategia"), "buy-and-hold");
    expect(screen.queryByLabelText(/^window/)).not.toBeInTheDocument();
  });

  it("submits the complete payload in the order the instruments were chosen", async () => {
    const { user, onSubmit } = setup();
    await user.type(screen.getByLabelText("Identificador"), "trend-a");
    await user.selectOptions(screen.getByLabelText("Estrategia"), "trend");
    await user.click(screen.getByLabelText("IE00B5BMR087"));
    await user.click(screen.getByLabelText("IE00B4L5Y983"));
    await user.click(screen.getByRole("button", { name: "Añadir paso" }));
    await user.type(screen.getByLabelText("Días del paso 1"), "30");
    await user.type(screen.getByLabelText("Capital del paso 1"), "60");

    await user.click(screen.getByRole("button", { name: "Crear agente" }));

    expect(onSubmit).toHaveBeenCalledWith({
      kind: "create",
      body: {
        id: "trend-a",
        strategyId: "trend",
        params: { window: 200, band: 0.01 },
        universe: ["IE00B5BMR087", "IE00B4L5Y983"],
        capital: 40,
        steps: [{ afterDays: 30, capital: 60 }],
      },
    });
  });

  it("shows every problem and sends nothing when the input is invalid", async () => {
    const { user, onSubmit } = setup();
    await user.type(screen.getByLabelText("Identificador"), "Bad Id");
    await user.selectOptions(screen.getByLabelText("Estrategia"), "trend");
    await user.clear(screen.getByLabelText(/^window/));
    await user.type(screen.getByLabelText(/^window/), "5");
    await user.clear(screen.getByLabelText("Capital"));
    await user.type(screen.getByLabelText("Capital"), "39");

    await user.click(screen.getByRole("button", { name: "Crear agente" }));

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("identificador");
    expect(alert).toHaveTextContent("window: debe estar entre 20 y 400");
    expect(alert).toHaveTextContent("al menos un instrumento");
    expect(alert).toHaveTextContent("capital mínimo es 40");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("rejects steps that do not go up", async () => {
    const { user, onSubmit } = setup();
    await user.type(screen.getByLabelText("Identificador"), "a");
    await user.click(screen.getByLabelText("IE00B4L5Y983"));
    await user.click(screen.getByRole("button", { name: "Añadir paso" }));
    await user.type(screen.getByLabelText("Días del paso 1"), "30");
    await user.type(screen.getByLabelText("Capital del paso 1"), "40");

    await user.click(screen.getByRole("button", { name: "Crear agente" }));

    expect(screen.getByRole("alert")).toHaveTextContent("subir el capital");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("cancel calls back without submitting", async () => {
    const { user, onSubmit, onCancel } = setup();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onCancel).toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe("AgentForm (edit)", () => {
  const agent: Agent = {
    id: "trend-a",
    strategyId: "trend",
    params: { window: 100, band: 0.02 },
    universe: ["IE00B4L5Y983"],
    capital: 60,
    steps: [{ afterDays: 30, capital: 100 }],
    mode: "DEMO",
    state: "ACTIVE",
  };

  it("locks the id and the strategy, loads the stored values and submits an update", async () => {
    const { user, onSubmit } = setup(agent);

    expect(screen.getByLabelText("Identificador")).toBeDisabled();
    expect(screen.getByLabelText("Estrategia")).toBeDisabled();
    expect(screen.getByLabelText(/^window/)).toHaveValue(100);
    expect(screen.getByLabelText("IE00B4L5Y983")).toBeChecked();

    await user.clear(screen.getByLabelText("Capital"));
    await user.type(screen.getByLabelText("Capital"), "80");
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(onSubmit).toHaveBeenCalledWith({
      kind: "update",
      id: "trend-a",
      body: {
        params: { window: 100, band: 0.02 },
        universe: ["IE00B4L5Y983"],
        capital: 80,
        steps: [{ afterDays: 30, capital: 100 }],
      },
    });
  });
});
