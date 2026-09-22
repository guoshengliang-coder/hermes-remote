import { useState } from "preact/hooks";
import { useApp } from "../app/store";
import { isAllowedHref } from "../markdown/render";
import type { ChatItem, ToolItem } from "../chat/model";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon, CopyIcon } from "./icons";
import type { ViewerImage } from "./ImageViewer";
import { copyWithFeedback, Markdown } from "./Markdown";
import { FileCard, MacImage } from "./Media";

// One turn. User: bubble (surface-variant 78%, 22/22/7/22). Assistant: no bubble — images →
// text → files → tools → action row (DESIGN §5.4 / §5.21). Tool output is plain text, never HTML.
// Tapping an image opens the viewer on that message's images only (DESIGN §5.4 看图器).

function ToolRow({ tool }: { tool: ToolItem }) {
  const { t } = useApp();
  const [open, setOpen] = useState(false);
  return (
    <div class={`tool-row${open ? " open" : ""}`}>
      <button type="button" class="tool-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <ChevronIcon open={open} />
        <span class={`tool-status ${tool.done ? "done" : "running"}`}>
          {tool.done ? t("已使用", "Used") : t("正在使用", "Using")}
        </span>
        <span class="tool-name mono">{tool.name}</span>
        {!tool.done ? <span class="spinner tiny" aria-hidden="true" /> : null}
      </button>
      {open ? <pre class="tool-output mono">{tool.output.trim() || t("（无输出）", "(no output)")}</pre> : null}
    </div>
  );
}

function Reasoning({ text, streaming }: { text: string; streaming: boolean }) {
  const { t } = useApp();
  const [open, setOpen] = useState(false);
  return (
    <div class="reasoning">
      <button type="button" class="reasoning-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <ChevronIcon open={open} />
        <span>{streaming ? t("思考中…", "Thinking…") : t("思考过程", "Reasoning")}</span>
      </button>
      {open ? <p class="reasoning-text">{text}</p> : null}
    </div>
  );
}

export function MessageView({
  item,
  onRetry,
  onOpenImage,
}: {
  item: ChatItem;
  onRetry?: (item: ChatItem) => void;
  onOpenImage?: (images: ViewerImage[], index: number) => void;
}) {
  const { language, t, flash } = useApp();
  const images = item.attachments.filter((a) => a.kind === "image");
  const files = item.attachments.filter((a) => a.kind === "download");
  const blockImages = item.images.filter((img) => img.path);
  const blockLinks = item.images.filter((img) => !img.path && isAllowedHref(img.url));

  // One list per message, in display order, so a tapped image knows its place in the viewer.
  const gallery: ViewerImage[] = [
    ...(item.role === "user" ? (item.localImages ?? []).map((url): ViewerImage => ({ kind: "local", url, name: "" })) : []),
    ...images.map((a): ViewerImage => ({ kind: "mac", path: a.path, name: a.name })),
    ...blockImages.map((img): ViewerImage => ({ kind: "mac", path: img.path!, name: "" })),
  ];
  const opener = (i: number) => (onOpenImage ? () => onOpenImage(gallery, i) : undefined);
  const localCount = item.role === "user" ? (item.localImages?.length ?? 0) : 0;

  if (item.role === "user") {
    return (
      <div class="turn turn-user">
        {item.localImages?.length || images.length || blockImages.length ? (
          <div class="user-media">
            {item.localImages?.map((url, i) =>
              onOpenImage ? (
                <button type="button" class="media-open" key={url} aria-label={t("查看图片", "View image")} onClick={opener(i)}>
                  <img class="media-image" src={url} alt="" />
                </button>
              ) : (
                <img class="media-image" src={url} alt="" key={url} />
              ),
            )}
            {images.map((a, i) => <MacImage key={a.path} path={a.path} name={a.name} onOpen={opener(localCount + i)} />)}
            {blockImages.map((img, i) => <MacImage key={img.path} path={img.path!} name="" onOpen={opener(localCount + images.length + i)} />)}
          </div>
        ) : null}
        {item.localFiles?.length ? (
          <div class="user-files">
            {item.localFiles.map((name) => (
              <span class="file-chip" key={name}>{name}</span>
            ))}
          </div>
        ) : null}
        {files.map((a) => <FileCard key={a.path} attachment={a} />)}
        {item.text ? (
          <div
            class={`bubble${item.send === "failed" ? " failed" : ""}`}
            role={item.send === "failed" && onRetry ? "button" : undefined}
            tabIndex={item.send === "failed" && onRetry ? 0 : undefined}
            onClick={item.send === "failed" && onRetry && item.error?.retryable ? () => onRetry(item) : undefined}
          >
            {item.text}
          </div>
        ) : null}
        {item.send === "sending" ? <span class="send-state">{t("发送中…", "Sending…")}</span> : null}
        {item.send === "failed" && item.error ? (
          <ErrorNotice error={item.error} language={language} onRetry={onRetry ? () => onRetry(item) : undefined} variant="inline" />
        ) : null}
      </div>
    );
  }

  if (item.role === "system") {
    return item.text ? <div class="turn turn-system">{item.text}</div> : null;
  }

  return (
    <div class="turn turn-assistant">
      {item.reasoning.trim() ? <Reasoning text={item.reasoning} streaming={item.streaming && !item.text} /> : null}
      {images.length || blockImages.length ? (
        <div class="assistant-media">
          {images.map((a, i) => <MacImage key={a.path} path={a.path} name={a.name} onOpen={opener(i)} />)}
          {blockImages.map((img, i) => <MacImage key={img.path} path={img.path!} name="" onOpen={opener(images.length + i)} />)}
        </div>
      ) : null}
      {item.text ? <Markdown source={item.text} streaming={item.streaming} /> : null}
      {blockLinks.map((img) => (
        <a class="media-link" href={img.url} target="_blank" rel="noopener noreferrer" key={img.url}>
          {img.url}
        </a>
      ))}
      {files.map((a) => <FileCard key={a.path} attachment={a} />)}
      {item.tools.length ? (
        <div class="tools">
          {item.tools.map((tool) => <ToolRow key={tool.id} tool={tool} />)}
        </div>
      ) : null}
      {item.streaming && !item.text && !item.tools.length && !item.reasoning ? <span class="spinner small" aria-label={t("正在回复", "Replying")} /> : null}
      {item.interrupted ? <span class="interrupted-note">{t("已中断", "Interrupted")}</span> : null}
      {!item.streaming && item.text.trim() ? (
        <div class="message-actions">
          <button
            type="button"
            class="icon-button action-button"
            aria-label={t("复制回复", "Copy response")}
            onClick={() => void copyWithFeedback(item.text, flash, t("已复制", "Copied"))}
          >
            <CopyIcon size={18} />
          </button>
        </div>
      ) : null}
    </div>
  );
}
