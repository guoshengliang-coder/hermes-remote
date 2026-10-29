import { constants, type Stats } from "node:fs";
import { lstat, open, realpath, stat, type FileHandle } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, resolve, sep } from "node:path";

export class ArtifactFileError extends Error {
  constructor(readonly status: number, readonly reason: string) { super(reason); }
}

/**
 * One explicitly authorized external read root (HG-245), pinned at Connector startup. `lexical`
 * is the configured path, `canonical` its resolved physical form; `dev`+`ino` identify the
 * mounted volume's root directory. Every request re-stats the canonical root and compares
 * against this baseline, so a different volume swapped onto the same mount point — or the
 * volume simply being gone — can never widen what the grant exposes.
 */
export interface ExternalRootGrant {
  readonly lexical: string;
  readonly canonical: string;
  readonly dev: number;
  readonly ino: number;
}

interface ArtifactFileSystem {
  realpath(path: string): Promise<string>;
  lstat(path: string): Promise<Stats>;
  stat(path: string): Promise<Stats>;
  open(path: string, flags: number): Promise<FileHandle>;
}

interface ArtifactFileOptions {
  filesRoot: string;
  temporaryRoots?: readonly string[];
  externalRoots?: readonly ExternalRootGrant[];
  userId?: number | null;
  fileSystem?: ArtifactFileSystem;
}

function within(candidate: string, root: string): boolean {
  return candidate === root || candidate.startsWith(root.endsWith(sep) ? root : `${root}${sep}`);
}

function forbidden(): never { throw new ArtifactFileError(403, "forbidden"); }

function temporaryFile(metadata: Stats, userId: number | null | undefined): void {
  // A hard link could otherwise expose a file whose original name is outside the allowed root.
  if (userId == null || metadata.uid !== userId || metadata.nlink !== 1) forbidden();
}

async function temporaryDirectories(
  path: string, root: string, userId: number | null | undefined, fs: ArtifactFileSystem,
): Promise<void> {
  // Shared /tmp is trusted only as a sticky, root-owned base. Below that base another user must
  // not be able to rename an ancestor while we validate/open the current user's file.
  for (let directory = dirname(path); ; directory = dirname(directory)) {
    const metadata = await fs.lstat(directory);
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) forbidden();
    const sharedBase = directory === root && metadata.uid === 0 && (metadata.mode & 0o1000) !== 0;
    if (!sharedBase && (metadata.uid !== userId || (metadata.mode & 0o022) !== 0)) forbidden();
    if (directory === root) break;
    if (!within(dirname(directory), root)) forbidden();
  }
}

/** Returns the opened grant match for `path`, or undefined when no external root claims it. */
async function openExternalRootFile(
  path: string, grants: readonly ExternalRootGrant[] | undefined, fs: ArtifactFileSystem,
): Promise<{ canonicalPath: string; dev: number } | undefined> {
  if (!grants?.length) return undefined;
  const grant = grants.find((entry) => within(path, entry.lexical) || within(path, entry.canonical));
  if (grant === undefined) return undefined;
  // Defense in depth: a grant resolving to the filesystem root would be a whole-disk read
  // permission; startup refuses to create one, so a hand-built grant must fail here too.
  if (dirname(grant.canonical) === grant.canonical) forbidden();
  // Re-stat the canonical root against the startup baseline: a different volume swapped onto
  // the same mount point (dev/ino mismatch) is forbidden; an unmounted volume reports not found.
  let rootMetadata: Stats;
  try {
    rootMetadata = await fs.stat(grant.canonical);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (["ENOENT", "ENOTDIR"].includes(code ?? "")) throw new ArtifactFileError(404, "not_found");
    if (["EACCES", "EPERM"].includes(code ?? "")) forbidden();
    throw error;
  }
  if (!rootMetadata.isDirectory() || rootMetadata.dev !== grant.dev || rootMetadata.ino !== grant.ino) {
    forbidden();
  }
  const canonicalPath = await fs.realpath(path);
  // A link may not cross out of its own external grant — mirroring the FILES_ROOT rule that even
  // a target inside another authorized root stays unreachable through a boundary-crossing link.
  if (!within(canonicalPath, grant.canonical)) forbidden();
  return { canonicalPath, dev: grant.dev };
}

