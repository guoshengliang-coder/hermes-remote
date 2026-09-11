#!/usr/bin/env node
// Turn docs/design/stitch/design-system.md into the payload the Stitch MCP expects, and check
// that the committed file is the one the lock file says was pushed.
//
// Why a script and not a hand-typed MCP call: the push must be repeatable and reviewable. The
// markdown is the single source (its values are pinned to the app theme by
// DesignSystemExportTest); this script derives everything else from it deterministically, so two
// people pushing the same commit send byte-identical payloads, and the sha256 recorded in
// stitch.lock.json identifies exactly which revision Stitch holds.
//
// Usage:
//   node scripts/design/stitch-design-system.mjs --summary          human-readable preview
//   node scripts/design/stitch-design-system.mjs --payload out.json  MCP payload (create/update)
//   node scripts/design/stitch-design-system.mjs --base64 out.txt    designMdBase64 for upload_design_md
//   node scripts/design/stitch-design-system.mjs --check             committed sha256 == lock sha256
//
// The agent then calls the MCP with the payload and records the returned asset id plus the
// sha256 in stitch.lock.json `designSystem`. Agents never push without the user seeing --summary.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const DESIGN_SYSTEM_MD = resolve(ROOT, 'docs/design/stitch/design-system.md');
export const LOCK_FILE = resolve(ROOT, 'docs/design/stitch/stitch.lock.json');

/** Font the mock uses to stand in for the Android system font (DESIGN.md §3.1: no bundled family). */
export const STITCH_FONT = 'ROBOTO_FLEX';

export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

/** Split the YAML front matter from the body. Throws when the file does not start with one. */
export function splitFrontMatter(md) {
  if (!md.startsWith('---\n')) throw new Error('design-system.md must start with a front matter block');
  const end = md.indexOf('\n---\n', 4);
  if (end < 0) throw new Error('front matter never closes');
  return { frontMatter: md.slice(4, end), body: md.slice(end + 5) };
}

/**
 * Minimal parser for the four sections Stitch's front matter uses: `name`, and the flat
 * (`colors`, `rounded`, `spacing`) or two-level (`typography`) maps. Indentation is two spaces;
 * quoted scalars lose their quotes. Anything else is an error — the file is ours, so it should
 * never be anything else.
 */
export function parseFrontMatter(frontMatter) {
  const out = { name: '', colors: {}, typography: {}, rounded: {}, spacing: {} };
  let section = null;
  let level = null;
  for (const raw of frontMatter.split('\n')) {
    if (raw.trim() === '') continue;
    let m;
    if ((m = raw.match(/^name: (.+)$/))) { out.name = m[1].trim(); section = null; continue; }
    if ((m = raw.match(/^([a-z]+):\s*$/))) {
      section = m[1];
      if (!(section in out)) throw new Error(`unknown front matter section: ${section}`);
      level = null;
      continue;
    }
    if (section === 'typography') {
      if ((m = raw.match(/^  ([a-z0-9-]+):\s*$/))) { level = m[1]; out.typography[level] = {}; continue; }
      if ((m = raw.match(/^    ([a-zA-Z]+): (.+?)\s*$/))) {
        if (!level) throw new Error(`typography property outside a level: ${raw}`);
        out.typography[level][m[1]] = unquote(m[2]);
        continue;
      }
    } else if (section && (m = raw.match(/^  ([a-z0-9-]+): (.+?)\s*$/))) {
      out[section][m[1]] = unquote(m[2]);
      continue;
    }
    throw new Error(`unparsable front matter line: ${JSON.stringify(raw)}`);
  }
  if (!out.name) throw new Error('front matter has no name');
  if (!out.colors.primary || !out.colors.surface) throw new Error('front matter needs primary and surface');
  return out;
}

function unquote(s) {
  return s.replace(/^'(.*)'$/, '$1');
}

/**
 * Stitch's `roundness` enum from our smallest corner step (xs = 8dp, Shape.kt extraSmall). Both
 * of the designer's existing systems sit at ROUND_EIGHT, and Stitch treats this as the base radius
 * generated components start from — not the card radius.
 */
export function roundnessFor(rounded) {
  const px = parseInt((rounded.xs ?? rounded.sm ?? rounded.DEFAULT ?? '8px'), 10);
  if (px >= 12) return 'ROUND_TWELVE';
  if (px >= 8) return 'ROUND_EIGHT';
  return 'ROUND_FOUR';
}

