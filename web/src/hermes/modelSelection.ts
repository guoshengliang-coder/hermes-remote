import type { JsonObject } from "./types";

// Mirrors the bounded Gateway/Connector checks. Spaces in a model name are plain Hermes tokens,
// never quoting or switch flags; provider is always one token.
export function modelIdentifiers(provider: unknown, model: unknown): boolean {
  return typeof provider === "string" && /^[A-Za-z0-9._-]{1,64}$/.test(provider)
    && typeof model === "string" && model.length <= 128 && model.trim() === model
    && /^[\p{L}\p{N}._:/@+ -]+$/u.test(model) && !model.split(/ +/).some((token) => token.startsWith("--"));
}

export function modelSwitchValue(provider: string, model: string): string | null {
  return modelIdentifiers(provider, model) ? `${model} --provider ${provider} --session` : null;
}

export type ModelSwitchResult =
  | { kind: "confirmation" }
  | { kind: "deferred" }
  | { kind: "applied"; model: string; warning: boolean };

export class ModelSwitchUnconfirmed extends Error {}

export function parseModelSwitchResult(value: unknown): ModelSwitchResult {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new ModelSwitchUnconfirmed("model reply is not an object");
  const body = value as JsonObject;
  if (body.scope !== undefined && body.scope !== "session") throw new ModelSwitchUnconfirmed("model reply has unexpected scope");
  for (const flag of ["confirm_required", "deferred"] as const) {
    if (body[flag] !== undefined && typeof body[flag] !== "boolean") throw new ModelSwitchUnconfirmed("model reply has an invalid outcome flag");
  }
  if (body.warning !== undefined && body.warning !== null && typeof body.warning !== "string") throw new ModelSwitchUnconfirmed("model reply has an invalid warning");
  if (body.confirm_required === true) return { kind: "confirmation" };
  if (body.deferred === true) return { kind: "deferred" };
  if (typeof body.value !== "string" || !body.value.trim() || body.value.length > 128 || /[\u0000-\u001f\u007f]/.test(body.value)) throw new ModelSwitchUnconfirmed("model reply has no applied value");
  return { kind: "applied", model: body.value, warning: typeof body.warning === "string" && Boolean(body.warning.trim()) };
}
