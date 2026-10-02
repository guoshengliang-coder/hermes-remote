import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { expect, it, vi } from "vitest";

it("targets the parent URL origin and rejects messages from another origin or window", () => {
  const postMessage = vi.fn();
  const parent = { postMessage };
  const handlers = new Map<string, (event: unknown) => void>();
  const document = {
    getElementById: () => ({}),
    // Stop an accepted init before DOM rendering: this tests the trust boundary itself.
    get documentElement(): never {
      throw new Error("accepted init");
    },
  };
  const root = {
    document,
    parent,
    URL,
    location: { href: "https://gateway.example/app/charts/chart.html" },
    HermesTableChart: {},
    addEventListener: (name: string, handler: (event: unknown) => void) =>
      handlers.set(name, handler),
  };
  const source = readFileSync(
    "../android/app/src/main/assets/table-chart/chart.js",
    "utf8",
  );
  runInNewContext(source, root);
  expect(postMessage).toHaveBeenCalledWith(
    expect.objectContaining({ type: "ready", protocol: 1 }),
    "https://gateway.example",
  );
  const receive = handlers.get("message")!;
  const data = { protocol: 1, type: "init", nonce: "test" };
  expect(() =>
    receive({ source: parent, origin: "https://untrusted.example", data }),
  ).not.toThrow();
  expect(() =>
    receive({ source: {}, origin: "https://gateway.example", data }),
  ).not.toThrow();
  expect(() =>
    receive({ source: parent, origin: "https://gateway.example", data }),
  ).toThrow("accepted init");
});
