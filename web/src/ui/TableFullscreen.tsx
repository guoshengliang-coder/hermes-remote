import { useEffect, useRef } from "preact/hooks";
import { useApp } from "../app/store";
import { CloseIcon, DownloadIcon } from "./icons";
import { saveTableImage } from "./Markdown";

// A table card viewed fullscreen (Android TableFullscreenDialog): the already-sanitized table node
// is cloned into a scrollable full-screen surface — never re-parsed — with close and save.

export function TableFullscreen({ table, onClose }: { table: HTMLTableElement; onClose: () => void }) {
  const { t, flash } = useApp();
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    host.current?.replaceChildren(table.cloneNode(true));
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [table]);
  return (
    <div class="table-fullscreen" role="dialog" aria-modal="true" aria-label={t("全屏查看表格", "Table, fullscreen")}>
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("关闭", "Close")} onClick={onClose}>
            <CloseIcon />
          </button>
          <h1 class="topbar-title left">{t("表格", "Table")}</h1>
          <button type="button" class="icon-button" aria-label={t("保存为图片", "Save as image")} onClick={() => void saveTableImage(table, flash, t)}>
            <DownloadIcon />
          </button>
        </div>
      </header>
      <div class="table-fullscreen-body markdown" ref={host} />
    </div>
  );
}