/** The `designSystem` object for `update_design_system`, plus the fields `upload_design_md` needs. */
export function buildPayload(md) {
  const { frontMatter } = splitFrontMatter(md);
  const fm = parseFrontMatter(frontMatter);
  const typography = Object.fromEntries(
    Object.entries(fm.typography).map(([k, v]) => [k, {
      fontFamily: v.fontFamily,
      fontSize: v.fontSize,
      fontWeight: v.fontWeight,
      lineHeight: v.lineHeight,
      letterSpacing: v.letterSpacing,
    }]),
  );
  return {
    displayName: fm.name,
    sha256: sha256(md),
    designMdBase64: Buffer.from(md, 'utf8').toString('base64'),
    designSystem: {
      displayName: fm.name,
      theme: {
        colorMode: 'LIGHT',
        headlineFont: STITCH_FONT,
        bodyFont: STITCH_FONT,
        labelFont: STITCH_FONT,
        roundness: roundnessFor(fm.rounded),
        colorVariant: 'FIDELITY',
        customColor: fm.colors.primary,
        overridePrimaryColor: fm.colors.primary,
        overrideSecondaryColor: fm.colors.secondary,
        overrideTertiaryColor: fm.colors.tertiary,
        overrideNeutralColor: fm.colors.surface,
        typography,
        spacing: { ...fm.spacing },
        designMd: md,
      },
    },
  };
}

export function summary(md) {
  const p = buildPayload(md);
  const t = p.designSystem.theme;
  const lines = [
    `name:      ${p.displayName}`,
    `sha256:    ${p.sha256}`,
    `mode/font: ${t.colorMode} / ${t.headlineFont} (body ${t.bodyFont}, label ${t.labelFont})`,
    `roundness: ${t.roundness}   seed/primary ${t.customColor}   neutral ${t.overrideNeutralColor}   secondary ${t.overrideSecondaryColor}   tertiary ${t.overrideTertiaryColor}`,
    `colors:    ${Object.keys(parseFrontMatter(splitFrontMatter(md).frontMatter).colors).length} light roles in front matter`,
    `type:      ${Object.keys(t.typography).join(', ')}`,
    `spacing:   ${Object.keys(t.spacing).length} tokens`,
    `designMd:  ${md.length} chars, ${md.split('\n').length} lines`,
  ];
  return lines.join('\n');
}

export function checkAgainstLock(md, lockJson) {
  const lock = JSON.parse(lockJson);
  const rec = lock.designSystem;
  if (!rec) return { ok: false, reason: 'stitch.lock.json has no designSystem section' };
  const actual = sha256(md);
  if (rec.sha256 !== actual) {
    return { ok: false, reason: `committed design-system.md sha256 ${actual} != lock ${rec.sha256}` };
  }
  if (rec.pushedSha256 && rec.pushedSha256 !== actual) {
    return { ok: false, reason: `Stitch holds ${rec.pushedSha256} (pushed ${rec.pushedAt}); the file has moved on — push again` };
  }
  return { ok: true, reason: rec.pushedSha256 ? `in sync with Stitch asset ${rec.assetId}` : 'recorded, never pushed' };
}

function main(argv) {
  const md = readFileSync(DESIGN_SYSTEM_MD, 'utf8');
  const args = [...argv];
  if (args.length === 0) args.push('--summary');
  while (args.length) {
    const a = args.shift();
    if (a === '--summary') { console.log(summary(md)); continue; }
    if (a === '--payload') { const out = args.shift(); const p = buildPayload(md); writeFileSync(out, JSON.stringify({ designSystem: p.designSystem }, null, 2)); console.log(`wrote ${out}`); continue; }
    if (a === '--base64') { const out = args.shift(); writeFileSync(out, buildPayload(md).designMdBase64); console.log(`wrote ${out}`); continue; }
    if (a === '--check') {
      const r = checkAgainstLock(md, readFileSync(LOCK_FILE, 'utf8'));
      console.log(`${r.ok ? 'OK' : 'DRIFT'}: ${r.reason}`);
      if (!r.ok) process.exitCode = 1;
      continue;
    }
    throw new Error(`unknown option ${a}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
