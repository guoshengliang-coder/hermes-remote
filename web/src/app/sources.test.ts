import { describe, expect, it } from "vitest";
import { explicitProfile } from "./profile";
import { isBotSession, isListable } from "./sources";

describe("session sources (SessionRepository.EXCLUDED_SOURCES)", () => {
  it("drops machinery and empty scratch sessions; keeps unknown and uncounted rows", () => {
    expect(isListable({ source: "cron", message_count: 5 })).toBe(false);
    expect(isListable({ source: "subagent" })).toBe(false);
    expect(isListable({ source: "tui", message_count: 0 })).toBe(false);
    expect(isListable({ source: "hermes_remote", message_count: 2 })).toBe(true);
    expect(isListable({ source: null })).toBe(true);
    expect(isListable({ source: "dingtalk", message_count: 3 })).toBe(true);
    expect(isBotSession({ source: "dingtalk" })).toBe(true);
    expect(isBotSession({ source: "tui" })).toBe(false);
  });
});

describe("explicitProfile", () => {
  it("names only a non-default profile", () => {
    expect(explicitProfile({ profile: "work", is_default_profile: false })).toBe("work");
    expect(explicitProfile({ profile: "main", is_default_profile: true })).toBeNull();
    expect(explicitProfile({ profile: "default" })).toBeNull();
    expect(explicitProfile({ profile: " " })).toBeNull();
    expect(explicitProfile(null)).toBeNull();
  });
});
