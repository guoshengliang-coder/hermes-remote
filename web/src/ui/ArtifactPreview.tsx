import { useEffect, useState } from "preact/hooks";
import DOMPurify from "dompurify";
import { useApp } from "../app/store";
import { toAppError } from "../app/failures";
import type { ConversationArtifact } from "../chat/artifacts";
import type { AppError } from "../errors";
import { Sheet } from "./Sheet";
import { ErrorNotice } from "./ErrorNotice";
import { FileCard, fetchFileBlob } from "./Media";
import { Markdown } from "./Markdown";

export function staticArtifactHtml(source: string): string {
  const safe = DOMPurify.sanitize(source, { WHOLE_DOCUMENT: true,
    FORBID_TAGS: ["script", "iframe", "object", "embed", "form", "meta", "base"],
    FORBID_ATTR: ["href", "action", "formaction", "srcset"] });
  return `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; base-uri 'none'; form-action 'none'">${safe}`;
}

/** HTML has no scripts, same-origin access, forms or navigation privileges. */
export function ArtifactPreview({ artifact, onClose }: { artifact: ConversationArtifact; onClose: () => void }) {
  const { client, device, t, language } = useApp();
  const [content, setContent] = useState<{ text?: string; url?: string } | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const extension = artifact.attachment.name.split(".").pop()?.toLowerCase();
  const text = ["html", "htm", "md", "txt"].includes(extension ?? "");
  const video = ["mp4", "webm", "mov"].includes(extension ?? "");
  useEffect(() => {
    if (!device || (!text && !video)) return;
    let live = true;
    let objectUrl: string | undefined;
    setContent(null); setError(null);
    fetchFileBlob(client, device.deviceId, artifact.attachment.path).then(async (blob) => {
      // Very large text stays a download, without decoding it into a second giant allocation.
      const value = text && blob.size <= 4 * 1024 * 1024 ? { text: await blob.text() }
        : video ? { url: URL.createObjectURL(blob) } : {};
      if ("url" in value) objectUrl = value.url;
      if (live) setContent(value);
      else if (objectUrl) URL.revokeObjectURL(objectUrl);
    }).catch((failure: unknown) => { if (live) setError(toAppError(failure, "download")); });
    return () => { live = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [artifact.attachment.path, device?.deviceId, attempt]);
  return <Sheet title={artifact.displayName} closeLabel={t("关闭预览", "Close preview")} onClose={onClose} className="artifact-preview" wide>
    {error ? <ErrorNotice error={error} language={language} onRetry={() => setAttempt((n) => n + 1)} /> : null}
    {(text || video) && !content && !error ? <p role="status">{t("正在打开…", "Opening…")}</p> : null}
    {content?.text !== undefined ? extension === "html" || extension === "htm" ? <>
      <p class="artifacts-note">{t("静态预览；交互内容请下载后打开。", "Static preview. Download to open interactive content.")}</p>
      <iframe title={artifact.displayName} sandbox="" srcDoc={staticArtifactHtml(content.text)} referrerPolicy="no-referrer" />
    </> : extension === "md" ? <Markdown source={content.text} /> : <pre class="artifact-text">{content.text}</pre> : null}
    {content?.url ? <video controls playsInline src={content.url} onError={() => setError(toAppError(new Error("Video preview unavailable"), "download"))} /> : null}
    {(!text && !video) || (content && content.text === undefined && !content.url) ? <p>{t("请下载后使用系统应用打开。", "Download and open with a compatible app.")}</p> : null}
    <FileCard attachment={{ ...artifact.attachment, name: artifact.displayName }} />
  </Sheet>;
}
