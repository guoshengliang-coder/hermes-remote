import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
import {
  ObserverStateStore,
  type ObserverPersistentState,
  type ObserverStatePersistence,
} from "./session-observer.js";
import { HermesSessionObserver, type ObserverSocket } from "./session-observer-runner.js";

class FakeSocket implements ObserverSocket {
  readyState = 1;
  readonly sent: string[] = [];
  private readonly listeners = new Map<string, Array<(...args: never[]) => void>>();

  on(event: "open" | "message" | "close" | "error", listener: (...args: never[]) => void): this {
    const current = this.listeners.get(event) ?? [];
    current.push(listener);
    this.listeners.set(event, current);
    return this;
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.emit("close");
  }

  emit(event: string, ...args: unknown[]): void {
    for (const listener of this.listeners.get(event) ?? []) listener(...args as never[]);
  }
}

test("observer polls the read-only RPC and persists before forwarding transitions", async () => {
  const root = await mkdtemp(join(tmpdir(), "hermes-observer-runner-"));
  const socket = new FakeSocket();
  const forwarded: SessionLifecycleEvent[] = [];
  const observer = new HermesSessionObserver({
    deviceId: "mac-mini",
    stateStore: new ObserverStateStore(join(root, "state.json")),
    websocketUrl: async () => "ws://hermes.test/api/ws?ticket=test",
    createSocket: () => socket,
    sendLifecycle: (event) => { forwarded.push(event); return true; },
    activePollMs: 5,
    idlePollMs: 5,
    rpcTimeoutMs: 1_000,
    reconnectMs: 1_000,
  });
  await observer.start();
  socket.emit("message", JSON.stringify({
    jsonrpc: "2.0",
    method: "event",
    params: { type: "gateway.ready", payload: {} },
  }));
  await waitFor(() => socket.sent.length === 1);
  const firstId = JSON.parse(socket.sent[0]).id;
  socket.emit("message", JSON.stringify({ jsonrpc: "2.0", id: firstId, result: { sessions: [] } }));

  await waitFor(() => socket.sent.length === 2);
  const secondId = JSON.parse(socket.sent[1]).id;
  socket.emit("message", JSON.stringify({
    jsonrpc: "2.0",
    id: secondId,
    result: { sessions: [{
      id: "runtime-1",
      session_key: "stored-1",
      status: "working",
      last_active: 123,
      title: "Research",
      preview: "private content",
    }] },
  }));

  await waitFor(() => forwarded.length === 1);
  assert.equal(forwarded[0].event, "run.started");
  assert.equal(forwarded[0].title, "Research");
  assert.equal(JSON.stringify(forwarded[0]).includes("private content"), false);
  assert.equal(observer.pendingCount(), 1);
  observer.acknowledge(forwarded[0].eventId);
  await waitFor(() => observer.pendingCount() === 0);
  observer.stop();
});

test("unsupported active-list closes the observer without fabricating lifecycle events", async () => {
  const root = await mkdtemp(join(tmpdir(), "hermes-observer-unsupported-"));
  const socket = new FakeSocket();
  const forwarded: SessionLifecycleEvent[] = [];
  const observer = new HermesSessionObserver({
    deviceId: "mac-mini",
    stateStore: new ObserverStateStore(join(root, "state.json")),
    websocketUrl: async () => "ws://hermes.test/api/ws?ticket=test",
    createSocket: () => socket,
    sendLifecycle: (event) => { forwarded.push(event); return true; },
    reconnectMs: 60_000,
    unsupportedRetryMs: 60_000,
  });
  await observer.start();
  socket.emit("message", JSON.stringify({
    jsonrpc: "2.0",
    method: "event",
    params: { type: "gateway.ready", payload: {} },
  }));
  await waitFor(() => socket.sent.length === 1);
  const id = JSON.parse(socket.sent[0]).id;
  socket.emit("message", JSON.stringify({
    jsonrpc: "2.0",
    id,
    error: { code: -32601, message: "Method not found" },
  }));
  await waitFor(() => socket.readyState === 3);
  assert.deepEqual(forwarded, []);
  observer.stop();
});

test("a corrupt observer state file does not disable the Connector observer", async () => {
  const root = await mkdtemp(join(tmpdir(), "hermes-observer-corrupt-"));
  const statePath = join(root, "state.json");
  await writeFile(statePath, "{not-json", "utf8");
  const socket = new FakeSocket();
  const logs: string[] = [];
  let created = 0;
  const observer = new HermesSessionObserver({
    deviceId: "mac-mini",
    stateStore: new ObserverStateStore(statePath),
    websocketUrl: async () => "ws://hermes.test/api/ws?ticket=test",
    createSocket: () => { created += 1; return socket; },
    sendLifecycle: () => true,
    log: (message) => logs.push(message),
  });
  await observer.start();
  await waitFor(() => created === 1);
  assert.equal(logs.some((message) => message.includes("state was ignored")), true);
  observer.stop();
});

test("never forwards a lifecycle transition before its outbox snapshot is durable", async () => {
  const socket = new FakeSocket();
  const forwarded: SessionLifecycleEvent[] = [];
  const logs: string[] = [];
  const failingStore: ObserverStatePersistence = {
    load: async () => undefined,
    save: async (state: ObserverPersistentState) => {
      if (state.outbox.length > 0) throw new Error("disk full");
    },
  };
  const observer = new HermesSessionObserver({
    deviceId: "mac-mini",
    stateStore: failingStore,
    websocketUrl: async () => "ws://hermes.test/api/ws?ticket=test",
    createSocket: () => socket,
    sendLifecycle: (event) => { forwarded.push(event); return true; },
    activePollMs: 60_000,
    idlePollMs: 5,
    rpcTimeoutMs: 1_000,
    reconnectMs: 60_000,
    log: (message) => logs.push(message),
  });
  await observer.start();
  socket.emit("message", JSON.stringify({
    jsonrpc: "2.0",
    method: "event",
    params: { type: "gateway.ready", payload: {} },
  }));
  await waitFor(() => socket.sent.length === 1);
  const baselineId = JSON.parse(socket.sent[0]).id;
  socket.emit("message", JSON.stringify({ jsonrpc: "2.0", id: baselineId, result: { sessions: [] } }));
  await waitFor(() => socket.sent.length === 2);
  const transitionId = JSON.parse(socket.sent[1]).id;
  socket.emit("message", JSON.stringify({
    jsonrpc: "2.0",
    id: transitionId,
    result: { sessions: [{
      id: "runtime-1",
      session_key: "stored-1",
      status: "working",
      last_active: 123,
    }] },
  }));
  await waitFor(() => logs.some((message) => message.includes("disk full")));
  assert.deepEqual(forwarded, []);
  assert.equal(observer.pendingCount(), 1);
  observer.stop();
});

async function waitFor(predicate: () => boolean, timeoutMs = 1_000): Promise<void> {
  const started = Date.now();
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error("timed out waiting for condition");
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}
