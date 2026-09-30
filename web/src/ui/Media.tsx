import { useEffect, useState } from "preact/hooks";
import type { GatewayClient } from "../api/gateway";
import { hermesPaths } from "../api/gateway";
import { toAppError } from "../app/failures";
import { mediaKey, readCachedMedia, writeCachedMedia } from "../app/mediaCache";
import { useApp } from "../app/store";
import type { AppError } from "../errors";
import type { Attachment } from "../hermes/media";
import { ErrorNotice } from "./ErrorNotice";
import { BrokenImageIcon, FileIcon } from "./icons";

// Mac files referenced by messages (MEDIA:/@image:/@file:). Always fetched through the device
// files route with the session cookie, turned into blob: URLs — never a cross-origin <img src>.
//
// Three tiers (HG-108): this page's blob: URLs, then IndexedDB, then the network. The middle tier
// is what survives a reload — before it, refreshing an image-heavy conversation re-downloaded every
// image at full size. See mediaCache.ts for why the bytes go there rather than into any HTTP cache.
//
// Two sizes (HG-115): a bubble asks for THUMBNAIL_WIDTH, the fullscreen viewer asks for the
// original. They are separate cache entries — serving the preview to the viewer is how a
// thumbnail feature turns into "the picture got blurry when I opened it".

const blobUrls = new Map<string, Promise<string>>();
const MAX_CACHED = 60;

/** Matches the Connector's tier; asking for anything else just snaps back to it. */
export const THUMBNAIL_WIDTH = 1080;

export async function fetchFileBlob(
  client: GatewayClient,
  deviceId: string,
  path: string,
  thumbWidth?: number,
): Promise<Blob> {
  const response = await client.deviceApi<Response>(deviceId, "GET", hermesPaths.file(path, thumbWidth), { raw: true });
  return response.blob();
}

/** The persisted blob if there is one, otherwise the network — and then persist it. */
async function loadBlob(
  client: GatewayClient,
  deviceId: string,
  path: string,
  thumbWidth: number | undefined,
  key: string,
): Promise<Blob> {
  const cached = await readCachedMedia(key);
  if (cached) return cached;
  const blob = await fetchFileBlob(client, deviceId, path, thumbWidth);
  void writeCachedMedia(key, blob);
  return blob;
}

export function cachedBlobUrl(
  client: GatewayClient,
  deviceId: string,
  path: string,
  thumbWidth?: number,
): Promise<string> {
  const key = mediaKey(deviceId, path, thumbWidth);
  let hit = blobUrls.get(key);
  if (!hit) {
    hit = loadBlob(client, deviceId, path, thumbWidth, key).then((blob) => URL.createObjectURL(blob));
    hit.catch(() => blobUrls.delete(key));
    blobUrls.set(key, hit);
    if (blobUrls.size > MAX_CACHED) {
      const [oldestKey, oldest] = blobUrls.entries().next().value as [string, Promise<string>];
      blobUrls.delete(oldestKey);
      oldest.then((url) => URL.revokeObjectURL(url), () => undefined);
    }
  }
  return hit;
}

/**
 * The failed-image cell: the loading placeholder's own box and fill, with one centred
 * broken-image glyph. Screen readers get the same sentence Android's `contentDescription`
 * carries; nothing else is drawn (DESIGN §5.21 follows the §5.4 image failure state).
 */
function BrokenImageCell() {
  const { t } = useApp();
  return (
    <div class="media-placeholder media-failed" role="img" aria-label={t("图片加载失败", "Image unavailable")}>
      <BrokenImageIcon size={28} />
    </div>
  );
}

/**
 * An image inside a bubble, which is always the preview (HG-115). The fullscreen viewer fetches
 * the original through `cachedBlobUrl` itself, and `FileCard` below downloads the real file — this
 * component is the only place a downscaled copy is the right answer.
 */
export function MacImage({ path, name, onOpen }: { path: string; name: string; onOpen?: () => void }) {
  const { client, device, t } = useApp();
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!device) return;
    let live = true;
    setFailed(false);
    cachedBlobUrl(client, device.deviceId, path, THUMBNAIL_WIDTH).then(
      (u) => live && setUrl(u),
      () => live && setFailed(true),
    );
    return () => {
      live = false;
    };
  }, [path, device?.deviceId]);
  // A picture that will not come back keeps its cell and draws the broken-image glyph (§5.4 /
  // Android `ChatImages.kt`, HG-167). It used to stack one full-width error card per failed image,
  // so a two-image message read as "the content will not load" instead of two unavailable pictures.
  // Like Android the cell is inert and carries no prose — only the accessible label — so the reason
  // (HR-FILE-003 and its "ask Hermes to move the file" advice) is no longer printed inside a chat.
  if (failed) return <BrokenImageCell />;
  if (!url) return <div class="media-placeholder" aria-label={name} />;
  if (!onOpen) return <img class="media-image" src={url} alt={name} loading="lazy" decoding="async" />;
  return (
    <button type="button" class="media-open" aria-label={name ? t(`查看图片 ${name}`, `View image ${name}`) : t("查看图片", "View image")} onClick={onOpen}>
      <img class="media-image" src={url} alt={name} loading="lazy" decoding="async" />
    </button>
  );
}

export function FileCard({ attachment }: { attachment: Attachment }) {
  const { client, device, language, t } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  async function download() {
    if (!device || busy) return;
    setBusy(true);
    setError(null);
    try {
      const blob = await fetchFileBlob(client, device.deviceId, attachment.path);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = attachment.name;
      a.rel = "noopener";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
    } catch (e) {
      setError(toAppError(e, "download"));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div class="file-card-wrap">
      <button type="button" class="file-card" onClick={() => void download()} disabled={busy}>
        <span class="file-icon">
          <FileIcon />
        </span>
        <span class="file-text">
          <span class="file-name">{attachment.name}</span>
          <span class="file-sub">{busy ? t("正在下载…", "Downloading…") : t("点按下载", "Tap to download")}</span>
        </span>
      </button>
      {error ? <ErrorNotice error={error} language={language} onRetry={() => void download()} variant="inline" /> : null}
    </div>
  );
}
