import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFile, mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { basename, join, resolve } from "node:path";

const root = resolve(".");
const dist = resolve(root, "dist");
const packageJson = JSON.parse(await readFile(resolve(root, "package.json"), "utf8"));
const contract = JSON.parse(await readFile(resolve(root, "release-contract.json"), "utf8"));
const configSchema = JSON.parse(await readFile(resolve(root, "config.schema.json"), "utf8"));
const sourceCommit = resolveSourceCommit();
const sourceDirty = resolveSourceDirty();
const builtAt = resolveBuildTime();

if (configSchema.$id?.endsWith(`-v${contract.configSchemaVersion}.json`) !== true) {
  throw new Error("config schema version does not match the release contract");
}

await writeFile(
  resolve(dist, "config.schema.json"),
  `${JSON.stringify(configSchema, null, 2)}\n`,
  "utf8",
);
await copyReleaseOperations();

const files = {};
for (const path of await walk(dist)) {
  const relative = path.slice(dist.length + 1);
  if (relative === "release-manifest.json") continue;
  files[relative] = sha256(await readFile(path));
}

const manifest = {
  ...contract,
  serverVersion: packageJson.version,
  sourceCommit,
  sourceDirty,
  builtAt,
  files,
};
await writeFile(
  resolve(dist, "release-manifest.json"),
  `${JSON.stringify(manifest, null, 2)}\n`,
  "utf8",
);

function resolveSourceCommit() {
  const provided = process.env.HERMES_BUILD_COMMIT?.trim();
  if (provided) return validateCommit(provided);
  try {
    return validateCommit(execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: resolve(root, ".."),
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim());
  } catch {
    return "development";
  }
}

async function copyReleaseOperations() {
  const migrationsTarget = resolve(dist, "migrations");
  const operationsTarget = resolve(dist, "ops");
  await mkdir(migrationsTarget, { recursive: true });
  await mkdir(operationsTarget, { recursive: true });
  for (const name of (await readdir(resolve(root, "migrations"))).filter((entry) => entry.endsWith(".sql"))) {
    await copyFile(resolve(root, "migrations", name), resolve(migrationsTarget, name));
  }
  await copyFile(
    resolve(root, "scripts", "migrate-account.mjs"),
    resolve(operationsTarget, "migrate-account.mjs"),
  );
}

function resolveSourceDirty() {
  const provided = process.env.HERMES_BUILD_DIRTY;
  if (provided !== undefined) {
    if (provided === "0") return false;
    if (provided === "1") return true;
    throw new Error("HERMES_BUILD_DIRTY must be 0 or 1");
  }
  try {
    return execFileSync("git", ["status", "--porcelain", "--untracked-files=normal"], {
      cwd: resolve(root, ".."),
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim().length > 0;
  } catch {
    return true;
  }
}

function validateCommit(value) {
  if (value === "development" || /^[0-9a-f]{40}$/.test(value)) return value;
  throw new Error("HERMES_BUILD_COMMIT must be a 40-character lowercase Git commit or development");
}

function resolveBuildTime() {
  const epoch = process.env.SOURCE_DATE_EPOCH;
  const date = epoch === undefined ? new Date() : new Date(Number(epoch) * 1000);
  if (!Number.isFinite(date.getTime())) throw new Error("SOURCE_DATE_EPOCH must be Unix seconds");
  return date.toISOString();
}

async function walk(directory) {
  const paths = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) paths.push(...await walk(path));
    else if (entry.isFile()) paths.push(path);
  }
  return paths.sort((left, right) => basename(left).localeCompare(basename(right)) || left.localeCompare(right));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
