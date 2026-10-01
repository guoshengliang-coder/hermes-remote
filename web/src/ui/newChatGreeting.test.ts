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
const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8");

function block(selector: string): string {
  const start = css.indexOf(`\n${selector} {`);
  if (start < 0) return "";
  const open = css.indexOf("{", start);
  const end = css.indexOf("}", open);
  return css.slice(open + 1, end).replace(/\s+/g, " ").trim();
}

describe("the new-session greeting carries the account identity (HG-183)", () => {
  it("puts the account avatar above the greeting, before the title", () => {
    const greeting = chatPage.indexOf('class="greeting"');
    const avatar = chatPage.indexOf('class="greeting-avatar"');
    const title = chatPage.indexOf('class="greeting-title"');
    expect(greeting).toBeGreaterThan(-1);
    expect(avatar).toBeGreaterThan(greeting);
    expect(avatar).toBeLessThan(title);
    expect(chatPage.slice(avatar, title)).toContain("AccountAvatar");
  });

  it("joins the signed-in Web account name to the greeting", () => {
    expect(chatPage).toContain("greetingForHour(new Date().getHours(), language, accountName)");
    // The name comes from the Web account, not a leftover profile identity.
    expect(chatPage).toContain("app.account?.displayName");
  });

  it("keeps the rest of the empty state, including the project pill", () => {
    expect(chatPage).toContain('class="greeting-pill mono"');
  });

  it("sizes the avatar for the greeting rather than reusing the 44px card size", () => {
    expect(block(".greeting-avatar .account-avatar")).toContain("width: 4rem");
  });
});
