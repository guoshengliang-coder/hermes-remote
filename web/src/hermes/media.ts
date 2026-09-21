// Attachment grammars in message text (docs/HERMES_CONTRACT.md §4): the canonical outbound
// `MEDIA:/absolute/path.ext` plus the client-side `@image:` / `@file:` directive lines.
// Port of android domain/Mappers.kt (MEDIA_TAG, extractExplicitMediaReferences,
// maskProtectedMediaExamples, IMAGE_DIRECTIVE/FILE_DIRECTIVE). The natural-language "文件已保存到"
// heuristics and Markdown-link cards are not ported.

/** Hand-copy of upstream `gateway/platforms/base.py` MEDIA_DELIVERY_EXTS (contract §5). */
export const MEDIA_DELIVERY_EXTENSIONS: readonly string[] = [
  "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg",
  "mp4", "mov", "avi", "mkv", "webm", "3gp",
  "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac",
  "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
  "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
  "kmz", "kml", "geojson", "gpx",
  "pptx", "ppt", "odp", "key",
  "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa",
  "html", "htm",
];

export type AttachmentKind = "image" | "download";

export interface Attachment {
  kind: AttachmentKind;
  /** Absolute path on the Mac. */
  path: string;
  name: string;
  /** Set for inline-capable images (png/jpeg/gif/webp) and known download types. */
  mimeType: string | null;
  source: "media" | "image-directive" | "file-directive";
}

export interface ParsedAttachments {
  /** Visible text with the tags/directives removed. */
  text: string;
  attachments: Attachment[];
}

const escapeRegex = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const EXT = [...MEDIA_DELIVERY_EXTENSIONS].sort((a, b) => b.length - a.length).map(escapeRegex).join("|");

// Java's \s and \S are ASCII-only (no UNICODE_CHARACTER_CLASS); spell them out so an ideographic
// space behaves exactly as on Android.
const WS = "[ \\t\\n\\x0B\\f\\r]";
const NWS = "[^ \\t\\n\\x0B\\f\\r]";
const HS = "[ \\t\\x0B\\f]"; // Kotlin [^\S\r\n]

const MEDIA_TAG_SOURCE =
  "[`\"'*_]{0,3}MEDIA:" + WS + "*(?:" +
  "`((?:file://)?/[^`\\r\\n]+?\\.(?:" + EXT + "))`" +
  "|\"((?:file://)?/[^\"\\r\\n]+?\\.(?:" + EXT + "))\"" +
  "|'((?:file://)?/[^'\\r\\n]+?\\.(?:" + EXT + "))'" +
  "|((?:file://)?/" + NWS + "+?(?:" + HS + "+" + NWS + "+?)*?\\.(?:" + EXT + "))" +
  ")(?=" + WS + "|[`\"'*_,;:)\\]}\\[（）〈〉《》：，。；！？、“”‘’【】]|MEDIA:|\\.(?:" + WS + "|$)|$)[`\"'*_]{0,3}\\.?";

const mediaTag = () => new RegExp(MEDIA_TAG_SOURCE, "gi");
const MEDIA_GLOBAL_DIRECTIVE = /\[\[(?:as_document|audio_as_voice)]]/gi;
const FILE_SIZE_AT_START = new RegExp(
  "^" + WS + "*[（(]" + WS + "*([0-9]+(?:\\.[0-9]+)?)" + WS + "*(B|KB|MB|GB|KIB|MIB|GIB)" + WS + "*[）)]",
  "i",
);
// Java `\s` includes newlines, so `^\s*` / `\s*$` may span a blank line exactly as on Android.
const directive = (name: string) =>
  new RegExp("^" + WS + "*@" + name + ":(?:\"([^\"]+)\"|'([^']+)'|`([^`]+)`|(.+?))" + WS + "*$", "gm");
