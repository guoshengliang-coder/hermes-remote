import { beforeEach, describe, expect, it } from "vitest";
import { applyFontSize, applyTheme, effectiveLanguage, effectiveTheme, readFontSize, readLanguagePreference, readThemeMode, saveFontSize, saveLanguagePreference, saveThemeMode } from "./appearance";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
  document.documentElement.removeAttribute("data-font-size");
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

  it("scales the root with one attribute, and standard hands control back to the browser", () => {
    expect(readFontSize()).toBe("standard");
    applyFontSize("standard");
    expect(document.documentElement.dataset.fontSize).toBeUndefined();
    saveFontSize("xlarge");
    applyFontSize(readFontSize());
    expect(document.documentElement.dataset.fontSize).toBe("xlarge");
    saveFontSize("standard");
    applyFontSize(readFontSize());
    expect(document.documentElement.dataset.fontSize).toBeUndefined();
    localStorage.setItem("hermes-go.fontSize", "gigantic");
    expect(readFontSize()).toBe("standard");
  });
});
