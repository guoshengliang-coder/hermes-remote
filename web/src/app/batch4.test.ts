import { beforeEach, describe, expect, it } from "vitest";
import { webDeviceFeatures, type GatewayCapabilities } from "../api/gateway";
import { HermesSocketError } from "../hermes/client";
import { REASONING_VALUES } from "../hermes/params";
import { sessionModelCommand } from "../hermes/slash";
import { moveError } from "../ui/SessionActions";
import { isReasoning, modelChipLabel } from "../ui/ModelSheet";
import { clearLocalPrefs, modelKey, modelPrefs, recordModelUse, rememberReasoning, toggleFavoriteModel } from "./localPrefs";

describe("the one slash command the Web app sends (matches the Gateway's filter)", () => {
  it("builds `/model <id> --provider <id> --session`", () => {
    expect(sessionModelCommand("anthropic", "claude-opus-5")).toBe("/model claude-opus-5 --provider anthropic --session");
    expect(sessionModelCommand("openrouter", "openrouter/anthropic/claude-3.5")).toBe("/model openrouter/anthropic/claude-3.5 --provider openrouter --session");
  });

  it("refuses ids the Gateway would refuse instead of sending them", () => {
    expect(sessionModelCommand("p", "has space")).toBeNull();
    expect(sessionModelCommand("p", 'q"uote')).toBeNull();
    expect(sessionModelCommand("p", "x;/reset")).toBeNull();
    expect(sessionModelCommand("a/b", "m")).toBeNull();
    expect(sessionModelCommand("p", "")).toBeNull();
  });

  it("reasoning values are exactly Android's eight", () => {
    expect([...REASONING_VALUES]).toEqual(["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]);
    expect(isReasoning("high")).toBe(true);
    expect(isReasoning("turbo")).toBe(false);
    expect(modelChipLabel("fable-5", "high", "zh")).toBe("fable-5 · 高");
    expect(modelChipLabel(null, null, "en")).toBe("Default model");
  });
});

describe("capability features", () => {
  it("an older Gateway lists none, so nothing extra shows", () => {
    const base = { version: 1, accountAuth: { webDeviceAccess: true } } as unknown as GatewayCapabilities;
    expect(webDeviceFeatures(base).size).toBe(0);
    const next = { version: 1, accountAuth: { webDeviceAccess: true, webDeviceFeatures: ["session-manage", "model-select", 7] } } as unknown as GatewayCapabilities;
    expect([...webDeviceFeatures(next)]).toEqual(["session-manage", "model-select"]);
  });
});

describe("move failures by Hermes code (ProjectMoveErrors.kt)", () => {
  const rpc = (code: number) => new HermesSocketError("rpc", "x", "session.workspace.move", code);
  it("maps busy / missing folder / gone / refused / other", () => {
    expect(moveError(rpc(4009)).code).toBe("HR-SESS-004");
    expect(moveError(rpc(4017)).code).toBe("HR-SESS-003");
    expect(moveError(rpc(4007)).code).toBe("HR-SESS-001");
    expect(moveError(rpc(4403)).code).toBe("HR-WEB-001");
    expect(moveError(new Error("boom")).code).toBe("HR-SESS-005");
  });
});

describe("model prefs (device-local, per Mac, cleared on sign-out)", () => {
  beforeEach(() => localStorage.clear());
  it("recents (5, newest first), favourites toggle, reasoning presets", () => {
    for (const m of ["a", "b", "c", "d", "e", "f"]) recordModelUse("mac", modelKey("p", m));
    recordModelUse("mac", modelKey("p", "c"));
    expect(modelPrefs("mac").recents).toEqual(["p/c", "p/f", "p/e", "p/d", "p/b"]);
    expect(toggleFavoriteModel("mac", "p/a")).toEqual(["p/a"]);
    expect(toggleFavoriteModel("mac", "p/a")).toEqual([]);
    rememberReasoning("mac", "p/a", "high");
    expect(modelPrefs("mac").presets).toEqual({ "p/a": "high" });
    expect(modelPrefs("other").recents).toEqual([]);
    clearLocalPrefs();
    expect(modelPrefs("mac")).toEqual({ recents: [], favorites: [], presets: {} });
  });
});
