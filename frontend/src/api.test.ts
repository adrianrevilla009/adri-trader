import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApi } from "./api";

function reply(status: number, body: unknown, statusText = ""): Response {
  return new Response(body === null ? "" : typeof body === "string" ? body : JSON.stringify(body), { status, statusText });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("createApi", () => {
  it("sends the token and the actor on every call and parses the answer", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(reply(200, [{ id: "a" }])));
    vi.stubGlobal("fetch", fetchMock);

    const agents = await createApi("http://host", "secret-token", "ui").agents();

    expect(agents).toEqual([{ id: "a" }]);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://host/api/agents");
    expect(init.method).toBe("GET");
    expect(init.headers).toMatchObject({ Authorization: "Bearer secret-token", "X-Actor": "ui" });
    expect(init.body).toBeUndefined();
  });

  it("sends JSON bodies with a content type and encodes ids in the path", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(reply(200, {})));
    vi.stubGlobal("fetch", fetchMock);
    const api = createApi("", "t");

    await api.updateAgent("a/b", { capital: 80 });
    await api.stopAgent("x y");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/agents/a%2Fb");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"capital":80}');
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    expect((fetchMock.mock.calls[1] as [string])[0]).toBe("/api/agents/x%20y/stop");
  });

  it("turns an error answer into an ApiError with the message of the server", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(409, { error: "agent already exists: t1" })));

    await expect(createApi("", "t").createAgent({ id: "t1", strategyId: "trend", params: {}, universe: ["X"], capital: 40, steps: [] })).rejects.toMatchObject(
      { name: "ApiError", status: 409, message: "agent already exists: t1" },
    );
  });

  it("falls back to the status text when the body is not the expected JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(502, "<html>bad gateway</html>", "Bad Gateway")));

    const error = await createApi("", "t").agents().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe("Bad Gateway");
  });

  it("reports a clear message when the server cannot be reached", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    const error = (await createApi("", "t").agents().catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(0);
    expect(error.message).toContain("No se puede conectar");
  });

  it("returns null for an empty successful answer instead of failing", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, null)));

    await expect(createApi("", "t").system()).resolves.toBeNull();
  });
});
