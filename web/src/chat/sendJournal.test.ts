import "fake-indexeddb/auto";
import { beforeEach, expect, it } from "vitest";
import { clearSendJournal, finishSend, listSends, restoreSendAttachments, stageSend, updateSend } from "./sendJournal";

const owner = { account: "a", device: "mac", profile: "work" };
const turn = { ...owner, id: "prompt", sessionId: null, cwd: "/work", text: "read this", createdAt: 1 };

beforeEach(clearSendJournal);

it("persists the complete turn and moves it atomically to the confirmed conversation", async () => {
  await stageSend(turn, [{ id: "attachment", name: "note.txt", mimeType: "text/plain", kind: "file", file: new Blob(["notes"]) }]);
  const [pending] = await listSends(owner, null);
  expect(pending).toMatchObject({ text: "read this", cwd: "/work", phase: "prepared" });
  expect(await restoreSendAttachments(pending!)[0]!.file.text()).toBe("notes");
  expect(await listSends({ ...owner, account: "other" }, null)).toEqual([]);
  expect(await listSends({ ...owner, device: "other" }, null)).toEqual([]);
  expect(await listSends({ ...owner, profile: "other" }, null)).toEqual([]);
  expect(await updateSend(turn.id, ["prepared"], { sessionId: "server" })).toBe(true);
  expect(await listSends(owner, null)).toEqual([]);
  expect(await listSends(owner, "server")).toHaveLength(1);
  await finishSend(turn.id);
  expect(await listSends(owner, "server")).toEqual([]);
});

it("only lets one claimant transition a recoverable turn to the same next phase", async () => {
  await stageSend(turn, []);
  const claims = await Promise.all([
    updateSend(turn.id, ["prepared"], { phase: "creating" }),
    updateSend(turn.id, ["prepared"], { phase: "creating" }),
  ]);
  expect(claims.sort()).toEqual([false, true]);
  expect(await updateSend(turn.id, ["prepared", "failed"], { phase: "submitting" })).toBe(false);
});

it("clears private payloads and blocks a late phase update", async () => {
  await stageSend(turn, []);
  await clearSendJournal();
  expect(await listSends(owner, null)).toEqual([]);
  expect(await updateSend(turn.id, ["prepared"], { phase: "submitting" })).toBe(false);
});
