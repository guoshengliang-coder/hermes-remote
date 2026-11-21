import { candidateIdentity } from './missiongo-release-snapshot.mjs';

const sha = /^[0-9a-f]{40}$/;
const publishers = new Map([['android', 'android'], ['androidApp', 'android'], ['web', 'web']]);

export function releaseTargets(artifacts) {
  if (!Array.isArray(artifacts) || artifacts.length === 0) throw new Error('Missing required release artifacts');
  const targets = new Set();
  for (const artifact of artifacts) {
    const publisher = publishers.get(artifact);
    if (!publisher || targets.has(publisher)) throw new Error(`Unsupported or duplicate release artifact: ${artifact}`);
    targets.add(publisher);
  }
  return [...targets];
}

// The resolver verifies GitHub state and binds the commit to the candidate, including its exact
// PR and repository. A missing result stays unresolved; it never becomes a no-release exclusion.
export async function checkReleaseCoverage({ snapshot, repository, targetHead, artifacts, resolveIntegration, contains }) {
  if (!sha.test(targetHead ?? '')) throw new Error('Coverage requires the exact target commit');
  const results = [];
  for (const candidate of snapshot.candidates) {
    const identity = candidateIdentity(candidate);
    try {
      const targets = releaseTargets(candidate.requiredArtifacts);
      const integration = await resolveIntegration(candidate);
      if (!integration || integration.repository !== repository || integration.branch !== 'main' ||
          !sha.test(integration.commit ?? '') || !await contains(targetHead, integration.commit)) {
        throw new Error('Candidate integration is not verified on the target commit');
      }
      const checks = [];
      for (const artifact of targets) {
        const source = artifacts[artifact]?.sourceCommit;
        const covered = sha.test(source ?? '') && await contains(targetHead, source) && await contains(source, integration.commit);
        checks.push({ artifact, sourceCommit: source ?? null, covered });
      }
      results.push({ identity, integrationCommit: integration.commit, checks, covered: checks.every(check => check.covered) });
    } catch (error) {
      results.push({ identity, covered: false, reason: error.message });
    }
  }
  return { ready: results.every(result => result.covered), results };
}