const IMAGE_DIRECTIVE = directive("image");
const FILE_DIRECTIVE = directive("file");
const ATTACHED_IMAGE_PLACEHOLDER = new RegExp("^" + WS + "*\\[User attached image:[^\\]]+]" + WS + "*$", "gim");
const ATTACHED_FILE_PLACEHOLDER = new RegExp("^" + WS + "*\\[User attached (?:file|PDF):[^\\]]+]" + WS + "*$", "gim");
const IMAGE_PATH_EXTENSION = /\.(?:png|jpe?g|gif|webp)(?=$|[\s`"'<>，。；;）)\]])/i;

const INLINE_IMAGE_MIME: Record<string, string> = {
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  gif: "image/gif",
  webp: "image/webp",
};

const DOWNLOAD_MIME: Record<string, string> = {
  pdf: "application/pdf",
  txt: "text/plain",
  log: "text/plain",
  md: "text/markdown",
  json: "application/json",
  csv: "text/csv",
  doc: "application/msword",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xls: "application/vnd.ms-excel",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  ppt: "application/vnd.ms-powerpoint",
  pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  odt: "application/vnd.oasis.opendocument.text",
  ods: "application/vnd.oasis.opendocument.spreadsheet",
  odp: "application/vnd.oasis.opendocument.presentation",
  mp3: "audio/mpeg",
  wav: "audio/wav",
  m4a: "audio/mp4",
  mp4: "video/mp4",
  mov: "video/quicktime",
  webm: "video/webm",
  mkv: "video/x-matroska",
  zip: "application/zip",
  gz: "application/gzip",
  tgz: "application/gzip",
  "7z": "application/x-7z-compressed",
  rar: "application/vnd.rar",
  apk: "application/vnd.android.package-archive",
};

function extensionOf(path: string): string {
  const dot = path.lastIndexOf(".");
  return dot < 0 ? "" : path.slice(dot + 1).toLowerCase();
}

/** Inline-capable image MIME (png/jpeg/gif/webp) or null. Everything else is a download. */
export function inlineImageMime(path: string): string | null {
  return INLINE_IMAGE_MIME[extensionOf(path)] ?? null;
}

export function fileNameOf(path: string): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  return name.slice(name.lastIndexOf("\\") + 1) || "attachment";
}

export function classifyAttachment(path: string, source: Attachment["source"] = "media"): Attachment {
  const image = inlineImageMime(path);
  const name = fileNameOf(path);
  return image
    ? { kind: "image", path, name, mimeType: image, source }
    : { kind: "download", path, name, mimeType: DOWNLOAD_MIME[extensionOf(name)] ?? null, source };
}

/**
 * Java `new URI(ref.replace(" ", "%20")).getPath()`: decode %XX, drop scheme/authority/query/
 * fragment; null where Java would throw (illegal characters, bad escapes) or have no path.
 */
function uriPath(reference: string): string | null {
  let rest = reference.replace(/ /g, "%20");
  if (/[\s"<>\\^`{|}]/.test(rest)) return null;
  const scheme = /^[A-Za-z][A-Za-z0-9+.-]*:/.exec(rest);
  if (scheme) {
    rest = rest.slice(scheme[0].length);
    if (!rest.startsWith("/")) return null; // opaque URI: no path
  }
  if (rest.startsWith("//")) {
    const end = rest.slice(2).search(/[/?#]/);
    rest = end < 0 ? "" : rest.slice(2 + end);
  }
  rest = rest.replace(/[?#].*$/s, "");
  if (/[[\]]/.test(rest)) return null;
  try {
    return decodeURIComponent(rest);
  } catch {
    return null;
  }
}

function normalizeExplicitMediaPath(raw: string): string {
  const reference = raw.trim().replace(/^[<>]+|[<>]+$/g, "");
  const path = uriPath(reference);
  return path && path.trim() ? path : reference.replace(/^file:\/\//, "");
}

function normalizeLocalImagePath(raw: string): string | null {
  const unwrapped = raw.trim().replace(/^[`"'<]+/, "");
  const ext = IMAGE_PATH_EXTENSION.exec(unwrapped);
  if (!ext) return null;
  const reference = unwrapped.slice(0, ext.index + ext[0].length);
  const uri = uriPath(reference);
  const path = uri && uri.trim() ? uri : reference.replace(/^file:\/\//, "");
  return path.startsWith("/") && inlineImageMime(path) ? path : null;
}

/** Offset-preserving mask: fenced code and blockquote lines cannot carry a real MEDIA tag. */
function maskProtectedMediaExamples(raw: string): string {
  const chars = raw.split(""); // UTF-16 units, so offsets match the original string
  const mask = (start: number, end: number) => {
    for (let i = start; i < end; i++) if (chars[i] !== "\n") chars[i] = " ";
  };
  for (const m of raw.matchAll(/```[^\n]*\n[\s\S]*?```/g)) mask(m.index, m.index + m[0].length);
  for (const m of raw.matchAll(new RegExp("^" + WS + "*>.*$", "gm"))) mask(m.index, m.index + m[0].length);
  return chars.join("");
}

function firstGroup(m: RegExpMatchArray): string | undefined {
  return m.slice(1).find((g) => g !== undefined && g.trim() !== "");
}

/** `MEDIA:` tags → attachments, removed from the text along with a trailing `（7.3 KB）`. */
export function extractMediaTags(raw: string): ParsedAttachments {
  if (!/MEDIA:/i.test(raw)) return { text: raw.replace(MEDIA_GLOBAL_DIRECTIVE, ""), attachments: [] };
  const masked = maskProtectedMediaExamples(raw);
  const matches = [...masked.matchAll(mediaTag())];
  if (!matches.length) return { text: raw.replace(MEDIA_GLOBAL_DIRECTIVE, ""), attachments: [] };

  const paths: string[] = [];
  const ranges: Array<[number, number]> = [];
  for (const m of matches) {
    const rawPath = firstGroup(m);
    if (!rawPath) continue;
    const path = normalizeExplicitMediaPath(rawPath);
    if (!path.startsWith("/") || !path.slice(path.lastIndexOf("/") + 1).trim()) continue;
    paths.push(path);
    let end = m.index + m[0].length;
    const size = FILE_SIZE_AT_START.exec(raw.slice(end));
    if (size) end += size[0].length;
    ranges.push([m.index, end]);
  }
  let cleaned = "";
  let cursor = 0;
  for (const [start, end] of ranges.sort((a, b) => a[0] - b[0])) {
    if (start > cursor) cleaned += raw.slice(cursor, start);
    cursor = Math.max(cursor, end);
  }
  if (cursor < raw.length) cleaned += raw.slice(cursor);
  cleaned = cleaned.replace(MEDIA_GLOBAL_DIRECTIVE, "").replace(/\n{3,}/g, "\n\n").trim();
  return { text: cleaned, attachments: [...new Set(paths)].map((p) => classifyAttachment(p, "media")) };
}

/**
 * Full pass used for display: MEDIA tags first, then `@image:` / `@file:` lines and their
 * `[User attached …]` placeholders. Leading/trailing blank lines are dropped.
 */
export function parseAttachments(raw: string): ParsedAttachments {
  const media = extractMediaTags(raw);
  const text = media.text;
  const images: Attachment[] = [];
  for (const m of text.matchAll(IMAGE_DIRECTIVE)) {
    const path = normalizeLocalImagePath(firstGroup(m) ?? "");
    if (path) images.push(classifyAttachment(path, "image-directive"));
  }
  const files: Attachment[] = [];
  for (const m of text.matchAll(FILE_DIRECTIVE)) {
    const path = firstGroup(m) ?? "";
    // `@file:` is an explicit delivery instruction and is taken at its word: always a download.
    const name = fileNameOf(path);
    files.push({ kind: "download", path, name, mimeType: DOWNLOAD_MIME[extensionOf(name)] ?? null, source: "file-directive" });
  }
  const visible = text
    .replace(IMAGE_DIRECTIVE, "")
    .replace(ATTACHED_IMAGE_PLACEHOLDER, "")
    .replace(FILE_DIRECTIVE, "")
    .replace(ATTACHED_FILE_PLACEHOLDER, "");
  const lines = visible.split("\n");
  while (lines.length && lines[0]!.trim() === "") lines.shift();
  while (lines.length && lines[lines.length - 1]!.trim() === "") lines.pop();

  const seen = new Set<string>();
  const attachments = [...media.attachments.filter((a) => a.kind === "image"), ...images, ...media.attachments.filter((a) => a.kind === "download"), ...files]
    .filter((a) => {
      const key = `${a.kind}:${a.path}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  return { text: lines.join("\n"), attachments };
}
