import type { Language } from "../errors";
import { detectLanguage } from "./i18n";

export type ThemeMode = "system" | "light" | "dark";
export type LanguagePreference = "system" | "zh" | "en";

const THEME_KEY = "hermes-go.theme";
const LANGUAGE_KEY = "hermes-go.language";

function readChoice<T extends string>(key: string, allowed: readonly T[]): T {
  try {
    const value = localStorage.getItem(key);
    return allowed.find((x) => x === value) ?? allowed[0]!;
  } catch {
    return allowed[0]!;
  }
}

function saveChoice(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch { /* private browsing */ }
}

export const readThemeMode = (): ThemeMode => readChoice(THEME_KEY, ["system", "light", "dark"]);
export const saveThemeMode = (mode: ThemeMode): void => saveChoice(THEME_KEY, mode);
export const readLanguagePreference = (): LanguagePreference => readChoice(LANGUAGE_KEY, ["system", "zh", "en"]);
export const saveLanguagePreference = (choice: LanguagePreference): void => saveChoice(LANGUAGE_KEY, choice);

export function effectiveTheme(mode: ThemeMode, darkSystem: boolean): "light" | "dark" {
  return mode === "system" ? (darkSystem ? "dark" : "light") : mode;
}

export function effectiveLanguage(choice: LanguagePreference, nav: { language?: string; languages?: readonly string[] } | undefined = globalThis.navigator): Language {
  return choice === "system" ? detectLanguage(nav) : choice;
}

export function applyTheme(mode: ThemeMode, darkSystem = globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false): void {
  document.documentElement.dataset.theme = effectiveTheme(mode, darkSystem);
}
