import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { appError, CATALOG } from "../errors";
import { installStartup } from "./fixture";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

let shell: ReturnType<typeof installStartup>;
beforeEach(() => { localStorage.clear(); vi.useFakeTimers(); });
afterEach(() => { shell?.cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

it.each([['light', 'zh', 'zh-CN'], ['dark', 'en', 'en']])("restores saved %s theme and language before app JS", (theme, language, lang) => {
  localStorage.setItem('hermes-go.theme', theme!);
  localStorage.setItem('hermes-go.language', language!);
  localStorage.setItem('hermes-go.fontSize', 'xlarge');
  shell = installStartup();
  expect(document.documentElement.dataset.theme).toBe(theme);
  expect(document.documentElement.lang).toBe(lang);
  expect(document.documentElement.dataset.fontSize).toBe('xlarge');
  expect(shell.gate.textContent).toContain('HERMES GO');
  expect(shell.root.inert).toBe(true);
});
it("private browsing and corrupt choices do not prevent the shell", () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  shell = installStartup();
  expect(shell.controller.pending).toBe(true);
  expect(document.documentElement.dataset.theme).toMatch(/light|dark/);
  expect(document.documentElement.dataset.fontSize).toBeUndefined();
});
it("a fast result enters immediately and never reveals loading furniture", () => {
  shell = installStartup();
  vi.advanceTimersByTime(300);
  shell.controller.complete();
  expect(document.documentElement.dataset.startup).toBe('done');
  expect(shell.root.inert).toBe(false);
  expect(shell.root.hasAttribute('aria-hidden')).toBe(false);
  vi.advanceTimersByTime(16000);
  expect(shell.gate.querySelector<HTMLElement>('#startup-loading')!.hidden).toBe(true);
  expect(shell.gate.hidden).toBe(true);
  expect(shell.gate.querySelector<HTMLElement>('#startup-error')!.hidden).toBe(true);
});
it("reveals a single indeterminate strip at 700ms", () => {
  shell = installStartup();
  vi.advanceTimersByTime(699);
  expect(shell.gate.querySelector<HTMLElement>('#startup-loading')!.hidden).toBe(true);
  vi.advanceTimersByTime(1);
  expect(shell.gate.querySelector<HTMLElement>('#startup-loading')!.hidden).toBe(false);
  expect(shell.gate.querySelector('[role="progressbar"]')).toBeNull();
});
it.each(['zh', 'en'] as const)("times out without application JS, with registered %s copy and retry", language => {
  localStorage.setItem('hermes-go.language', language);
  shell = installStartup();
  vi.advanceTimersByTime(15000);
  expect(shell.gate.querySelector('#startup-summary')!.textContent).toBe(CATALOG['HR-WEB-013'][language]);
  expect(shell.gate.querySelector('#startup-code')!.textContent).toBe('HR-WEB-013');
  expect(shell.gate.querySelector<HTMLButtonElement>('#startup-retry')!.hidden).toBe(false);
  expect(shell.gate.querySelector<HTMLElement>('#startup-loading')!.hidden).toBe(true);
});
it("timeout rejects late completion and reloads to release even a hung shared auth refresh", () => {
  shell = installStartup();
  const retry = vi.fn(), timeout = vi.fn();
  shell.controller.bind(retry, timeout);
  vi.advanceTimersByTime(15000);
  expect(timeout).toHaveBeenCalledOnce();
  shell.controller.complete();
  expect(document.documentElement.dataset.startup).toBe('failed');
  const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
  shell.gate.querySelector<HTMLButtonElement>('#startup-retry')!.click();
  expect(reload).toHaveBeenCalledOnce();
  expect(retry).not.toHaveBeenCalled();
});
it("a completed network failure can retry the boot in place", () => {
  shell = installStartup();
  const retry = vi.fn();
  shell.controller.bind(retry, vi.fn());
  shell.controller.fail(appError('HR-WEB-003'));
  shell.gate.querySelector<HTMLButtonElement>('#startup-retry')!.click();
  expect(retry).toHaveBeenCalledOnce();
  expect(shell.controller.pending).toBe(true);
  shell.controller.complete();
  expect(document.documentElement.dataset.startup).toBe('done');
});
it("an app bundle arriving after the asset deadline must preserve the failure", () => {
  shell = installStartup();
  vi.advanceTimersByTime(15000);
  new Function(readFileSync(resolve(process.cwd(), 'src/startup/early.js'), 'utf8'))();
  const timeout = vi.fn();
  shell.controller.bind(vi.fn(), timeout);
  expect(timeout).toHaveBeenCalledOnce();
  expect(shell.controller.pending).toBe(false);
});
it("preserves structured failures, with diagnostics hidden and redacted", () => {
  shell = installStartup();
  const error = appError('HR-WEB-003', 'Authorization: Bearer secret-token');
  shell.controller.fail(error);
  expect(shell.gate.querySelector('#startup-code')!.textContent).toBe(error.code);
  expect(shell.gate.querySelector<HTMLDetailsElement>('#startup-details')!.open).toBe(false);
  expect(shell.gate.textContent).not.toContain('secret-token');
  expect(shell.gate.querySelector('#startup-cause')!.textContent).toBe(error.details);
});
it("reveal and watchdog stay cleared through visibility changes and reconnection", () => {
  shell = installStartup(); shell.controller.complete();
  document.dispatchEvent(new Event('visibilitychange'));
  window.dispatchEvent(new Event('online'));
  vi.advanceTimersByTime(30000);
  expect(document.documentElement.dataset.startup).toBe('done');
});
