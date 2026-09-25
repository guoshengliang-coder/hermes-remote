#!/usr/bin/env node
// Builds the stable Desktop app update index that `DesktopAppUpdateIndex` consumes. Offline and
// inert: it reads a finished DMG, computes its size and SHA-256, and writes one JSON file. It never
// uploads, deploys, or enables anything.
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { basename, isAbsolute, join } from "node:path";
import { parseArgs } from "node:util";

const usage = `Usage: scripts/desktop-app-update-index.mjs \\
  --version <x.y.z> --channel <channel> --architecture <arm64|x86_64|universal> \\
  --dmg <path/to/Hermes-Go-Desktop-x.y.z.dmg> --origin <https://host> \\
  --notes-file <notes.json> --source-commit <40-hex> --output <empty-or-existing-dir> \\
  [--minimum-macos 14.0] [--build-number N]`;

function fail(message, code = 64) {
  console.error(message);
  process.exit(code);
}

const { values } = parseArgs({
  options: {
    version: { type: "string" },
    channel: { type: "string" },
    architecture: { type: "string" },
    dmg: { type: "string" },
    origin: { type: "string" },
    "notes-file": { type: "string" },
    "source-commit": { type: "string" },
    output: { type: "string" },
    "minimum-macos": { type: "string", default: "14.0" },
    "build-number": { type: "string" },
    help: { type: "boolean", default: false },
  },
  allowPositionals: false,
});

if (values.help) {
  console.log(usage);
  process.exit(0);
}

const semver = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const identifier = /^[A-Za-z0-9._-]{1,32}$/;
const commit = /^[0-9a-f]{40}$/;
const minimumMacOS = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?$/;

const version = values.version;
const channel = values.channel;
const architecture = values.architecture;
const dmg = values.dmg;
const origin = values.origin;
const notesFile = values["notes-file"];
const sourceCommit = values["source-commit"];
const output = values.output;

if (!version || !semver.test(version)) fail("A canonical semantic --version (x.y.z) is required.");
if (!channel || !identifier.test(channel)) fail("A bounded --channel is required.");
if (!["arm64", "x86_64", "universal"].includes(architecture)) {
  fail("--architecture must be arm64, x86_64, or universal.");
}
if (!dmg || !isAbsolute(dmg)) fail("--dmg must be an absolute path.");
if (!origin || !/^https:\/\/[A-Za-z0-9._:-]+$/.test(origin)) {
  fail("--origin must be a bare https origin with no path, query, or credentials.");
}
if (!notesFile || !isAbsolute(notesFile)) fail("--notes-file must be an absolute path.");
if (!sourceCommit || !commit.test(sourceCommit)) fail("--source-commit must be 40 lowercase hex characters.");
if (!output || !isAbsolute(output)) fail("--output must be an absolute path.");
if (!minimumMacOS.test(values["minimum-macos"])) fail("--minimum-macos must be x.y or x.y.z.");

const buildNumber = values["build-number"] === undefined ? undefined : Number(values["build-number"]);
if (buildNumber !== undefined && (!Number.isInteger(buildNumber) || buildNumber <= 0)) {
  fail("--build-number must be a positive integer when given.");
}
if (buildNumber === undefined) fail("--build-number is required.");

const dmgName = `Hermes-Go-Desktop-${version}.dmg`;
if (basename(dmg) !== dmgName) {
  fail(`--dmg must be named ${dmgName}.`);
}

let dmgStat;
try {
  dmgStat = await stat(dmg);
} catch {
  fail(`DMG not found: ${dmg}`, 66);
}
if (!dmgStat.isFile()) fail("--dmg must be a regular file.", 66);

let notes;
try {
  notes = JSON.parse(await readFile(notesFile, "utf8"));
} catch (error) {
  fail(`Could not read or parse --notes-file: ${error.message}`, 66);
}
if (!Array.isArray(notes) || notes.length > 20) fail("Notes must be an array of at most 20 strings.");
for (const note of notes) {
  if (typeof note !== "string" || note.length === 0 || Buffer.byteLength(note) > 500) {
    fail("Each note must be a non-empty string of at most 500 bytes.");
  }
  if (/[\u0000-\u001f\u007f]/.test(note)) fail("Notes must be single-line without control characters.");
}

const digest = await new Promise((resolve, reject) => {
  const hash = createHash("sha256");
  createReadStream(dmg)
    .on("error", reject)
    .on("data", (chunk) => hash.update(chunk))
    .on("end", () => resolve(hash.digest("hex")));
});

const index = {
  schemaVersion: 1,
  channel,
  architecture,
  appVersion: version,
  buildNumber,
  minimumMacOS: values["minimum-macos"],
  downloadURL: `${origin}/desktop/apps/${version}/${dmgName}`,
  sizeBytes: dmgStat.size,
  sha256: digest,
  releaseNotes: notes,
  sourceCommit,
  updatedAt: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
};

try {
  await mkdir(output, { recursive: true });
  await writeFile(join(output, "index.json"), `${JSON.stringify(index)}\n`, {
    mode: 0o600,
    flag: "wx",
  });
} catch (error) {
  fail(`Could not write the index: ${error.message}`, 66);
}

console.log("DESKTOP_APP_UPDATE_INDEX_OK");
console.log(`INDEX=${join(output, "index.json")}`);
console.log(`SIZE_BYTES=${dmgStat.size}`);
console.log(`SHA256=${digest}`);
