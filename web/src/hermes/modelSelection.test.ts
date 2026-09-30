import { expect, it } from "vitest";
import { modelIdentifiers, modelSwitchValue, ModelSwitchUnconfirmed, parseModelSwitchResult } from "./modelSelection";

it("builds only session-scoped model values, including spaced names", () => {
  expect(modelSwitchValue("openai", "Model 6 Pro")).toBe("Model 6 Pro --provider openai --session");
  for (const model of ["", " m", "m\nreset", "m --global", "m --session", "m --provider x", "m; reset", "x".repeat(129)]) expect(modelIdentifiers("p", model)).toBe(false);
  expect(modelIdentifiers("p x", "m")).toBe(false);
});

it("distinguishes confirmed, confirmation-required and deferred replies", () => {
  expect(parseModelSwitchResult({ confirm_required: true, value: "m", warning: "token=CANARY" })).toEqual({ kind: "confirmation" });
  expect(parseModelSwitchResult({ deferred: true, value: "m" })).toEqual({ kind: "deferred" });
  expect(parseModelSwitchResult({ value: "canonical", scope: "session", warning: "CANARY" })).toEqual({ kind: "applied", model: "canonical", warning: true });
  for (const value of [undefined, null, {}, [], { value: "" }, { value: "m", scope: "global" },
    { value: "m", confirm_required: "true" }, { value: "m", deferred: 1 }, { value: "m", warning: [] }]) expect(() => parseModelSwitchResult(value)).toThrow(ModelSwitchUnconfirmed);
});
