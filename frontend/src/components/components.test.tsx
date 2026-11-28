import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Agent } from "../types";
import { AgentTable } from "./AgentTable";
import { AuditPanel } from "./AuditPanel";
import { TokenGate } from "./TokenGate";

const agent = (over: Partial<Agent> = {}): Agent => ({
  id: "trend-a",
  strategyId: "trend",
  params: {},
  universe: ["IE00B4L5Y983", "IE00B5BMR087"],
  capital: 40,
  steps: [],
  mode: "DEMO",
  state: "ACTIVE",
  ...over,
});

function table(agents: Agent[], busy = false) {
  const handlers = { onStop: vi.fn(), onResume: vi.fn(), onEdit: vi.fn(), onAudit: vi.fn() };
  render(<AgentTable agents={agents} busy={busy} {...handlers} />);
  return { ...handlers, user: userEvent.setup() };
}

describe("AgentTable", () => {
  it("explains the empty state instead of showing an empty table", () => {
    table([]);
    expect(screen.getByText(/Todavía no hay agentes/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows one row per agent with its values", () => {
    table([agent(), agent({ id: "swing-b", strategyId: "swing", capital: 120, state: "STOPPED" })]);
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText("trend-a")).toBeInTheDocument();
    expect(within(rows[0]!).getByText("IE00B4L5Y983, IE00B5BMR087")).toBeInTheDocument();
    expect(within(rows[0]!).getByText("Activo")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("120")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("Parado")).toBeInTheDocument();
  });

  it("offers Stop on an active agent and Resume on a stopped one, never both", () => {
    table([agent(), agent({ id: "swing-b", state: "STOPPED" })]);
    expect(screen.getByRole("button", { name: "Parar trend-a" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reanudar trend-a" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reanudar swing-b" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Parar swing-b" })).not.toBeInTheDocument();
  });

  it("calls the right handler with the right agent", async () => {
    const t = table([agent(), agent({ id: "swing-b", state: "STOPPED" })]);
    await t.user.click(screen.getByRole("button", { name: "Parar trend-a" }));
    await t.user.click(screen.getByRole("button", { name: "Reanudar swing-b" }));
    await t.user.click(screen.getByRole("button", { name: "Editar swing-b" }));
    await t.user.click(screen.getByRole("button", { name: "Historial de trend-a" }));
    expect(t.onStop).toHaveBeenCalledWith("trend-a");
    expect(t.onResume).toHaveBeenCalledWith("swing-b");
    expect(t.onEdit).toHaveBeenCalledWith(expect.objectContaining({ id: "swing-b" }));
    expect(t.onAudit).toHaveBeenCalledWith("trend-a");
  });

  it("disables every action while a command is running, so a double click cannot send it twice", async () => {
    const t = table([agent()], true);
    for (const button of screen.getAllByRole("button")) {
      expect(button).toBeDisabled();
    }
    await t.user.click(screen.getByRole("button", { name: "Parar trend-a" }));
    expect(t.onStop).not.toHaveBeenCalled();
  });

  it("renders hostile text as text, never as markup", () => {
    const evil = '<img src=x onerror="document.title=1">';
    table([agent({ id: evil, universe: [evil] })]);
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getAllByText(evil).length).toBeGreaterThan(0);
  });
});

describe("AuditPanel", () => {
  it("says so when there are no changes", () => {
    render(<AuditPanel agentId="trend-a" entries={[]} onClose={() => {}} />);
    expect(screen.getByText("Sin cambios registrados.")).toBeInTheDocument();
  });

  it("shows creations as a value and changes as old to new, in order", () => {
    render(
      <AuditPanel
        agentId="trend-a"
        entries={[
          { agentId: "trend-a", actor: "api:ui", field: "created", oldValue: null, newValue: "trend" },
          { agentId: "trend-a", actor: "api:ui", field: "capital", oldValue: "40", newValue: "50" },
        ]}
        onClose={() => {}}
      />,
    );
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("created por api:ui: trend");
    expect(items[1]).toHaveTextContent("capital por api:ui: 40 → 50");
  });

  it("closes", async () => {
    const onClose = vi.fn();
    render(<AuditPanel agentId="trend-a" entries={[]} onClose={onClose} />);
    await userEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("renders hostile actors and values as text", () => {
    const evil = "<script>window.hacked=1</script>";
    render(
      <AuditPanel
        agentId="a"
        entries={[{ agentId: "a", actor: evil, field: "created", oldValue: null, newValue: evil }]}
        onClose={() => {}}
      />,
    );
    expect(document.querySelector("script")).toBeNull();
    expect((window as unknown as { hacked?: number }).hacked).toBeUndefined();
  });
});

describe("TokenGate", () => {
  it("hides the token while typing and sends it trimmed", async () => {
    const onSubmit = vi.fn();
    render(<TokenGate onSubmit={onSubmit} />);
    const input = screen.getByLabelText("Token");
    expect(input).toHaveAttribute("type", "password");
    expect(input).toHaveAttribute("autocomplete", "off");
    await userEvent.type(input, "  0123456789abcdef  ");
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(onSubmit).toHaveBeenCalledWith("0123456789abcdef");
  });

  it("does not send an empty or blank token", async () => {
    const onSubmit = vi.fn();
    render(<TokenGate onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    await userEvent.type(screen.getByLabelText("Token"), "   ");
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("shows the error it is given as an alert", () => {
    render(<TokenGate onSubmit={() => {}} error="Token incorrecto." />);
    expect(screen.getByRole("alert")).toHaveTextContent("Token incorrecto.");
  });
});
