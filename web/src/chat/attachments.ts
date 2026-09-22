// Outbound attachments: limits from android ui/chat/Attachments.kt (at most 9, 6 MB each), large
// images re-encoded like AttachmentInput.kt (longest side ≤ 2560 px, JPEG q0.88).

export const MAX_ATTACHMENTS = 9;
export const MAX_ATTACHMENT_BYTES = 6 * 1024 * 1024;
export const MAX_IMAGE_SIDE = 2560;
export const JPEG_QUALITY = 0.88;

export interface PendingAttachment {
  id: string;
  file: Blob;
  name: string;
  mimeType: string;
  kind: "image" | "file";
  /** Blob URL for an image preview chip. */
  previewUrl?: string;
}

/** Images Hermes can take as `image.attach`; everything else is a `file.attach`. */
const IMAGE_TYPES: ReadonlySet<string> = new Set(["image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif"]);

export function attachmentKind(mimeType: string): "image" | "file" {
  return IMAGE_TYPES.has(mimeType.toLowerCase()) ? "image" : "file";
}

export type AttachmentProblem = "too-many" | "too-large" | "empty" | "executable";

/** Refused outright, as on Android (AttachmentInput.kt): installers and native code. */
const EXECUTABLE_EXTENSIONS: ReadonlySet<string> = new Set(["apk", "exe", "msi", "dmg", "pkg", "app", "dex", "so", "dylib"]);

function isExecutable(name: string): boolean {
  const dot = name.lastIndexOf(".");
  return dot > 0 && EXECUTABLE_EXTENSIONS.has(name.slice(dot + 1).toLowerCase());
}

export interface AttachmentCheck {
  accepted: File[];
  rejected: Array<{ name: string; problem: AttachmentProblem }>;
}

/**
 * Which of the picked files fit: count first (existing + new ≤ 9, the rest are refused as
 * too-many), then per-file size. Images over the size cap are accepted when they will be
 * re-encoded, so only non-images are refused for size here.
 */
export function checkAttachments(existing: number, picked: readonly File[]): AttachmentCheck {
  const accepted: File[] = [];
  const rejected: AttachmentCheck["rejected"] = [];
  for (const file of picked) {
    if (existing + accepted.length >= MAX_ATTACHMENTS) {
      rejected.push({ name: file.name, problem: "too-many" });
      continue;
    }
    if (isExecutable(file.name)) {
      rejected.push({ name: file.name, problem: "executable" });
      continue;
    }
    if (file.size === 0) {
      rejected.push({ name: file.name, problem: "empty" });
      continue;
    }
    if (file.size > MAX_ATTACHMENT_BYTES && !reencodable(file.type)) {
      rejected.push({ name: file.name, problem: "too-large" });
      continue;
    }
    accepted.push(file);
  }
  return { accepted, rejected };
}

/** Static images the browser can decode and re-encode (GIF would lose its animation). */
export function reencodable(mimeType: string): boolean {
  return ["image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"].includes(mimeType.toLowerCase());
}

/** Target size for an image of w×h: unchanged when within the limit, else scaled to fit. */
export function scaledSize(width: number, height: number, maxSide: number = MAX_IMAGE_SIDE): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (longest <= maxSide || longest <= 0) return { width, height };
  const scale = maxSide / longest;
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

export function needsReencode(file: { size: number; type: string }, width: number, height: number): boolean {
  if (!reencodable(file.type)) return false;
  return Math.max(width, height) > MAX_IMAGE_SIDE || file.size > MAX_ATTACHMENT_BYTES || /hei[cf]$/i.test(file.type);
}

export function jpegName(name: string): string {
  const dot = name.lastIndexOf(".");
  return `${dot > 0 ? name.slice(0, dot) : name}.jpg`;
}

/** Browser-only: downscale/re-encode a large image to JPEG. Falls back to the original. */
export async function prepareImage(file: File): Promise<{ blob: Blob; name: string; mimeType: string }> {
  const original = { blob: file as Blob, name: file.name, mimeType: file.type };
  if (!reencodable(file.type) || typeof createImageBitmap !== "function") return original;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return original;
  }
  try {
    if (!needsReencode(file, bitmap.width, bitmap.height)) return original;
    const size = scaledSize(bitmap.width, bitmap.height);
    const canvas = document.createElement("canvas");
    canvas.width = size.width;
    canvas.height = size.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return original;
    ctx.drawImage(bitmap, 0, 0, size.width, size.height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY));
    if (!blob) return original;
    return { blob, name: jpegName(file.name), mimeType: "image/jpeg" };
  } finally {
    bitmap.close();
  }
}
