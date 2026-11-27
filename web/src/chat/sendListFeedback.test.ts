import { expect, it } from "vitest";
import { mergeSendFeedback } from "./sendListFeedback";

const send = { id: "local", account: "account", device: "mac", profile: "work", sessionId: null,
  cwd: "/project", text: "draft title", createdAt: 1_720_000_010_000 };

it("shows a pending new conversation, keeps it through acknowledgement and adopts the server row", () => {
  expect(mergeSendFeedback([], [send]).sessions[0]).toMatchObject({ id: "local", title: "draft title" });
  const accepted = { ...send, sessionId: "real", accepted: true };
  expect(mergeSendFeedback([], [accepted]).sessions[0]).toMatchObject({ id: "real" });
  const server = { id: "real", title: "formal title", profile: "work", last_active: send.createdAt / 1000 };
  const settled = mergeSendFeedback([server], [accepted]);
  expect(settled.sessions).toEqual([server]);
  expect(settled.caughtUp.has(send.id)).toBe(true);
});

it("overlays existing activity in place without merging the same ID from another role", () => {
  const rows = [{ id: "real", title: "other role", profile: "default" }, { id: "real", title: "original", profile: "work" }];
  const merged = mergeSendFeedback(rows, [{ ...send, sessionId: "real" }]);
  expect(merged.sessions).toHaveLength(2);
  expect(merged.sessions[0]).toBe(rows[0]);
  expect(merged.sessions[1]).toMatchObject({ title: "original", last_active: send.createdAt / 1000 });
  expect(merged.byRow.has(merged.sessions[1]!)).toBe(true);
});

it("retains local activity if a stale server row comes from the same second", () => {
  const started = send.createdAt + 900;
  const entry = { ...send, sessionId: "real", createdAt: started, accepted: true, baselineCount: 5 };
  const stale = { id: "real", profile: "work", last_active: send.createdAt / 1000, message_count: 5 };
  expect(mergeSendFeedback([stale], [entry]).sessions[0]?.last_active).toBe(started / 1000);
  expect(mergeSendFeedback([{ ...stale, message_count: 6 }], [entry]).caughtUp.has(send.id)).toBe(true);
});
