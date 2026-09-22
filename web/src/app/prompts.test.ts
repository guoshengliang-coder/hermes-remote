import { beforeEach, describe, expect, it } from "vitest";
import { clearPrompts, deletePrompt, loadPrompts, upsertPrompt } from "./prompts";

describe("saved prompts (Android PromptStore)", () => {
  beforeEach(() => localStorage.clear());

  it("appends, replaces in place, deletes, and clears on sign-out", () => {
    upsertPrompt({ id: "a", title: "A", body: "one" });
    upsertPrompt({ id: "b", title: "B", body: "two" });
    upsertPrompt({ id: "a", title: "A2", body: "uno" });
    expect(loadPrompts().map((p) => `${p.id}:${p.title}:${p.body}`)).toEqual(["a:A2:uno", "b:B:two"]);
    expect(deletePrompt("a").map((p) => p.id)).toEqual(["b"]);
    clearPrompts();
    expect(loadPrompts()).toEqual([]);
    expect(localStorage.getItem("hermes-go.prompts")).toBeNull();
  });

  it("ignores malformed storage", () => {
    localStorage.setItem("hermes-go.prompts", '[{"id":1},{"id":"x","body":"ok"},null]');
    expect(loadPrompts()).toEqual([{ id: "x", title: "", body: "ok" }]);
    localStorage.setItem("hermes-go.prompts", "{not json");
    expect(loadPrompts()).toEqual([]);
  });
});
