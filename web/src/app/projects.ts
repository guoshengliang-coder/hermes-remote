import { lastActiveMs } from "./grouping";
import type { SessionListItem } from "../hermes/types";

// Projects derived from the session list (Android ProjectDerivation.kt): a session belongs to its
// git repo root, else its working directory. The Web app cannot reach Hermes' project tree (the
// browser allowlist has no projects.* route), so this is the read-only derived list Android also
// shows for non-default profiles: no rename, no icons, no moving sessions between projects.

export interface DerivedProject {
  /** The folder path; `null` for sessions that carry neither a repo root nor a cwd. */
  path: string | null;
  label: string;
  count: number;
  lastActiveMs: number;
}

function trimPath(path: string | null | undefined): string | null {
  const trimmed = path?.trim().replace(/[/\\]+$/, "");
  return trimmed ? trimmed : null;
}

/** The path a session is grouped by: the resolved git repo root, else its working directory. */
export function projectKeyOf(session: Pick<SessionListItem, "git_repo_root" | "cwd">): string | null {
  return trimPath(session.git_repo_root) ?? trimPath(session.cwd);
}

export function basename(path: string): string {
  return path.split(/[/\\]/).pop() || path;
}

/** Projects with at least one live (non-archived) session, most recently active first. */
export function deriveProjects(sessions: readonly SessionListItem[]): DerivedProject[] {
  const byKey = new Map<string | null, DerivedProject>();
  for (const session of sessions) {
    if (session.archived) continue;
    const path = projectKeyOf(session);
    const entry = byKey.get(path) ?? { path, label: path ? basename(path) : "", count: 0, lastActiveMs: 0 };
    entry.count += 1;
    entry.lastActiveMs = Math.max(entry.lastActiveMs, lastActiveMs(session) ?? 0);
    byKey.set(path, entry);
  }
  return [...byKey.values()].sort((a, b) => {
    // The folder-less bucket goes last: it is "everything else", not a project anyone chose.
    if ((a.path === null) !== (b.path === null)) return a.path === null ? 1 : -1;
    return b.lastActiveMs - a.lastActiveMs || a.label.localeCompare(b.label);
  });
}

/** Two projects with the same folder name get their parent folder too, so the choice is readable. */
export function disambiguatedLabels(projects: readonly DerivedProject[]): Map<string | null, string> {
  const seen = new Map<string, number>();
  for (const p of projects) if (p.path) seen.set(p.label, (seen.get(p.label) ?? 0) + 1);
  const labels = new Map<string | null, string>();
  for (const p of projects) {
    if (!p.path || (seen.get(p.label) ?? 0) < 2) {
      labels.set(p.path, p.label);
      continue;
    }
    const parts = p.path.split(/[/\\]/).filter(Boolean);
    labels.set(p.path, parts.slice(-2).join("/"));
  }
  return labels;
}

/** Does `session` belong to the project at `path` (`null` = the folder-less bucket)? */
export function inProject(session: SessionListItem, path: string | null): boolean {
  return projectKeyOf(session) === path;
}
