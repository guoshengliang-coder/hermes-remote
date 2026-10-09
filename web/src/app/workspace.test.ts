import { beforeEach, expect, it, vi } from "vitest";
import { DEFAULT_LIST_REM, readLayoutMode, readListWidth, saveLayoutMode, saveListWidth, workspaceBudget } from "./workspace";

beforeEach(() => { vi.restoreAllMocks(); localStorage.clear(); });
it("uses pane budgets rather than an 840px device breakpoint", () => {
  expect(workspaceBudget(623, 16).eligible).toBe(false);
  expect(workspaceBudget(624, 16)).toMatchObject({ eligible: true, list: 240, maximum: 240 });
  expect(workspaceBudget(700, 16)).toMatchObject({ eligible: true, list: 300, maximum: 316 });
  expect(workspaceBudget(1000, 16, 30).list).toBe(480);
  expect(workspaceBudget(780, 20).eligible).toBe(true);
  expect(workspaceBudget(779, 20).eligible).toBe(false);
  expect(workspaceBudget(700, 24).eligible).toBe(false);
});
it("retains a preferred width while temporarily narrow and rejects invalid saved values", () => {
  saveListWidth(28); saveLayoutMode("single");
  expect(readLayoutMode()).toBe("single");
  expect(workspaceBudget(700, 16, readListWidth()).list).toBe(316);
  expect(readListWidth()).toBe(28);
  expect(workspaceBudget(1000, 16, readListWidth()).list).toBe(448);
  localStorage.setItem("hermes-go.workspace.widthRem", "Infinity");
  expect(readListWidth()).toBe(DEFAULT_LIST_REM);
});
it("preference storage failure never blocks the workspace", () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("denied"); });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("quota"); });
  expect(readLayoutMode()).toBe("auto");
  expect(readListWidth()).toBe(DEFAULT_LIST_REM);
  expect(() => { saveLayoutMode("single"); saveListWidth(20); }).not.toThrow();
});
