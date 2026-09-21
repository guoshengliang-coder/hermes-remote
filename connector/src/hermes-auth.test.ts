import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { HermesAuth, fetchHermesOpenApi } from "./hermes-auth.js";

interface Seen { method?: string; url?: string; token?: string; cookie?: string }

async function serve(
  handler: (request: IncomingMessage, respond: (status: number, body: string, headers?: Record<string, string>) => void) => void,
): Promise<{ server: Server; baseUrl: string; seen: Seen[] }> {
  const seen: Seen[] = [];
  const server = createServer((request, response) => {
    seen.push({
      method: request.method,
      url: request.url,
      token: request.headers["x-hermes-session-token"] as string | undefined,
      cookie: request.headers.cookie,
    });
    request.resume();
    handler(request, (status, body, headers = {}) => {
      response.writeHead(status, headers);
      response.end(body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return { server, baseUrl: `http://127.0.0.1:${port}`, seen };
}

const schema = JSON.stringify({ openapi: "3.1.0", paths: { "/api/status": { get: {} } } });

test("the schema is fetched with the session token every relayed call uses", async () => {
  const { server, baseUrl, seen } = await serve((_request, respond) => respond(200, schema));
  try {
    const auth = new HermesAuth({ baseUrl, sessionToken: "local-session-token" });
    const result = await fetchHermesOpenApi(auth);

    assert.deepEqual(result, { kind: "ok", body: schema });
    assert.equal(seen[0].url, "/openapi.json");
    assert.equal(seen[0].method, "GET");
    assert.equal(seen[0].token, "local-session-token");
  } finally {
    server.close();
  }
});

test("a Basic Auth Hermes is logged into first and the schema carries its cookie", async () => {
  const { server, baseUrl, seen } = await serve((request, respond) => {
    if (request.url === "/auth/password-login") {
      respond(200, "{}", { "set-cookie": "hermes_session=abc; Path=/; HttpOnly" });
      return;
    }
    respond(200, schema);
  });
  try {
    const auth = new HermesAuth({ baseUrl, username: "owner", password: "correct horse" });
    const result = await fetchHermesOpenApi(auth);

    assert.equal(result.kind, "ok");
    assert.deepEqual(seen.map((entry) => entry.url), ["/auth/password-login", "/openapi.json"]);
    assert.equal(seen[1].cookie, "hermes_session=abc");
  } finally {
    server.close();
  }
});

test("the relay path still refuses anything outside /api/", async () => {
  const auth = new HermesAuth({ baseUrl: "http://127.0.0.1:9", sessionToken: "t" });
  await assert.rejects(auth.request("/openapi.json", { method: "GET" }), /unsupported Hermes path/);
});

test("a schema over the ceiling is too_large, declared or streamed", async () => {
  const big = "x".repeat(4096);
  const declared = await serve((_request, respond) => respond(200, big, { "content-length": String(big.length) }));
  const streamed = await serve((_request, respond) => respond(200, big, { "transfer-encoding": "chunked" }));
  try {
    for (const { baseUrl } of [declared, streamed]) {
      const result = await fetchHermesOpenApi(new HermesAuth({ baseUrl, sessionToken: "t" }), 1024);
      assert.deepEqual(result, { kind: "too_large" });
    }
    // The production ceiling leaves room for today's ~250 KB schema.
    const ok = await fetchHermesOpenApi(new HermesAuth({ baseUrl: declared.baseUrl, sessionToken: "t" }));
    assert.equal(ok.kind, "ok");
  } finally {
    declared.server.close();
    streamed.server.close();
  }
});

test("a non-2xx is reported with its status, and a dead port as unreachable", async () => {
  const { server, baseUrl } = await serve((_request, respond) => respond(404, '{"detail":"Not Found"}'));
  try {
    assert.deepEqual(
      await fetchHermesOpenApi(new HermesAuth({ baseUrl, sessionToken: "t" })),
      { kind: "http_status", status: 404 },
    );
  } finally {
    server.close();
  }
  const closed = await serve((_request, respond) => respond(200, schema));
  closed.server.close();
  await new Promise((resolve) => closed.server.once("close", resolve));
  assert.deepEqual(
    await fetchHermesOpenApi(new HermesAuth({ baseUrl: closed.baseUrl, sessionToken: "t" })),
    { kind: "unreachable" },
  );
});
