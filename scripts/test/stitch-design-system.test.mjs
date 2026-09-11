import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  DESIGN_SYSTEM_MD,
  LOCK_FILE,
  buildPayload,
  checkAgainstLock,
  parseFrontMatter,
  roundnessFor,
  sha256,
  splitFrontMatter,
  summary,
} from '../design/stitch-design-system.mjs';

const SAMPLE = `---
name: Sample
colors:
  surface: '#faf9f5'
  primary: '#004ac6'
  secondary: '#605c54'
  tertiary: '#824500'
typography:
  body-md:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0px
rounded:
  xs: 8px
  sm: 12px
spacing:
  fab-size: 56px
---

# body
`;

test('front matter parses into the four Stitch sections', () => {
  const { frontMatter, body } = splitFrontMatter(SAMPLE);
  const fm = parseFrontMatter(frontMatter);
  assert.equal(fm.name, 'Sample');
  assert.equal(fm.colors.primary, '#004ac6');
  assert.deepEqual(fm.typography['body-md'], {
    fontFamily: 'Roboto Flex', fontSize: '14px', fontWeight: '400', lineHeight: '20px', letterSpacing: '0px',
  });
  assert.equal(fm.spacing['fab-size'], '56px');
  assert.equal(body.trim(), '# body');
});

test('unknown sections and malformed lines are rejected, not skipped', () => {
  assert.throws(() => parseFrontMatter('name: x\nweird:\n  a: b'), /unknown front matter section/);
  assert.throws(() => parseFrontMatter('name: x\ncolors:\n  bad line'), /unparsable/);
  assert.throws(() => parseFrontMatter('name: x\ncolors:\n  surface: \'#fff\''), /primary and surface/);
});

test('payload is deterministic and carries the whole markdown as designMd', () => {
  const a = buildPayload(SAMPLE);
  const b = buildPayload(SAMPLE);
  assert.deepEqual(a, b);
  assert.equal(a.designSystem.theme.designMd, SAMPLE);
  assert.equal(a.designSystem.theme.customColor, '#004ac6');
  assert.equal(a.designSystem.theme.overrideNeutralColor, '#faf9f5');
  assert.equal(a.designSystem.theme.headlineFont, 'ROBOTO_FLEX');
  assert.equal(a.designSystem.theme.roundness, 'ROUND_EIGHT');
  assert.equal(Buffer.from(a.designMdBase64, 'base64').toString('utf8'), SAMPLE);
  assert.equal(a.sha256, sha256(SAMPLE));
});

test('roundness maps from the smallest corner step', () => {
  assert.equal(roundnessFor({ xs: '8px', sm: '12px' }), 'ROUND_EIGHT');
  assert.equal(roundnessFor({ xs: '12px' }), 'ROUND_TWELVE');
  assert.equal(roundnessFor({ xs: '4px' }), 'ROUND_FOUR');
  assert.equal(roundnessFor({ sm: '12px' }), 'ROUND_TWELVE');
  assert.equal(roundnessFor({}), 'ROUND_EIGHT');
});

test('lock check distinguishes never-pushed, in-sync and drifted', () => {
  const h = sha256(SAMPLE);
  assert.equal(checkAgainstLock(SAMPLE, JSON.stringify({})).ok, false);
  assert.equal(checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { sha256: 'nope' } })).ok, false);
  const never = checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { sha256: h } }));
  assert.equal(never.ok, true);
  assert.match(never.reason, /never pushed/);
  const synced = checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { sha256: h, pushedSha256: h, assetId: 'a1' } }));
  assert.equal(synced.ok, true);
  assert.match(synced.reason, /a1/);
  const drift = checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { sha256: h, pushedSha256: 'old', pushedAt: 'x' } }));
  assert.equal(drift.ok, false);
  assert.match(drift.reason, /push again/);
});

test('the committed design-system.md parses and matches its lock record', () => {
  const md = readFileSync(DESIGN_SYSTEM_MD, 'utf8');
  const p = buildPayload(md);
  assert.ok(Object.keys(p.designSystem.theme.typography).length >= 10);
  assert.ok(summary(md).includes(p.sha256));
  const r = checkAgainstLock(md, readFileSync(LOCK_FILE, 'utf8'));
  assert.ok(r.ok, r.reason);
});
