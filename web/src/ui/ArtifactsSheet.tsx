import { useLayoutEffect, useRef } from "preact/hooks";
import type { ConversationArtifact } from "../chat/artifacts";
import { useApp } from "../app/store";
import type { AppError } from "../errors";
import { formatTimeSeparator } from "../chat/transcript";
import { ErrorNotice } from "./ErrorNotice";
import { FileCard, MacImage } from "./Media";
import { Sheet } from "./Sheet";

export function ArtifactsSheet({ artifacts, loading, error, scrollTop, onScroll, onRetry, onOpen, onContext, onClose }: {
  artifacts: ConversationArtifact[]; loading: boolean; error: AppError | null;
  scrollTop: number; onScroll: (top: number) => void; onRetry: () => void;
  onOpen: (artifact: ConversationArtifact) => void; onContext: (artifact: ConversationArtifact) => void; onClose: () => void;
}) {
  const { t, language } = useApp();
  const list = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => { if (list.current) list.current.scrollTop = scrollTop; }, []);
  return <Sheet title={t("产物", "Artifacts")} closeLabel={t("关闭", "Close")} onClose={onClose} className="artifacts-sheet">
    <div class="artifacts-list" ref={list} onScroll={() => onScroll(list.current?.scrollTop ?? 0)}>
      <p class="artifacts-note">{t("显示文件当前内容，同一路径再次交付可能更新内容。", "Files show their current content; delivering the same path again may update it.")}</p>
      {loading ? <p role="status">{t("正在整理整个会话的产物…", "Collecting artifacts from the entire conversation…")}</p> : null}
      {error ? <ErrorNotice error={error} language={language} onRetry={onRetry} /> : null}
      {!loading && !error && !artifacts.length ? <p>{t("暂无产物", "No artifacts yet")}</p> : null}
      {artifacts.map((artifact) => <article class="artifact-entry" key={artifact.attachment.path}>
        {artifact.attachment.kind === "image" ? <>
          <MacImage path={artifact.attachment.path} name={artifact.displayName} onOpen={() => onOpen(artifact)} />
          <div class="artifact-name">{artifact.displayName}</div>
        </> : <FileCard attachment={{ ...artifact.attachment, name: artifact.displayName }} onOpen={() => onOpen(artifact)} />}
        <div class="artifact-details">
          <span>{artifact.attachment.name.split(".").pop()?.toUpperCase() ?? t("文件", "File")}{artifact.timestampMs ? ` · ${formatTimeSeparator(artifact.timestampMs, language)}` : ""}</span>
          <button type="button" class="text-button" onClick={() => onContext(artifact)}>{t("查看上下文", "View context")}</button>
        </div>
      </article>)}
    </div>
  </Sheet>;
}
