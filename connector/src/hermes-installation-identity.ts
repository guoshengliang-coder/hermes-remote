import { createHash } from "node:crypto";
import { lstat, readFile, realpath } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

/** Opaque on-disk identity only; never a claim about which code a running process loaded. */
export async function readHermesInstallationIdentity(home = homedir()): Promise<string | undefined> {
  try {
    const root = await realpath(join(home, ".hermes", "hermes-agent"));
    const ownerHome = await realpath(join(home, ".hermes"));
    if (root !== join(ownerHome, "hermes-agent") || !await ownedDirectory(join(root, ".git"))) return undefined;
    const key = createHash("sha256").update(root).digest("hex").slice(0, 16);
    const paths = [
      ".git/HEAD", ".git/packed-refs", "install-stamp.json", "pm/lock.json",
      "uv.lock", "pyproject.toml",
    ];
    const hash = createHash("sha256").update(root).update(ownerHome);
    let foundHead = false;
    for (const name of paths) {
      const bytes = await boundedFile(join(root, name));
      if (bytes) {
        hash.update(name).update(bytes);
        if (name === ".git/HEAD") {
          foundHead = true;
          const ref = /^ref: (refs\/[A-Za-z0-9_./-]+)\s*$/.exec(bytes.toString("utf8"));
          if (ref && !ref[1].includes("..")) {
            const value = await boundedFile(join(root, ".git", ref[1]));
            if (value) hash.update(value);
          }
        }
      }
    }
    const facts = await boundedFile(join(ownerHome, "installs", key, "facts.json"));
    if (facts) hash.update(facts);
    return foundHead ? hash.digest("hex") : undefined;
  } catch { return undefined; }
}

async function boundedFile(path: string): Promise<Buffer | undefined> {
  try {
    const info = await lstat(path);
    if (!info.isFile() || info.size > 4 * 1024 * 1024 || (info.mode & 0o022)
        || process.getuid && info.uid !== process.getuid()) return undefined;
    return await readFile(path);
  } catch { return undefined; }
}

async function ownedDirectory(path: string): Promise<boolean> {
  try {
    const info = await lstat(path);
    return info.isDirectory() && !(info.mode & 0o022) && (!process.getuid || info.uid === process.getuid());
  } catch { return false; }
}
