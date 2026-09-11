import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  DESIGN_SYSTEM_MD,
  LOCK_FILE,
  buildPayload,
  checkAgainstLock,
  frontMatterSha256,
  parseFrontMatter,
  roundnessFor,
  rules,
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
  const h = frontMatterSha256(SAMPLE);
  assert.equal(checkAgainstLock(SAMPLE, JSON.stringify({})).ok, false);
  assert.equal(checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { frontMatterSha256: 'nope' } })).ok, false);
  const never = checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { frontMatterSha256: h } }));
  assert.equal(never.ok, true);
  assert.match(never.reason, /never pushed/);
  const synced = checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { frontMatterSha256: h, pushedFrontMatterSha256: h, assetId: 'a1' } }));
  assert.equal(synced.ok, true);
  assert.match(synced.reason, /a1/);
  const drift = checkAgainstLock(SAMPLE, JSON.stringify({ designSystem: { frontMatterSha256: h, pushedFrontMatterSha256: 'old', pushedAt: 'x' } }));
  assert.equal(drift.ok, false);
  assert.match(drift.reason, /push again/);
});

test('editing prose is not drift, editing a token is', () => {
  const h = frontMatterSha256(SAMPLE);
  const lock = JSON.stringify({ designSystem: { frontMatterSha256: h, pushedFrontMatterSha256: h, assetId: 'a1' } });
  const proseEdit = SAMPLE.replace('# body', '# body\n\nan extra paragraph the channel never carries');
  assert.equal(checkAgainstLock(proseEdit, lock).ok, true);
  const tokenEdit = SAMPLE.replace("primary: '#004ac6'", "primary: '#ff0000'");
  assert.equal(checkAgainstLock(tokenEdit, lock).ok, false);
});

test('rules() keeps the constraints and drops the repo-facing framing', () => {
  const md = readFileSync(DESIGN_SYSTEM_MD, 'utf8');
  const r = rules(md);
  // No front matter, no test-pinning blockquote, no repo-vs-mock section.
  assert.ok(!r.startsWith('---'));
  assert.ok(!r.includes('DesignSystemExportTest'));
  assert.ok(!r.includes('与本仓不一致时怎么办'));
  // Every rule a generator must not violate is still there, including the rejected ones
  // (no stroke on the search field, time buckets never green) and the ones that reversed
  // earlier decisions (mono is now bundled; there is no longer a size floor).
  for (const rule of ['不加描边', '中性近黑', '时间桶绝不用绿', '等宽只用在', '没有字号下限', '描边式']) {
    assert.ok(r.includes(rule), `rules() lost: ${rule}`);
  }
  // And the values, since a generator needs them alongside the constraints.
  assert.ok(r.includes('#FAF9F5') && r.includes('status-running'));
});

test('the committed design-system.md parses and matches its lock record', () => {
  const md = readFileSync(DESIGN_SYSTEM_MD, 'utf8');
  const p = buildPayload(md);
  assert.ok(Object.keys(p.designSystem.theme.typography).length >= 10);
  assert.ok(summary(md).includes(p.sha256));
  const r = checkAgainstLock(md, readFileSync(LOCK_FILE, 'utf8'));
  assert.ok(r.ok, r.reason);
});
