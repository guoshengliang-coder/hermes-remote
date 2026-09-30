import { expect, it } from "vitest";
import { appendVoiceText, voiceActionAt } from "./voiceGesture";
it("uses measured circle centers and expanded edges rather than distance from the hold button", () => {
  const left = { x: 80, y: 500, radius: 32 }, right = { x: 280, y: 500, radius: 32 };
  expect(voiceActionAt(80, 500, left, right)).toBe("cancel");
  expect(voiceActionAt(280, 500, left, right)).toBe("edit");
  expect(voiceActionAt(280, 300, left, right)).toBe("edit");
  expect(voiceActionAt(323, 543, left, right)).toBe("edit");
  expect(voiceActionAt(280, 545, left, right)).toBe("send");
  expect(voiceActionAt(180, 500, left, right)).toBe("send");
  expect(voiceActionAt(80, 500, null, null)).toBe("send");
});
it("appends dictation while keeping the existing draft", () => {
  expect(appendVoiceText("已有文字 ", " 新文字 ")).toBe("已有文字\n新文字");
  expect(appendVoiceText(" ", " 新文字 ")).toBe("新文字");
  expect(appendVoiceText("已有文字", "")).toBe("已有文字");
});
