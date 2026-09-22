import { beforeEach, describe, expect, it } from "vitest";
import { clearAllDrafts, draftKey, draftSessions, hasDraft, loadDraft, saveDraft } from "./drafts";

describe("drafts (Android DraftStore, owner decision 2026-09-22)", () => {
  beforeEach(() => localStorage.clear());

  it("keeps text per Mac and conversation; blank text removes it", () => {
    saveDraft(draftKey("mac", "s1"), "half a thought");
    saveDraft(draftKey("mac", null), "new chat text");
    expect(loadDraft(draftKey("mac", "s1"))).toBe("half a thought");
    expect(loadDraft(draftKey("other", "s1"))).toBe("");
    expect([...draftSessions("mac")]).toEqual(["s1"]);
    saveDraft(draftKey("mac", "s1"), "  ");
    expect(hasDraft(draftKey("mac", "s1"))).toBe(false);
  });

  it("caps each draft at 8,000 characters and keeps the 50 newest", () => {
    saveDraft(draftKey("mac", "long"), "x".repeat(9000));
    expect(loadDraft(draftKey("mac", "long"))).toHaveLength(8000);
    for (let i = 0; i < 55; i++) saveDraft(draftKey("mac", `s${i}`), "t", 1000 + i);
    expect(loadDraft(draftKey("mac", "s0"))).toBe("");
    expect(loadDraft(draftKey("mac", "s54"))).toBe("t");
  });

  it("sign-out clears every draft", () => {
    saveDraft(draftKey("mac", "s1"), "a");
    clearAllDrafts();
    expect(localStorage.getItem("hermes-go.drafts")).toBeNull();
  });
});
