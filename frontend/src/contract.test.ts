// @vitest-environment node
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApi } from "./api";

/** Contract test, client side: the same file the backend is tested against (contract/api-contract.json). */
interface Operation {
  name: string;
  method: string;
  path: string;
  status: number;
  request?: unknown;
  response: unknown;
  call?: { fn: string; args: unknown[] };
}

const contract = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../contract/api-contract.json", import.meta.url)), "utf8"),
) as { operations: Operation[] };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("API contract (client side)", () => {
  const withCall = contract.operations.filter((op) => op.call !== undefined);

  it("covers every function of the client at least once", () => {
    const api = createApi("", "token");
    const covered = new Set(withCall.map((op) => op.call?.fn));
    expect(Object.keys(api).filter((fn) => !covered.has(fn))).toEqual([]);
  });

  it.each(withCall.map((op) => [op.name, op] as const))("%s sends and reads what the contract says", async (_n, op) => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(op.response), { status: op.status, headers: { "Content-Type": "application/json" } }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const api = createApi("http://backend", "secret-token-123456", "ui") as unknown as Record<
      string,
      (...args: unknown[]) => Promise<unknown>
    >;
    const fn = api[op.call!.fn];
    expect(fn).toBeTypeOf("function");

    const result = fn!(...op.call!.args);
    if (op.status >= 400) {
      await expect(result).rejects.toMatchObject({ name: "ApiError", status: op.status });
    } else {
      await expect(result).resolves.toEqual(op.response);
    }

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`http://backend${op.path}`);
    expect(init.method).toBe(op.method);
    const headers = init.headers as Record<string, string>;
    expect(headers["Authorization"]).toBe("Bearer secret-token-123456");
    expect(headers["X-Actor"]).toBe("ui");
    if (op.request === undefined) {
      expect(init.body).toBeUndefined();
    } else {
      expect(JSON.parse(init.body as string)).toEqual(op.request);
      expect(headers["Content-Type"]).toBe("application/json");
    }
  });

  it("turns every error of the contract into an ApiError with the server's message", async () => {
    for (const op of contract.operations.filter((o) => o.status >= 400)) {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response(JSON.stringify(op.response), { status: op.status })),
      );
      const api = createApi("", "t");
      const error = await api.system().then(
        () => null,
        (e: unknown) => e,
      );
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(op.status);
      expect((error as ApiError).message).toBe((op.response as { error: string }).error);
    }
  });
});
