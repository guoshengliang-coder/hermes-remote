import { createHash } from 'node:crypto';

const sha = /^[0-9a-f]{40}$/;
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const itemKey = /^[A-Z][A-Z0-9]*-[1-9][0-9]*$/;

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  }
  return value;
}

function digest(value) {
  return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
}

export function candidateIdentity(candidate) {
  if (candidate?.kind === 'delivery') {
    if (!uuid.test(candidate.deliveryId ?? '') || !/^[0-9a-f]{64}$/.test(candidate.expectedRevision ?? '')) {
      throw new Error('Invalid standalone delivery identity');
    }
    return `delivery:${candidate.deliveryId}`;
  }
  if (!itemKey.test(candidate?.itemKey ?? '') || ![undefined, 'item'].includes(candidate.kind)) {
    throw new Error('Invalid release candidate identity');
  }
  return `${candidate.itemKey}:${candidate.developmentEventId ?? 'legacy'}`;
}

export function readReleaseSnapshot(input, { productId, now = Date.now() }) {
  if (!productId || !Array.isArray(input?.pages) || input.pages.length === 0) {
    throw new Error('A bound product and every MissionGo candidate page are required');
  }
  const snapshot = { productId, candidates: [], noReleaseItems: [], noReleaseDeliveries: [] };
  const seen = new Set();
  let cursor = null;
  let oldest = now;
  function append(target, value, identity) {
    if (seen.has(identity)) throw new Error(`Repeated release identity: ${identity}`);
    seen.add(identity);
    target.push(value);
  }
  for (const [index, page] of input.pages.entries()) {
    if (page?.productId !== productId || page.requestedBeforeSequence !== cursor || page.coverageVersion !== 2 ||
        ![page.candidates, page.noReleaseItems, page.noReleaseDeliveries].every(Array.isArray)) {
      throw new Error('Candidate product, coverage version, or pagination does not match');
    }
    const generated = Date.parse(page.generatedAt);
    if (!Number.isFinite(generated) || generated > now + 60_000 || now - generated > 15 * 60_000) {
      throw new Error('Candidate page is stale or has an invalid timestamp');
    }
    oldest = Math.min(oldest, generated);
    for (const candidate of page.candidates) append(snapshot.candidates, candidate, candidateIdentity(candidate));
    for (const exclusion of page.noReleaseItems) {
      if (!itemKey.test(exclusion?.itemKey ?? '') || typeof exclusion.noReleaseReason !== 'string' || !exclusion.noReleaseReason.trim()) {
        throw new Error('Invalid no-release item');
      }
      append(snapshot.noReleaseItems, exclusion, `no-release:${exclusion.itemKey}`);
    }
    for (const exclusion of page.noReleaseDeliveries) {
      if (!uuid.test(exclusion?.deliveryId ?? '') || typeof exclusion.noReleaseReason !== 'string' || !exclusion.noReleaseReason.trim()) {
        throw new Error('Invalid no-release delivery');
      }
      append(snapshot.noReleaseDeliveries, exclusion, `delivery:${exclusion.deliveryId}`);
    }
    const next = page.nextBeforeSequence ?? null;
    if (next === null ? index !== input.pages.length - 1
      : !Number.isSafeInteger(next) || next <= 0 || (cursor !== null && next >= cursor)) {
      throw new Error('Invalid release candidate cursor');
    }
    cursor = next;
  }
  if (cursor !== null) throw new Error('Missing final release candidate page');
  const sorted = (items, identity) => [...items].sort((a, b) => identity(a).localeCompare(identity(b)));
  const candidatesDigest = digest({ productId,
    candidates: sorted(snapshot.candidates, candidateIdentity),
    noReleaseItems: sorted(snapshot.noReleaseItems, item => item.itemKey),
    noReleaseDeliveries: sorted(snapshot.noReleaseDeliveries, delivery => delivery.deliveryId),
  });
  return { ...snapshot, snapshotAt: new Date(oldest).toISOString(), candidatesDigest };
}

