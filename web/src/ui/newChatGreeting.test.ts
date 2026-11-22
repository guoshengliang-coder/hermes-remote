import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const chatPage = readFileSync(resolve(process.cwd(), "src/ui/ChatPage.tsx"), "utf8");
describe("new-session profile choice", () => {
  it("opens an explicit role sheet below the welcome avatar", () => {
    expect(chatPage).toContain('class="role-switch"');
    expect(chatPage).toContain("setSelected(name)");
    expect(chatPage).toContain("preferences.label(newProfile)");
  });
  it("preserves the project greeting", () => {
    expect(chatPage).toContain('class="greeting-pill mono"');
  });
});
