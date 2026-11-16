import { createHash } from 'node:crypto';

export const requiredHermesChecks = [
  'SQLite guard rejects an external store', 'RPC:hermes_remote:True', 'RPC:hermes_remote:False',
  'messages retained:hermes_remote', 'same-id profile isolation',
  'console output confirms exact target and title', 'exact snapshot rejects a prefix alias before mutation',
  'console rejects shell syntax without changing title', 'official delete:hermes_remote',
  'delete refuses a live session', 'official stored workspace move', 'authority forbids legacy fallback',
];
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const sha = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const commit = value => typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);
const refuse = () => { throw new Error('desktop_combination_evidence_mismatch'); };

export function buildCombinationEvidence({ app, executable, bundleSHA256, manifest, manifestBytes, upstream, upstreamBytes, hermesCommit }) {
  if (!sha(bundleSHA256) || !commit(hermesCommit) || upstream?.sourceCommit !== hermesCommit || upstream.passed !== true
      || !sha(upstream.runtimeIdentity?.interpreterSHA256) || !sha(upstream.runtimeIdentity?.importedCodeSHA256)
      || !Number.isSafeInteger(upstream.runtimeIdentity?.importedModuleCount) || upstream.runtimeIdentity.importedModuleCount < 1
      || !Array.isArray(upstream.checks) || upstream.checks.some(c => c?.passed !== true)
      || !requiredHermesChecks.every(name => upstream.checks.some(c => c.name === name && c.passed === true))
      || !commit(app?.sourceCommit) || !Number.isSafeInteger(app.build) || app.build < 1
      || !/^\d+\.\d+\.\d+$/.test(app.version ?? '')) refuse();
  const node = manifest?.components?.find(c => c.kind === 'node_runtime');
  const connector = manifest?.components?.find(c => c.kind === 'connector');
  if (manifest?.schemaVersion !== 2 || manifest.components.length !== 2 || !node || !connector
      || !sha(node.contentSHA256) || !sha(connector.contentSHA256) || node.dependencies.length !== 0
      || connector.dependencies.length !== 1 || connector.dependencies[0].kind !== 'node_runtime'
      || connector.dependencies[0].contentSHA256 !== node.contentSHA256) refuse();
  return {
    schemaVersion: 1, scope: 'isolated_handlers_only',
    app: { ...app, bundleSHA256, executableSHA256: digest(executable) },
    manifestSHA256: digest(manifestBytes), releaseVersion: manifest.releaseVersion,
    components: [node, connector].map(({ kind, version, contentSHA256 }) => ({ kind, version, contentSHA256 })),
    hermesCommit, runtimeIdentity: upstream.runtimeIdentity, upstreamProbeSHA256: digest(upstreamBytes), checks: upstream.checks.map(({ name, passed }) => ({ name, passed })),
    remainingVerification: ['installed app startup', 'live Connector identity and fresh binding health', 'dedicated session business acceptance'],
  };
}

export function assertCombinationEvidence(evidence, { version, build, sourceCommit, executableSHA256, bundleSHA256, manifestSHA256 }) {
  if (evidence?.schemaVersion !== 1 || evidence.scope !== 'isolated_handlers_only'
      || evidence.app?.version !== version || evidence.app?.build !== build || evidence.app?.sourceCommit !== sourceCommit
      || !sha(bundleSHA256) || evidence.app?.bundleSHA256 !== bundleSHA256
      || evidence.app?.executableSHA256 !== executableSHA256 || evidence.manifestSHA256 !== manifestSHA256
      || !sha(evidence.runtimeIdentity?.interpreterSHA256) || !sha(evidence.runtimeIdentity?.importedCodeSHA256)
      || !sha(manifestSHA256) || !commit(evidence.hermesCommit) || !sha(evidence.upstreamProbeSHA256)
      || !Array.isArray(evidence.checks) || evidence.checks.some(c => c.passed !== true)
      || !requiredHermesChecks.every(name => evidence.checks.some(c => c.name === name && c.passed === true))
      || !Array.isArray(evidence.remainingVerification) || evidence.remainingVerification.length === 0) refuse();
  return true;
}

// The signed bundle and the published index must describe the same installation contract.
export function assertAppIndexMatchesBundle(index, plist) {
  if (index?.appVersion !== plist?.CFBundleShortVersionString
      || String(index?.buildNumber) !== plist?.CFBundleVersion
      || index?.minimumMacOS !== plist?.LSMinimumSystemVersion
      || index?.channel !== plist?.HermesGoDesktopAppUpdateChannel
      || index?.architecture !== plist?.HermesGoDesktopAppUpdateArchitecture) refuse();
  return true;
}
