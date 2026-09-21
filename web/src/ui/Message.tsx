import { useState } from "preact/hooks";
import { useApp } from "../app/store";
import { isAllowedHref } from "../markdown/render";
import type { ChatItem, ToolItem } from "../chat/model";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon } from "./icons";
import { Markdown } from "./Markdown";
import { FileCard, MacImage } from "./Media";

// One turn. User: bubble (surface-variant 78%, 22/22/7/22). Assistant: no bubble — images →
// text → files → tools (DESIGN §5.4 / §5.21). Tool output is plain text, never HTML.

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

export function MessageView({ item, onRetry }: { item: ChatItem; onRetry?: (item: ChatItem) => void }) {
  const { language, t } = useApp();
  const images = item.attachments.filter((a) => a.kind === "image");
  const files = item.attachments.filter((a) => a.kind === "download");
  const blockImages = item.images.filter((img) => img.path);
  const blockLinks = item.images.filter((img) => !img.path && isAllowedHref(img.url));

  if (item.role === "user") {
    return (
      <div class="turn turn-user">
        {item.localImages?.length || images.length || blockImages.length ? (
          <div class="user-media">
            {item.localImages?.map((url) => <img class="media-image" src={url} alt="" key={url} />)}
            {images.map((a) => <MacImage key={a.path} path={a.path} name={a.name} />)}
            {blockImages.map((img) => <MacImage key={img.path} path={img.path!} name="" />)}
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
          {images.map((a) => <MacImage key={a.path} path={a.path} name={a.name} />)}
          {blockImages.map((img) => <MacImage key={img.path} path={img.path!} name="" />)}
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
    </div>
  );
}
