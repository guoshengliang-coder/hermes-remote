import { useEffect, useRef, useState } from "preact/hooks";
import { copyText } from "../app/clipboard";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { copyPayload, decorateBlocks, renderMarkdownFragment } from "../markdown/render";
import { renderTableImage, tableImageName } from "../markdown/tableImage";
import { saveOrShareFile } from "../app/files";
import { TableFullscreen } from "./TableFullscreen";

// Assistant Markdown. The ONLY route into the DOM is the sanitized fragment from
// renderMarkdownFragment, attached with replaceChildren — no innerHTML anywhere. While a reply
// streams, re-rendering is throttled so a long answer does not re-parse on every delta. Code
// blocks and tables carry a header with a copy button (decorateBlocks), handled here by delegation.

const STREAM_THROTTLE_MS = 90;

export function Markdown({ source, streaming }: { source: string; streaming?: boolean }) {
  const { t, flash } = useApp();
  const [fullTable, setFullTable] = useState<HTMLTableElement | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  const last = useRef<{ at: number; source: string | null; timer: ReturnType<typeof setTimeout> | null }>({ at: 0, source: null, timer: null });

  useEffect(() => {
    const state = last.current;
    const paint = () => {
      state.timer = null;
      state.at = Date.now();
      if (state.source === source || !ref.current) return;
      state.source = source;
      const fragment = renderMarkdownFragment(source);
      decorateBlocks(fragment, {
        table: t("表格", "Table"),
        copyCode: t("复制代码", "Copy code"),
        copyTable: t("复制表格", "Copy table"),
        saveTable: t("保存为图片", "Save as image"),
        fullTable: t("全屏查看", "View fullscreen"),
      });
      ref.current.replaceChildren(fragment);
    };
    if (!streaming) {
      if (state.timer) clearTimeout(state.timer);
      paint();
      return;
    }
    const wait = STREAM_THROTTLE_MS - (Date.now() - state.at);
    if (wait <= 0) paint();
    else {
      if (state.timer) clearTimeout(state.timer);
      state.timer = setTimeout(paint, wait);
    }
  }, [source, streaming]);

  useEffect(() => () => {
    if (last.current.timer) clearTimeout(last.current.timer);
  }, []);

  function onClick(event: MouseEvent) {
    const button = (event.target as Element | null)?.closest?.("button.block-copy");
    if (!button || !ref.current?.contains(button)) return;
    const action = button.getAttribute("data-action");
    const table = button.closest(".table-card")?.querySelector("table") ?? null;
    if (action === "table-full" && table) return setFullTable(table);
    if (action === "table-image" && table) return void saveTableImage(table, flash, t);
    const payload = copyPayload(button);
    if (!payload) return;
    void copyWithFeedback(payload.text, flash, payload.kind === "code" ? t("代码已复制", "Code copied") : t("表格已复制，可直接粘贴为单元格", "Table copied as cells"));
  }

  return (
    <>
      <div class="markdown" ref={ref} onClick={onClick} />
      {fullTable ? <TableFullscreen table={fullTable} onClose={() => setFullTable(null)} /> : null}
    </>
  );
}

/** Render the table to PNG and hand it to the share sheet, or download it (HR-WEB-008 on failure). */
export async function saveTableImage(table: Element, flash: (m: string | AppError) => void, t: (zh: string, en: string) => string): Promise<void> {
  let blob: Blob;
  try {
    blob = await renderTableImage(table);
  } catch (error) {
    flash(appError("HR-WEB-008", error instanceof Error ? error.message : String(error)));
    return;
  }
  const outcome = await saveOrShareFile(new File([blob], tableImageName(), { type: "image/png" }), t("表格图片", "Table image"));
  if (outcome === "downloaded") flash(t("已保存表格图片", "Table image saved"));
}

/** Copy, then confirm with `done` or show HR-WEB-007. */
export async function copyWithFeedback(text: string, flash: (message: string | AppError) => void, done: string): Promise<void> {
  try {
    await copyText(text);
    flash(done);
  } catch (error) {
    flash(isAppError(error) ? error : appError("HR-WEB-007", String(error)));
  }
}

function isAppError(value: unknown): value is AppError {
  return typeof value === "object" && value !== null && typeof (value as AppError).code === "string" && typeof (value as AppError).zh === "string";
}
