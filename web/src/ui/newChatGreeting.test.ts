import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-183. The new-session empty state used to be a bare greeting: a time-of-day line, a fixed
 * subline and the project pill, with no identity. Android's `NewChatGreeting.kt` puts the identity
 * avatar over the greeting and names it ("上午好，芯芯"). Web has no profile switching, so it reads
 * its own account avatar and display name instead.
 *
 * The greeting string half lives in `transcript.test.ts`; the DOM half is in `ChatPage.tsx` and
 * cannot be rendered on its own (the page needs a live ChatSession), so it is checked by reading
 * the source — the same split `jumpLatest.test.ts` uses for the footer anchor.
 */
const chatPage = readFileSync(resolve(process.cwd(), "src/ui/ChatPage.tsx"), "utf8");
describe("new-session profile choice", () => {
  it("makes the central avatar the profile selector", () => {
    expect(chatPage).toContain('class="greeting-avatar identity-picker"');
    expect(chatPage).toContain("setSelected(event.currentTarget.value)");
    expect(chatPage).toContain("preferences.label(newProfile)");
  });
  it("preserves the project greeting", () => {
    expect(chatPage).toContain('class="greeting-pill mono"');
  });
});
