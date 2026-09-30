import { gzipSync } from "node:zlib";
import { expect, it } from "vitest";
import { parseVoiceFrame, voiceAudioFrame, voiceInitialFrame } from "./voiceProtocol";
function response(json: unknown, flags = 1, compressed = false): ArrayBuffer {
  let payload = new TextEncoder().encode(JSON.stringify(json));
  if (compressed) payload = new Uint8Array(gzipSync(payload));
  const bytes = new Uint8Array(12 + payload.length); bytes.set([0x11, 0x90 | flags, 0x10 | (compressed ? 1 : 0), 0]);
  new DataView(bytes.buffer).setInt32(4, flags & 2 ? -3 : 3);
  new DataView(bytes.buffer).setUint32(8, payload.length); bytes.set(payload, 12); return bytes.buffer;
}
it("matches Android initialization and audio sequence/tail framing", () => {
  const init = voiceInitialFrame(), view = new DataView(init);
  expect([...new Uint8Array(init).slice(0, 4)]).toEqual([0x11, 0x10, 0x10, 0]);
  const json = JSON.parse(new TextDecoder().decode(init.slice(8)));
  expect(view.getUint32(4)).toBe(init.byteLength - 8);
  expect(json.audio).toEqual({ format: "pcm", codec: "raw", rate: 16000, bits: 16, channel: 1 });
  const audio = voiceAudioFrame(2, new Uint8Array([1, 2]), true);
  expect(new DataView(audio).getInt32(4)).toBe(-2);
  expect([...new Uint8Array(audio).slice(0, 4)]).toEqual([0x11, 0x23, 0, 0]);
  expect(() => voiceAudioFrame(1, new Uint8Array(0))).toThrow();
  expect(() => voiceAudioFrame(2, new Uint8Array(6402))).toThrow();
});
it("reads partial, wrapped final and full-result arrays, including gzip", async () => {
  expect(await parseVoiceFrame(response({ result: { text: "你好" } }))).toEqual({ text: "你好", finished: false, error: false });
  expect(await parseVoiceFrame(response({ payload_msg: { result: [{ text: "你" }, { text: "好" }], is_last_package: true } }, 3, true))).toEqual({ text: "你好", finished: true, error: false });
  expect((await parseVoiceFrame(response({ code: 45000000 }))).error).toBe(true);
});
it("refuses truncated, invalid and decompression-bomb provider payloads", async () => {
  const frame = response({ result: { text: "ok" } });
  await expect(parseVoiceFrame(frame.slice(0, 6))).rejects.toThrow();
  await expect(parseVoiceFrame(frame.slice(0, -1))).rejects.toThrow();
  await expect(parseVoiceFrame(response({ result: { text: "x".repeat(262145) } }, 1, true))).rejects.toThrow("too large");
  const version = new Uint8Array(frame); version[0] = 0x21;
  await expect(parseVoiceFrame(version.buffer)).rejects.toThrow();
});
