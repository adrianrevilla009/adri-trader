import { describe, expect, it } from "vitest";
import { ApiError, createApi } from "../src/api";
import { client, env, totp } from "./helpers";

const PHRASE = "SWITCH TO PROD";

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error("expected the call to fail");
}

describe("e2e phase 1: the real client against the real bot and database", () => {
  const api = client("e2e-one");

  it("rejects a wrong token and shows nothing", async () => {
    const bad = createApi(env("E2E_API_URL"), "not-the-token-0123456789");
    expect((await failure(bad.agents())).status).toBe(401);
  });

  it("starts in DEMO with the catalog, the instruments and the limits", async () => {
    const mode = await api.system();
    expect(mode.mode).toBe("DEMO");
    expect(mode.prodEnabled).toBe(true);
    expect((await api.strategies()).map((s) => s.id)).toEqual(
      expect.arrayContaining(["buy-and-hold", "trend", "momentum", "vol-target", "swing"]),
    );
    expect((await api.instruments()).length).toBeGreaterThan(0);
    expect((await api.limits()).minimumCapital).toBe(40);
  });

  it("creates, changes, stops and resumes an agent and records every change", async () => {
    const [isin] = await api.instruments();
    const created = await api.createAgent({
      id: "e2e-trend",
      strategyId: "trend",
      params: { window: 100 },
      universe: [isin!],
      capital: 40,
      steps: [{ afterDays: 30, capital: 60 }],
    });
    expect(created).toMatchObject({ id: "e2e-trend", state: "ACTIVE", mode: "DEMO" });
    expect(created.params).toMatchObject({ window: 100 });

    expect((await api.updateAgent("e2e-trend", { capital: 50 })).capital).toBe(50);
    expect((await api.stopAgent("e2e-trend")).state).toBe("STOPPED");
    expect((await api.resumeAgent("e2e-trend")).state).toBe("ACTIVE");

    const audit = await api.audit("e2e-trend");
    expect(audit.map((a) => a.field)).toEqual(expect.arrayContaining(["created"]));
    expect(audit.length).toBeGreaterThanOrEqual(4);
    expect(audit.every((a) => a.actor === "api:e2e-one")).toBe(true);
  });

  it("refuses duplicates, a capital below the minimum, unknown agents and unknown instruments", async () => {
    const [isin] = await api.instruments();
    const base = { id: "e2e-trend", strategyId: "trend", universe: [isin!], capital: 40, params: {}, steps: [] };
    expect((await failure(api.createAgent(base))).status).toBe(409);
    expect((await failure(api.createAgent({ ...base, id: "e2e-poor", capital: 20 }))).status).toBe(400);
    expect((await failure(api.createAgent({ ...base, id: "e2e-bad", universe: ["IE00BKM4GZ66"] }))).status).toBe(400);
    expect((await failure(api.stopAgent("ghost"))).status).toBe(404);
  });

  it("never lets PROD be selected directly", async () => {
    expect((await failure(api.setMode("PROD"))).status).toBe(409);
    expect((await api.system()).mode).toBe("DEMO");
  });

  it("switches to PROD with a real MFA code, refuses to reuse it and goes back to DEMO", async () => {
    const secret = env("E2E_MFA_SECRET");
    const challenge = await api.requestProd();
    expect(challenge.confirmPhrase).toBe(PHRASE);

    // a wrong phrase spends the challenge even with a good code (failure 1 of 3)
    expect((await failure(api.confirmProd(challenge.challengeId, totp(secret), "switch to prod"))).status).toBe(403);
    expect((await api.system()).mode).toBe("DEMO");

    const second = await api.requestProd();
    const code = totp(secret);
    const prod = await api.confirmProd(second.challengeId, code, PHRASE);
    expect(prod.mode).toBe("PROD");

    expect((await api.setMode("DEMO")).mode).toBe("DEMO");

    const third = await api.requestProd();
    expect((await failure(api.confirmProd(third.challengeId, code, PHRASE))).status).toBe(403); // same code again (failure 2)
    expect((await api.system()).mode).toBe("DEMO");
  });

  it("locks the switch after repeated wrong codes", async () => {
    // the success of the previous test reset the counter, then it failed once (reused code): two more reach three
    for (let i = 0; i < 2; i++) {
      const c = await api.requestProd();
      expect((await failure(api.confirmProd(c.challengeId, "000000", PHRASE))).status).toBe(403);
    }

    const mode = await api.system();
    expect(mode.lockedUntil).not.toBeNull();
    expect((await failure(api.requestProd())).status).toBe(423);
    expect(mode.mode).toBe("DEMO");
  });
});
