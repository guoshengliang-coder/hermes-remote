import type { Language } from "../errors";

// UI language follows the browser, with DESIGN.md §6's rule: a `zh` language code (including zh-TW /
// zh-HK, for now) draws Simplified Chinese, every other language draws English. Only a browser that
// reports no language at all falls back to Chinese.

export function detectLanguage(nav: { language?: string; languages?: readonly string[] } | undefined = globalThis.navigator): Language {
  const tag = (nav?.language || nav?.languages?.[0] || "").toLowerCase();
  if (tag === "") return "zh";
  return tag === "zh" || tag.startsWith("zh-") ? "zh" : "en";
}

export type Translate = (zh: string, en: string) => string;

export function translator(language: Language): Translate {
  return (zh, en) => (language === "en" ? en : zh);
}
