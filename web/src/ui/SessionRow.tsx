import { lastActiveMs, relativeTime } from "../app/grouping";
import { basename, projectKeyOf } from "../app/projects";
import type { RowView } from "../app/rowStatus";
import { useApp } from "../app/store";
import type { SessionListItem } from "../hermes/types";
import { Highlighted } from "./Highlighted";
import { FolderIcon, PinMark } from "./icons";

// One session row (DESIGN §5.2, Android SessionRow): title (heavier while unread), subline
// `[pin][草稿] [folder] project · model` (the default project shows no project part; inside a
// project filter the subline is `branch · model`), an optional status line, time and the trailing
// indicator. Bot rows carry no project, say 模型未知 without a model, and their status line is
// `<time> · N 条` (§5.16).

export interface SessionRowProps {
  session: SessionListItem;
  now: number;
  view?: RowView;
  pinned?: boolean;
  draft?: boolean;
  archived?: boolean;
  /** Inside a project filter: `branch · model`. */
  inProject?: boolean;
  bot?: { statusLine: string | null };
  defaultProject?: string | null;
  query?: string;
  divider?: boolean;
  onOpen: () => void;
}

export function projectLabelOf(session: Pick<SessionListItem, "git_repo_root" | "cwd">, defaultProject: string | null | undefined): string | null {
  const key = projectKeyOf(session);
  if (!key) return null;
  if (defaultProject && key === defaultProject.replace(/[/\\]+$/, "")) return null;
  return basename(key);
}

export function SessionRow({ session, now, view, pinned, draft, archived, inProject, bot, defaultProject, query = "", divider, onOpen }: SessionRowProps) {
  const { t, language } = useApp();
  const project = bot || inProject ? null : projectLabelOf(session, defaultProject);
  const model = session.model?.trim() || (bot ? t("模型未知", "Model unknown") : "");
  const lead = inProject ? session.git_branch?.trim() ?? "" : "";
  const parts = [lead, model].filter(Boolean);
  const unread = view?.unread ?? false;
  const status = bot ? null : view?.status ?? null;
  const title = session.title || session.display_name || t("未命名会话", "Untitled");
  return (
    <button type="button" class={`session-row${divider ? " with-divider" : ""}${unread ? " unread" : ""}`} onClick={onOpen}>
      <span class="row-main">
        <span class="row-title">
          <Highlighted text={title} query={query} />
        </span>
        {pinned || draft || project || parts.length || archived ? (
          <span class="row-subline mono">
            {pinned ? <PinMark label={t("已置顶", "Pinned")} /> : null}
            {draft ? <span class="row-draft">{t("草稿", "Draft")}</span> : null}
            {project ? (
              <span class="row-project">
                <FolderIcon size={12} />
                <Highlighted text={project} query={query} />
              </span>
            ) : null}
            {project && parts.length ? <span aria-hidden="true">·</span> : null}
            {parts.length ? <span class="row-model">{parts.join(" · ")}</span> : null}
            {archived ? <span class="row-tag">{t("已归档", "Archived")}</span> : null}
          </span>
        ) : null}
        {bot?.statusLine ? <span class="row-status mono">{bot.statusLine}</span> : null}
        {status ? (
          <span class={`row-status ${status.paint}`}>
            {language === "en" ? status.en : status.zh}
          </span>
        ) : null}
      </span>
      <span class="row-side">
        {bot ? null : <span class="row-time">{relativeTime(lastActiveMs(session), now, language)}</span>}
        {view?.trailing === "spinner" ? (
          <span class="spinner tiny row-indicator" aria-label={t("运行中", "Running")} />
        ) : view?.trailing === "waiting" ? (
          <span class="dot dot-warn row-indicator" aria-label={t("等待你处理", "Needs your attention")} />
        ) : view?.trailing === "unread" ? (
          <span class="dot dot-unread row-indicator" aria-label={t("未读", "Unread")} />
        ) : null}
      </span>
    </button>
  );
}
