import { afterEach, expect, it } from "vitest";
import { clearConversationMemory, conversationScope, readConversationView, viewWriter } from "./conversationMemory";

afterEach(clearConversationMemory);
it("isolates view state by origin, account, Mac, profile and conversation", () => {
  const key = conversationScope("a", "mac", null, "s");
  viewWriter(key)({ query: "private", anchor: { key: "h-2", offset: -200 } });
  expect(readConversationView(key)).toMatchObject({ query: "private" });
  for (const other of [conversationScope("b", "mac", null, "s"), conversationScope("a", "other", null, "s"), conversationScope("a", "mac", "work", "s"), conversationScope("a", "mac", null, "t")]) {
    expect(readConversationView(other)).toBeUndefined();
  }
});
it("a stale cleanup cannot restore view state after sign-out", () => {
  const key = conversationScope("a", "mac", null, "s");
  const save = viewWriter(key);
  save({ query: "before" });
  clearConversationMemory();
  save({ query: "late cleanup" });
  expect(readConversationView(key)).toBeUndefined();
});
