import type { ChatItem } from "./model";
import markdownIt from "markdown-it";
import { classifyAttachment, MEDIA_DELIVERY_EXTENSIONS, type Attachment } from "../hermes/media";

const markdown = markdownIt({ html: false });
markdown.validateLink = () => true; // Parse local file: links; they are never browser navigation.
// Reducer items are immutable. Avoid reparsing every old answer on each live stream update.
const linksByItem = new WeakMap<ChatItem, Attachment[]>();
function linksOf(item: ChatItem): Attachment[] {
  let links = linksByItem.get(item);
  if (!links) { links = localLinks(item.text); linksByItem.set(item, links); }
  return links;
}
function localLinks(text: string): Attachment[] {
  const out: Attachment[] = [];
  for (const token of markdown.parse(text, {})) for (const child of token.children ?? []) {
    const href = child.type === "link_open" ? child.attrGet("href") : child.type === "image" ? child.attrGet("src") : null;
    if (typeof href !== "string" || !href) continue;
    let path: string;
    try { path = decodeURIComponent(href.replace(/^file:\/\//, "")); } catch { continue; }
    if (!path.startsWith("/") || path.startsWith("//") || !MEDIA_DELIVERY_EXTENSIONS.includes(path.split(".").pop()?.toLowerCase() ?? "")) continue;
    out.push(classifyAttachment(path));
  }
  return out;
}

export interface ConversationArtifact {
  attachment: Attachment;
  messageKey: string;
  timestampMs: number | null;
  displayName: string;
}

/** Whole-conversation input; only explicit, settled assistant deliveries are results. */
export function conversationArtifacts(items: readonly ChatItem[]): ConversationArtifact[] {
  const uploads = new Set(items.filter((i) => i.role === "user").flatMap((i) =>
    [...i.attachments.map((a) => a.path), ...linksOf(i).map((a) => a.path),
      ...i.images.flatMap((image) => image.path ? [image.path] : [])]));
  const byPath = new Map<string, ConversationArtifact>();
  for (const item of items) {
    if (item.role !== "assistant" || item.note || item.streaming) continue;
    const direct = [...(item.deliveredAttachments ?? item.attachments), ...linksOf(item),
      ...item.images.flatMap((image) => image.path ? [classifyAttachment(image.path)] : [])];
    for (const attachment of direct) {
      if (uploads.has(attachment.path)) continue;
      // One current source per path. A later delivery replaces its origin; no invented versions.
      byPath.delete(attachment.path);
      byPath.set(attachment.path, { attachment, messageKey: item.key, timestampMs: item.timestampMs, displayName: attachment.name });
    }
  }
  const artifacts = [...byPath.values()].reverse();
  const reserved = new Set(artifacts.map((a) => a.attachment.name));
  const used = new Set<string>();
  for (const artifact of artifacts) {
    const name = artifact.attachment.name;
    if (!used.has(name)) { used.add(name); continue; }
    const dot = name.lastIndexOf(".");
    const stem = dot > 0 ? name.slice(0, dot) : name;
    const extension = dot > 0 ? name.slice(dot) : "";
    let n = 2;
    let unique: string;
    do { unique = `${stem} (${n++})${extension}`; } while (reserved.has(unique) || used.has(unique));
    artifact.displayName = unique;
    used.add(unique);
  }
  return artifacts;
}
