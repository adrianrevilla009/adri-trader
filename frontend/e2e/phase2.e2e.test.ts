import { describe, expect, it } from "vitest";
import { client } from "./helpers";

describe("e2e phase 2: after the bot was restarted", () => {
  const api = client("e2e-two");

  it("still has the agent, its changes and its history (they live in the database)", async () => {
    const agent = (await api.agents()).find((a) => a.id === "e2e-trend");
    expect(agent).toMatchObject({ capital: 50, state: "ACTIVE", mode: "DEMO" });
    expect((await api.audit("e2e-trend")).length).toBeGreaterThanOrEqual(4);
  });

  it("is back in DEMO and not locked: a restart always returns to the safe state", async () => {
    const mode = await api.system();
    expect(mode.mode).toBe("DEMO");
    expect(mode.lockedUntil).toBeNull();
  });
});
