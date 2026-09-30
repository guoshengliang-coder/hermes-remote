// Volcengine V3, matching Android DoubaoSpeechProtocol. Provider credentials never enter Web.
const LIMIT = 262_144;
export function voiceInitialFrame(): ArrayBuffer {
  const body = new TextEncoder().encode(JSON.stringify({
    audio: { format: "pcm", codec: "raw", rate: 16000, bits: 16, channel: 1 },
    request: { model_name: "bigmodel", result_type: "full", show_utterances: true, enable_nonstream: true, enable_itn: true, enable_punc: true },
  }));
  const frame = new Uint8Array(8 + body.length);
  frame.set([0x11, 0x10, 0x10, 0]);
  new DataView(frame.buffer).setUint32(4, body.length);
  frame.set(body, 8);
  return frame.buffer;
}
export function voiceAudioFrame(sequence: number, pcm: Uint8Array, last = false): ArrayBuffer {
  if (!Number.isSafeInteger(sequence) || sequence < 2 || sequence > 0x7fffffff || pcm.length > 6400 || pcm.length % 2) throw new Error("invalid audio frame");
  const frame = new Uint8Array(12 + pcm.length);
  frame.set([0x11, last ? 0x23 : 0x21, 0, 0]);
  const view = new DataView(frame.buffer);
  view.setInt32(4, last ? -sequence : sequence);
  view.setUint32(8, pcm.length);
  frame.set(pcm, 12);
  return frame.buffer;
}
export interface VoiceResult { text: string | null; finished: boolean; error: boolean }
export async function parseVoiceFrame(bytes: ArrayBuffer): Promise<VoiceResult> {
  const data = new Uint8Array(bytes), view = new DataView(bytes);
  if (data.length < 8 || (data[0]! >> 4) !== 1) throw new Error("invalid speech frame");
  const header = (data[0]! & 15) * 4, kind = data[1]! >> 4, flags = data[1]! & 15;
  if (header < 4 || header > data.length || ![9, 15].includes(kind)) throw new Error("invalid speech header");
  let offset = header + (kind === 15 ? 4 : 0) + (flags & 3 ? 4 : 0);
  if (offset + 4 > data.length) throw new Error("truncated speech frame");
  const size = view.getUint32(offset); offset += 4;
  if (size > LIMIT || offset + size !== data.length) throw new Error("invalid speech payload");
  let body = data.slice(offset);
  const compression = data[2]! & 15;
  if (compression === 1) {
    const reader = new Blob([body]).stream().pipeThrough(new DecompressionStream("gzip")).getReader();
    const chunks: Uint8Array[] = []; let length = 0;
    try {
      while (true) {
        const next = await reader.read(); if (next.done) break;
        length += next.value.length;
        if (length > LIMIT) throw new Error("speech payload too large");
        chunks.push(next.value);
      }
    } finally { await reader.cancel(); }
    body = new Uint8Array(length); let start = 0;
    for (const chunk of chunks) { body.set(chunk, start); start += chunk.length; }
  } else if (compression !== 0) throw new Error("unsupported speech compression");
  const json = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(body));
  const payload = json.payload_msg ?? json;
  const result = payload.result;
  const text = Array.isArray(result) ? result.map((r) => typeof r?.text === "string" ? r.text : "").join("") : result?.text;
  const code = json.code ?? payload.code;
  return { text: typeof text === "string" ? text : null, finished: Boolean(flags & 2) || json.is_last_package === true || payload.is_last_package === true, error: kind === 15 || (code !== undefined && code !== 0) };
}
