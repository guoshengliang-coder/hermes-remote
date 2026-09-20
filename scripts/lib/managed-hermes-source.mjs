import path from "node:path";

/** Runtime source copied from the pinned Hermes checkout into a managed component. */
export const HERMES_SOURCE_DIRECTORIES = Object.freeze([
  "agent", "cron", "gateway", "hermes_cli", "locales", "native", "optional-skills",
  "plugins", "skills", "tools", "tui_gateway",
]);

/** Root metadata required by the managed runtime. */
export const HERMES_METADATA_FILES = Object.freeze([
  "LICENSE", "compat_manifest.json", "pyproject.toml",
]);

/**
 * Return whether a repository-relative Hermes path is present in the staged managed tree.
 *
 * Keep this as the single source of truth for both staging and patch validation. A patch that
 * targets anything outside this set cannot be applied after the packager has copied its allowlist.
 */
export function isManagedHermesSourcePath(file) {
  if (typeof file !== "string" || file.length === 0 || file.includes("\\")
      || path.posix.isAbsolute(file)) return false;
  const normalized = path.posix.normalize(file);
  if (normalized !== file || normalized === "." || normalized.startsWith("../")) return false;

  const [topLevel, ...rest] = normalized.split("/");
  if (rest.length === 0) {
    return topLevel.endsWith(".py") || HERMES_METADATA_FILES.includes(topLevel);
  }
  return HERMES_SOURCE_DIRECTORIES.includes(topLevel);
}
