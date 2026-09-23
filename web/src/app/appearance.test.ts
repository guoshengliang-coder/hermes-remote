import { beforeEach, describe, expect, it } from "vitest";
import { applyTheme, effectiveLanguage, effectiveTheme, readLanguagePreference, readThemeMode, saveLanguagePreference, saveThemeMode } from "./appearance";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

describe("browser appearance preferences", () => {
  it("follows the system until an explicit theme is chosen, and applies one root attribute", () => {
    expect(readThemeMode()).toBe("system");
    expect(effectiveTheme("system", true)).toBe("dark");
    expect(effectiveTheme("system", false)).toBe("light");
    saveThemeMode("light");
    expect(effectiveTheme(readThemeMode(), true)).toBe("light");
    applyTheme(readThemeMode(), true);
    expect(document.documentElement.dataset.theme).toBe("light");
    saveThemeMode("dark");
    applyTheme(readThemeMode(), false);
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("keeps language choice distinct from the resolved language", () => {
    expect(readLanguagePreference()).toBe("system");
    expect(effectiveLanguage("system", { language: "zh-HK" })).toBe("zh");
    expect(effectiveLanguage("system", { language: "fr-FR" })).toBe("en");
    saveLanguagePreference("en");
    expect(effectiveLanguage(readLanguagePreference(), { language: "zh-CN" })).toBe("en");
    localStorage.setItem("hermes-go.language", "invalid");
    expect(readLanguagePreference()).toBe("system");
  });
});