export function bindBatchObservation(batch, { productId, repository }) {
  if (!uuid.test(batch?.id ?? '') || batch.productId !== productId || typeof batch.publish !== 'boolean' ||
      !Array.isArray(batch.deliveries) || !batch.deliveries.length || !Array.isArray(batch.entries) ||
      !Array.isArray(batch.selection) || batch.deliveries.length !== batch.selection.length ||
      batch.deliveries.length !== batch.entries.length || new Set(batch.deliveries).size !== batch.deliveries.length ||
      batch.selection.some(row => !batch.deliveries.includes(row?.id) || typeof row.revision !== 'string' || !row.revision) ||
      new Set(batch.selection.map(row => row.id)).size !== batch.deliveries.length ||
      (batch.publish && !/^[0-9a-f]{64}$/.test(batch.publicationScope?.digest ?? ''))) {
    throw new Error('Batch must have its original product, selections and publication scope');
  }
  const heads = batch.deliveries.map(deliveryId => {
    const matching = batch.entries.filter(entry => entry?.id === deliveryId);
    const entry = matching[0];
    const head = entry?.executionHead ?? entry?.head;
    if (!uuid.test(deliveryId) || matching.length !== 1 || entry.repository !== repository ||
        entry.targetBranch !== 'main' || !Number.isSafeInteger(entry.pullRequest) || entry.pullRequest <= 0 || !sha.test(head ?? '')) {
      throw new Error('Batch entry does not match the Hermes repository, target or exact execution head');
    }
    return { deliveryId, head };
  });
  return { batchId: batch.id, heads,
    scope: digest([batch.selection, batch.operation ?? null, batch.publish, batch.publicationScope?.digest ?? null]),
  };
}

export function comparePublicationScope(batch, snapshot) {
  const scope = batch.publicationScope;
  if (!scope || !Array.isArray(scope.candidates) || !Array.isArray(scope.exclusions) || !Array.isArray(scope.blockers)) {
    throw new Error('Frozen publication scope is incomplete');
  }
  // A selected, still-unmerged delivery need not appear among release candidates yet.
  const selected = new Set(batch.deliveries);
  const frozen = new Map(scope.candidates.map(row => [row.identity, row]));
  if (frozen.size !== scope.candidates.length) throw new Error('Frozen scope repeats a candidate');
  const current = new Set();
  const differences = [];
  for (const candidate of snapshot.candidates) {
    const identity = candidateIdentity(candidate);
    current.add(identity);
    const row = frozen.get(identity);
    if (!row || row.expectedRevision !== candidate.expectedRevision ||
        digest(row.requiredArtifacts ?? null) !== digest(candidate.requiredArtifacts ?? null)) differences.push(identity);
  }
  for (const row of frozen.values()) {
    const inBatch = row.selected && selected.has(row.deliveryId);
    if (!inBatch && !current.has(row.identity)) differences.push(row.identity);
    if (inBatch) {
      const entry = batch.entries.find(candidate => candidate.id === row.deliveryId);
      const selection = batch.selection.find(candidate => candidate.id === row.deliveryId);
      if (!entry || row.expectedRevision !== selection?.revision ||
          digest(row.requiredArtifacts ?? null) !== digest(entry.requiredArtifacts ?? null)) differences.push(row.identity);
    }
  }
  const frozenExclusions = new Map(scope.exclusions.map(row => [row.identity, row.reason]));
  const currentExclusions = new Map([
    ...snapshot.noReleaseItems.map(row => [row.itemKey, row.noReleaseReason]),
    ...snapshot.noReleaseDeliveries.map(row => [`delivery:${row.deliveryId}`, row.noReleaseReason]),
  ]);
  for (const identity of new Set([...frozenExclusions.keys(), ...currentExclusions.keys()])) {
    if (frozenExclusions.get(identity) !== currentExclusions.get(identity)) differences.push(identity);
  }
  return { matches: differences.length === 0 && scope.blockers.length === 0, differences, blockers: scope.blockers };
}