/** Opens only a validated artifact; callers own and must close the returned handle. */
export async function openArtifactFile(requestedPath: string, options: ArtifactFileOptions): Promise<{
  canonicalPath: string; handle: FileHandle; metadata: Stats;
}> {
  if (requestedPath.includes("\0") || !isAbsolute(requestedPath) || requestedPath.split(/[\\/]+/).includes("..")) {
    throw new ArtifactFileError(400, "invalid_path");
  }
  const fs = options.fileSystem ?? { lstat, realpath, stat, open };
  const userId = options.userId === undefined ? process.geteuid?.() : options.userId;
  const path = resolve(requestedPath);
  const filesRoot = resolve(options.filesRoot);
  let canonicalPath: string;
  let temporaryRoot: string | undefined;
  let externalGrantDev: number | undefined;
  if (within(path, filesRoot)) {
    // Retain the original FILES_ROOT policy, including rejection of symlinks out of that root,
    // even when their target happens to be inside one of the newly permitted temporary roots.
    const canonicalRoot = await fs.realpath(filesRoot);
    canonicalPath = await fs.realpath(path);
    if (!within(canonicalPath, canonicalRoot)) forbidden();
  } else {
    // Explicitly authorized external read roots come first (HG-245); a path that matches none
    // of them keeps falling through to the shared temporary-root policy below.
    const external = await openExternalRootFile(path, options.externalRoots, fs);
    if (external !== undefined) {
      canonicalPath = external.canonicalPath;
      externalGrantDev = external.dev;
    } else {
      const roots = options.temporaryRoots ?? ["/tmp", "/private/tmp", tmpdir()];
      for (const root of new Set(roots.map((entry) => resolve(entry)))) {
        // Never turn TMPDIR=/ into a whole-disk download grant.
        if (dirname(root) === root) continue;
        let canonicalRoot: string;
        try { canonicalRoot = await fs.realpath(root); }
        catch (error) {
          const code = (error as NodeJS.ErrnoException).code;
          if (["ENOENT", "ENOTDIR", "EACCES", "EPERM"].includes(code ?? "")) continue;
          throw error;
        }
        if (dirname(canonicalRoot) === canonicalRoot) continue;
        if (!within(path, root) && !within(path, canonicalRoot)) continue;
        temporaryRoot = canonicalRoot;
        break;
      }
      if (temporaryRoot === undefined || userId == null) forbidden();
      canonicalPath = await fs.realpath(path);
      if (!within(canonicalPath, temporaryRoot)) forbidden();
    }
  }

  const expected = await fs.lstat(canonicalPath);
  if (!expected.isFile()) throw new ArtifactFileError(400, "invalid_file");
  if (temporaryRoot !== undefined) {
    temporaryFile(expected, userId);
    await temporaryDirectories(canonicalPath, temporaryRoot, userId, fs);
  }
  let handle: FileHandle;
  try {
    // NONBLOCK also prevents a replaced FIFO from hanging before fstat rejects it.
    handle = await fs.open(canonicalPath, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ELOOP") forbidden();
    throw error;
  }
  try {
    const metadata = await handle.stat();
    if (!metadata.isFile() || metadata.dev !== expected.dev || metadata.ino !== expected.ino) forbidden();
    // The pinned external grant's volume identity is the final word: even if every earlier check
    // raced a mount-point swap and agreed on the replacement volume, the opened file must still
    // live on the authorized volume (same dev as the startup baseline) to be served.
    if (externalGrantDev !== undefined && metadata.dev !== externalGrantDev) forbidden();
    if (temporaryRoot !== undefined) {
      temporaryFile(metadata, userId);
      if (await fs.realpath(path) !== canonicalPath) forbidden();
      await temporaryDirectories(canonicalPath, temporaryRoot, userId, fs);
    }
    return { canonicalPath, handle, metadata };
  } catch (error) {
    await handle.close();
    throw error;
  }
}
