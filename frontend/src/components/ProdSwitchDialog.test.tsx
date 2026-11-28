import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api";
import type { Api } from "../api";
import { ProdSwitchDialog } from "./ProdSwitchDialog";

const PHRASE = "SWITCH TO PROD";
const PROD = { mode: "PROD", available: ["DEMO", "PROD"], prodEnabled: true, lockedUntil: null, confirmPhrase: PHRASE };

function setup(overrides: Partial<Record<"requestProd" | "confirmProd", ReturnType<typeof vi.fn>>> = {}) {
  const expiresAt = new Date(Date.now() + 120_000).toISOString();
  const mocks = {
    requestProd: vi.fn().mockResolvedValue({ challengeId: "c1", expiresAt, confirmPhrase: PHRASE }),
    confirmProd: vi.fn().mockResolvedValue(PROD),
    ...overrides,
  };
  const onDone = vi.fn();
  const onCancel = vi.fn();
  render(<ProdSwitchDialog api={mocks as unknown as Api} phrase={PHRASE} onDone={onDone} onCancel={onCancel} />);
  return { mocks, onDone, onCancel, user: userEvent.setup() };
}

async function tickAll(user: ReturnType<typeof userEvent.setup>) {
  for (const box of screen.getAllByRole("checkbox")) {
    await user.click(box);
  }
}

describe("ProdSwitchDialog", () => {
  it("cannot ask for the challenge until every warning is ticked", async () => {
    const { mocks, user } = setup();
    const ask = screen.getByRole("button", { name: "Pedir confirmación" });
    expect(ask).toBeDisabled();
    const boxes = screen.getAllByRole("checkbox");
    await user.click(boxes[0]!);
    await user.click(boxes[1]!);
    expect(ask).toBeDisabled();
    await user.click(boxes[2]!);
    expect(ask).toBeEnabled();
    expect(mocks.requestProd).not.toHaveBeenCalled();
  });

  it("needs a six-digit code and the exact phrase before the last screen, and nothing is sent until the end", async () => {
    const { mocks, user } = setup();
    await tickAll(user);
    await user.click(screen.getByRole("button", { name: "Pedir confirmación" }));

    const next = await screen.findByRole("button", { name: "Continuar" });
    expect(next).toBeDisabled();
    await user.type(screen.getByLabelText("Código MFA"), "12345");
    await user.type(screen.getByLabelText(/Escribe/), PHRASE);
    expect(next).toBeDisabled(); // five digits
    await user.type(screen.getByLabelText("Código MFA"), "6");
    expect(next).toBeEnabled();

    await user.clear(screen.getByLabelText(/Escribe/));
    await user.type(screen.getByLabelText(/Escribe/), "switch to prod");
    expect(next).toBeDisabled(); // case matters
    await user.clear(screen.getByLabelText(/Escribe/));
    await user.type(screen.getByLabelText(/Escribe/), PHRASE);
    await user.click(next);

    expect(mocks.confirmProd).not.toHaveBeenCalled(); // a last confirmation screen comes first
    await user.click(await screen.findByRole("button", { name: "Sí, cambiar a PROD ahora" }));
    expect(mocks.confirmProd).toHaveBeenCalledWith("c1", "123456", PHRASE);
  });

  it("reports the new mode when the server accepts", async () => {
    const { onDone, user } = setup();
    await tickAll(user);
    await user.click(screen.getByRole("button", { name: "Pedir confirmación" }));
    await user.type(await screen.findByLabelText("Código MFA"), "123456");
    await user.type(screen.getByLabelText(/Escribe/), PHRASE);
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await user.click(await screen.findByRole("button", { name: "Sí, cambiar a PROD ahora" }));
    expect(onDone).toHaveBeenCalledWith(PROD);
  });

  it("starts again from the warnings, with the error, when the server rejects (the challenge is spent)", async () => {
    const confirmProd = vi.fn().mockRejectedValue(new ApiError(403, "confirmation rejected: ask for a new challenge"));
    const { onDone, user } = setup({ confirmProd });
    await tickAll(user);
    await user.click(screen.getByRole("button", { name: "Pedir confirmación" }));
    await user.type(await screen.findByLabelText("Código MFA"), "000000");
    await user.type(screen.getByLabelText(/Escribe/), PHRASE);
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await user.click(await screen.findByRole("button", { name: "Sí, cambiar a PROD ahora" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("confirmation rejected");
    expect(screen.getByRole("button", { name: "Pedir confirmación" })).toBeDisabled(); // checks are cleared too
    expect(onDone).not.toHaveBeenCalled();
  });

  it("shows why the challenge was refused (feature off or locked) and stays on the first screen", async () => {
    const requestProd = vi.fn().mockRejectedValue(new ApiError(423, "too many failed confirmations: locked until later"));
    const { user } = setup({ requestProd });
    await tickAll(user);
    await user.click(screen.getByRole("button", { name: "Pedir confirmación" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("locked until later");
    expect(screen.getByRole("button", { name: "Pedir confirmación" })).toBeInTheDocument();
  });

  it("cancel leaves without calling the server", async () => {
    const { mocks, onCancel, user } = setup();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onCancel).toHaveBeenCalled();
    expect(mocks.requestProd).not.toHaveBeenCalled();
    expect(mocks.confirmProd).not.toHaveBeenCalled();
  });
});
