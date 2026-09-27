import type { Language } from "../errors";
import { detectLanguage } from "./i18n";

export type ThemeMode = "system" | "light" | "dark";
export type LanguagePreference = "system" | "zh" | "en";
/** Named root-font-size steps; `standard` keeps the browser's own default. */
export type FontSize = "standard" | "large" | "xlarge" | "xxlarge";
export const FONT_SIZES: readonly FontSize[] = ["standard", "large", "xlarge", "xxlarge"];

const THEME_KEY = "hermes-go.theme";
const LANGUAGE_KEY = "hermes-go.language";
const FONT_SIZE_KEY = "hermes-go.fontSize";

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

export const readFontSize = (): FontSize => readChoice(FONT_SIZE_KEY, FONT_SIZES);
export const saveFontSize = (size: FontSize): void => saveChoice(FONT_SIZE_KEY, size);

/**
 * One root font-size scales the whole surface: every size in styles.css is in `rem`, so text,
 * line height, spacing and the 44px touch targets grow together. `standard` removes the attribute
 * entirely instead of pinning 100%, so the browser's own default font size stays in charge — the
 * Home Screen app blocks Safari's page zoom (noZoom.ts), which leaves this as the only control.
 */
export function applyFontSize(size: FontSize): void {
  if (size === "standard") delete document.documentElement.dataset.fontSize;
  else document.documentElement.dataset.fontSize = size;
}
