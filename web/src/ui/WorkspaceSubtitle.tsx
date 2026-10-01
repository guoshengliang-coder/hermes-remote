import { useApp } from "../app/store";
import { BranchIcon, ChevronDownIcon, FolderIcon, HomeFolderIcon } from "./icons";

// Chat top-bar subtitle (DESIGN §5.4 / §5.21, Android `WorkspaceSubtitle.kt`):
// `[folder] project · [branch] branch ▾`. The default project has no folder name of its own, so it
// shows the house-folder + 「默认项目」 instead of the launch directory's basename — the same rule
// Android applies. The row is also the way to move the chat: muted, without the chevron, while a
// run is live (Hermes refuses a move then, 4009).

export interface WorkspaceSubtitleProps {
  /** The project folder's basename, or `null` when the chat runs in the default project. */
  projectLabel: string | null;
  /** The live git branch, or `null` when unknown. */
  branch: string | null;
  /** Moving is available (the workspace-move feature is on and the session is durable). */
  canMove: boolean;
  /** No turn is running, so the move is offered rather than refused. */
  enabled: boolean;
  onClick: () => void;
}

export function WorkspaceSubtitle({ projectLabel, branch, canMove, enabled, onClick }: WorkspaceSubtitleProps) {
  const { t } = useApp();
  const isDefault = projectLabel === null;
  return (
    <button
      type="button"
      class={`chat-workspace mono${canMove ? " movable" : ""}`}
      disabled={!canMove || !enabled}
      aria-label={canMove ? t("所属项目，点按移动", "Project — tap to move") : undefined}
      onClick={onClick}
    >
      {isDefault ? <HomeFolderIcon size={12} /> : <FolderIcon size={12} />}
      <span class="chat-workspace-name">{projectLabel ?? t("默认项目", "Default project")}</span>
      {branch ? (
        <>
          <span aria-hidden="true">·</span>
          <BranchIcon size={12} />
          <span class="chat-workspace-name">{branch}</span>
        </>
      ) : null}
      {canMove && enabled ? <ChevronDownIcon size={12} /> : null}
    </button>
  );
}
