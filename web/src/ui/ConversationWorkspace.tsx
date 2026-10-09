import type { ComponentChildren } from "preact";
import { useLayoutEffect, useRef, useState } from "preact/hooks";
import { DEFAULT_LIST_REM, readLayoutMode, readListWidth, saveLayoutMode, saveListWidth, WorkspaceContext, workspaceBudget, type LayoutMode } from "../app/workspace";
import { useApp } from "../app/store";
import type { Route } from "../app/router";
import { navigate } from "../app/router";
import { SessionList } from "./SessionList";
import { PlusIcon } from "./icons";

/** Stable sibling slots: resizing never changes the chat's parent or key. */
export function ConversationWorkspace({ route, children }: { route: Route; children: ComponentChildren }) {
  const { t } = useApp();
  const root = useRef<HTMLDivElement>(null);
  const [geometry, setGeometry] = useState({ width: 0, rem: 16 });
  const [mode, updateMode] = useState(readLayoutMode);
  const [preferred, updatePreferred] = useState(readListWidth);
  const [collapsed, setCollapsed] = useState(false);
  const [listVisited, setListVisited] = useState(route.name === "list");
  const pointer = useRef<{ id: number; start: number; width: number } | null>(null);
  useLayoutEffect(() => {
    const measure = () => {
      const el = root.current;
      if (!el) return;
      const style = getComputedStyle(el);
      const width = (el.getBoundingClientRect().width || window.innerWidth) - (parseFloat(style.paddingLeft) || 0) - (parseFloat(style.paddingRight) || 0);
      const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
      setGeometry(previous => previous.width === width && previous.rem === rem ? previous : { width, rem });
    };
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(measure) : null;
    if (root.current) observer?.observe(root.current);
    const fontObserver = new MutationObserver(measure);
    fontObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-font-size", "style", "class"] });
    window.addEventListener("resize", measure);
    measure();
    return () => { observer?.disconnect(); fontObserver.disconnect(); window.removeEventListener("resize", measure); };
  }, []);
  const budget = workspaceBudget(geometry.width, geometry.rem, preferred);
  const eligible = budget.eligible && mode === "auto";
  const split = eligible && !collapsed;
  const chat = route.name === "chat" || route.name === "new";
  const showList = split || !chat;
  useLayoutEffect(() => { if (showList) setListVisited(true); }, [showList]);
  const setMode = (choice: LayoutMode) => { updateMode(choice); saveLayoutMode(choice); setCollapsed(false); };
  const setWidth = (pixels: number) => {
    const rem = Math.max(budget.minimum, Math.min(budget.maximum, pixels)) / budget.rem;
    updatePreferred(rem); saveListWidth(rem);
  };
  const resetWidth = () => { updatePreferred(DEFAULT_LIST_REM); saveListWidth(DEFAULT_LIST_REM); };
  const stopDrag = (el: HTMLElement, id: number) => {
    if (pointer.current?.id !== id) return;
    pointer.current = null;
    if (el.hasPointerCapture?.(id)) el.releasePointerCapture(id);
  };
  return <WorkspaceContext.Provider value={{ mode, setMode, eligible, split, width: geometry.width,
    listWidth: budget.list, selectedId: route.name === "chat" ? route.sessionId : null, listVisible: showList,
    toggleList: () => setCollapsed(value => !value), resetWidth }}>
    <div class="conversation-workspace" data-split={String(split)} ref={root} style={{ "--session-pane-width": `${budget.list}px` }}>
      <section class="workspace-list" id="conversation-list-pane" hidden={!showList} inert={!showList} aria-label={t("会话列表", "Conversations")}>
        {showList || listVisited ? <SessionList /> : null}
      </section>
      <div class="workspace-divider" hidden={!split} role="separator" tabIndex={split ? 0 : -1}
        aria-label={t("调整会话栏宽度", "Resize conversation list")} aria-orientation="vertical" aria-controls="conversation-list-pane"
        aria-valuemin={Math.round(budget.minimum)} aria-valuemax={Math.round(budget.maximum)} aria-valuenow={Math.round(budget.list)}
        aria-valuetext={`${Math.round(budget.list)} ${t("像素；方向键调整，Home 恢复默认", "pixels; arrow keys resize, Home resets")}`}
        onKeyDown={event => {
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
          event.preventDefault();
          if (event.key === "Home") resetWidth();
          else setWidth(event.key === "End" ? budget.maximum : budget.list + (event.key === "ArrowLeft" ? -1 : 1) * (event.shiftKey ? 32 : 16));
        }}
        onPointerDown={event => {
          if (event.button !== 0 || !event.isPrimary) return;
          pointer.current = { id: event.pointerId, start: event.clientX, width: budget.list };
          event.currentTarget.setPointerCapture?.(event.pointerId);
          event.preventDefault();
        }}
        onPointerMove={event => { const drag = pointer.current; if (drag?.id === event.pointerId) setWidth(drag.width + event.clientX - drag.start); }}
        onPointerUp={event => stopDrag(event.currentTarget, event.pointerId)}
        onPointerCancel={event => stopDrag(event.currentTarget, event.pointerId)}
        onLostPointerCapture={() => { pointer.current = null; }} onDblClick={resetWidth}>
        <span aria-hidden="true" />
      </div>
      <section class="workspace-chat" hidden={!split && !chat} inert={!split && !chat} aria-label={t("聊天", "Chat")}>
        {children || <div class="workspace-empty"><p>{t("选择一个会话，或开始新的对话", "Select a conversation or start a new chat")}</p>
          <button type="button" class="primary-button" onClick={() => navigate({ name: "new" })}><PlusIcon />{t("新会话", "New chat")}</button></div>}
      </section>
    </div>
  </WorkspaceContext.Provider>;
}
