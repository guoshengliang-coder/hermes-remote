import { useEffect, useState } from "preact/hooks";
import type { GatewayClient } from "../api/gateway";
import { hermesPaths } from "../api/gateway";
import { toAppError } from "../app/failures";
import { useApp } from "../app/store";
import type { AppError } from "../errors";
import type { Attachment } from "../hermes/media";
import { ErrorNotice } from "./ErrorNotice";
import { FileIcon } from "./icons";

// Mac files referenced by messages (MEDIA:/@image:/@file:). Always fetched through the device
// files route with the session cookie, turned into blob: URLs — never a cross-origin <img src>.

const blobUrls = new Map<string, Promise<string>>();
const MAX_CACHED = 60;

export async function fetchFileBlob(client: GatewayClient, deviceId: string, path: string): Promise<Blob> {
  const response = await client.deviceApi<Response>(deviceId, "GET", hermesPaths.file(path), { raw: true });
  return response.blob();
}

export function cachedBlobUrl(client: GatewayClient, deviceId: string, path: string): Promise<string> {
  const key = `${deviceId}\n${path}`;
  let hit = blobUrls.get(key);
  if (!hit) {
    hit = fetchFileBlob(client, deviceId, path).then((blob) => URL.createObjectURL(blob));
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

export function MacImage({ path, name, onOpen }: { path: string; name: string; onOpen?: () => void }) {
  const { client, device, language, t } = useApp();
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!device) return;
    let live = true;
    setError(null);
    cachedBlobUrl(client, device.deviceId, path).then(
      (u) => live && setUrl(u),
      (e: unknown) => live && setError(toAppError(e, "download")),
    );
    return () => {
      live = false;
    };
  }, [path, device?.deviceId, attempt]);
  if (error) return <ErrorNotice error={error} language={language} onRetry={() => setAttempt(attempt + 1)} variant="inline" />;
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
