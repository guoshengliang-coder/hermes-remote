import { constants, type Stats } from "node:fs";
import { lstat, open, realpath, type FileHandle } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, resolve, sep } from "node:path";

export class ArtifactFileError extends Error {
  constructor(readonly status: number, readonly reason: string) { super(reason); }
}

interface ArtifactFileSystem {
  realpath(path: string): Promise<string>;
  lstat(path: string): Promise<Stats>;
  open(path: string, flags: number): Promise<FileHandle>;
}

interface ArtifactFileOptions {
  filesRoot: string;
  temporaryRoots?: readonly string[];
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

/** Opens only a validated artifact; callers own and must close the returned handle. */
export async function openArtifactFile(requestedPath: string, options: ArtifactFileOptions): Promise<{
  canonicalPath: string; handle: FileHandle; metadata: Stats;
}> {
  if (requestedPath.includes("\0") || !isAbsolute(requestedPath) || requestedPath.split(/[\\/]+/).includes("..")) {
    throw new ArtifactFileError(400, "invalid_path");
  }
  const fs = options.fileSystem ?? { lstat, realpath, open };
  const userId = options.userId === undefined ? process.geteuid?.() : options.userId;
  const path = resolve(requestedPath);
  const filesRoot = resolve(options.filesRoot);
  let canonicalPath: string;
  let temporaryRoot: string | undefined;
  if (within(path, filesRoot)) {
    // Retain the original FILES_ROOT policy, including rejection of symlinks out of that root,
    // even when their target happens to be inside one of the newly permitted temporary roots.
    const canonicalRoot = await fs.realpath(filesRoot);
    canonicalPath = await fs.realpath(path);
    if (!within(canonicalPath, canonicalRoot)) forbidden();
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
