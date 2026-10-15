// P0 executable specification only. Never imported by a service or client.
// The trusted observation issuer below is a test seam, not an implemented Hermes adapter.
import {isDeepStrictEqual} from 'node:util';

const VERSION = /^(0|[1-9][0-9]{0,19})$/;
const MAX_VERSION = (1n << 64n) - 1n;
export function revision(value) {
  if (typeof value !== 'string' || !VERSION.test(value) || BigInt(value) > MAX_VERSION) {
    throw new Error('invalid_revision');
  }
  return BigInt(value);
}

function nextRevision(value) {
  if (value >= MAX_VERSION) throw new Error('revision_exhausted');
  return value + 1n;
}

function text(value) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 256 && !value.includes('\0');
}

function keys(value, expected) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      !isDeepStrictEqual(Object.keys(value).sort(), [...expected].sort())) throw new Error('invalid_shape');
}

export function entityKey(entity) {
  if (entity?.kind === 'favorite') {
    keys(entity, ['kind', 'provider', 'model']);
    if (!text(entity.provider) || !text(entity.model)) throw new Error('invalid_entity');
    return JSON.stringify(['favorite', entity.provider, entity.model]);
  }
  keys(entity, ['kind', 'deviceId', 'profile', 'sessionId', 'sessionEpoch']);
  if (!['pin', 'read'].includes(entity.kind) ||
      ![entity.deviceId, entity.profile, entity.sessionId, entity.sessionEpoch].every(text)) {
    throw new Error('invalid_entity');
  }
  // Default normalization belongs at the upstream adapter; no case folding/trim/alias guessing.
  return JSON.stringify([entity.kind, entity.deviceId, entity.profile, entity.sessionId, entity.sessionEpoch]);
}

export function parseMutation(input) {
  const read = input?.entity?.kind === 'read';
  keys(input, read
    ? ['opId', 'entity', 'accessGeneration', 'observation']
    : ['opId', 'entity', 'accessGeneration', 'baseRevision', 'value']);
  if (!text(input.opId)) throw new Error('invalid_op_id');
  entityKey(input.entity);
  revision(input.accessGeneration);
  if (read) {
    if (!text(input.observation)) throw new Error('invalid_observation');
  } else {
    revision(input.baseRevision);
    if (typeof input.value !== 'boolean') throw new Error('invalid_value');
  }
  if (input.entity.kind === 'favorite' && input.accessGeneration !== '0') throw new Error('invalid_generation');
  return structuredClone(input);
}

/** In-memory oracle for contract traces. No HTTP, disk, SQL, timers, credentials or production imports. */
export class SyncContractModel {
  accounts = new Map();
  grants = new Map();
  observations = new Map();

  account(principal) {
    keys(principal, ['origin', 'accountId']);
    if (!text(principal.origin) || !text(principal.accountId)) throw new Error('invalid_principal');
    const key = JSON.stringify([principal.origin, principal.accountId]);
    if (!this.accounts.has(key)) this.accounts.set(key, {revision: 0n, accessRevision: 0n, rows: new Map(), log: [], ops: new Map()});
    return [key, this.accounts.get(key)];
  }

  access(principal, deviceId, active) {
    if (!text(deviceId) || typeof active !== 'boolean') throw new Error('invalid_access');
    const [accountKey, account] = this.account(principal);
    const key = JSON.stringify([accountKey, deviceId]);
    const previous = this.grants.get(key);
    if (previous?.active === active) return previous.generation.toString();
    const generation = nextRevision(previous?.generation ?? 0n);
    const accessRevision = nextRevision(account.accessRevision);
    this.grants.set(key, {active, generation});
    account.accessRevision = accessRevision;
    return generation.toString();
  }

  authorized(accountKey, entity, generation) {
    if (entity.kind === 'favorite') return generation === '0';
    const access = this.grants.get(JSON.stringify([accountKey, entity.deviceId]));
    return access?.active === true && access.generation.toString() === generation;
  }

  rowKey(entity, generation) { return JSON.stringify([entityKey(entity), generation]); }

  // Trusted adapter seam. The model assumes this exact version was rendered; it cannot prove that.
  observe(principal, entity, accessGeneration, through) {
    entityKey(entity);
    if (entity.kind !== 'read') throw new Error('invalid_observation_scope');
    revision(through);
    revision(accessGeneration);
    const [accountKey] = this.account(principal);
    if (!this.authorized(accountKey, entity, accessGeneration)) throw new Error('denied');
    const token = `fixture-observation-${this.observations.size + 1}`;
    this.observations.set(token, {accountKey, entity: structuredClone(entity), accessGeneration, through});
    return token;
  }

  mutate(principal, input) {
    const request = parseMutation(input);
    const [accountKey, account] = this.account(principal);
    // Recheck current authorization before idempotency lookup: cached success is not a permission.
    if (!this.authorized(accountKey, request.entity, request.accessGeneration)) return {status: 'denied'};
    const cached = account.ops.get(request.opId);
    if (cached) return isDeepStrictEqual(cached.request, request)
      ? structuredClone(cached.result) : {status: 'op_mismatch'};
    const key = this.rowKey(request.entity, request.accessGeneration);
    const previous = account.rows.get(key);
    const remember = (result) => {
      account.ops.set(request.opId, {request, result: structuredClone(result)});
      return result;
    };
    let value = request.value;
    if (request.entity.kind === 'read') {
      const proof = this.observations.get(request.observation);
      if (!proof || proof.accountKey !== accountKey || proof.accessGeneration !== request.accessGeneration ||
          !isDeepStrictEqual(proof.entity, request.entity)) return remember({status: 'invalid_observation'});
      value = (revision(proof.through) > revision(previous?.value ?? '0') ? proof.through : previous?.value ?? '0');
      if (previous && value === previous.value) return remember({status: 'accepted', revision: previous.revision, value});
    } else if (request.baseRevision !== (previous?.revision ?? '0')) {
      return remember({status: 'conflict', current: previous ? structuredClone(previous) : null});
    }
    account.revision = nextRevision(account.revision);
    const row = {entity: request.entity, accessGeneration: request.accessGeneration,
      revision: account.revision.toString(), value};
    // false is retained as a tombstone. It is not absence and cannot be re-created at base 0.
    account.rows.set(key, row);
    account.log.push(structuredClone(row));
    return remember({status: 'accepted', revision: row.revision, value});
  }

  snapshot(principal) {
    const [accountKey, account] = this.account(principal);
    return {cursor: {scope: accountKey, revision: account.revision.toString(), accessRevision: account.accessRevision.toString()},
      rows: structuredClone([...account.rows.values()].filter((r) => this.authorized(accountKey, r.entity, r.accessGeneration)))};
  }

  changes(principal, cursor, scanLimit) {
    keys(cursor, ['scope', 'revision', 'accessRevision']);
    const after = revision(cursor.revision);
    revision(cursor.accessRevision);
    if (!Number.isInteger(scanLimit) || scanLimit < 1 || scanLimit > 200) throw new Error('invalid_limit');
    const [accountKey, account] = this.account(principal);
    if (cursor.scope !== accountKey || cursor.accessRevision !== account.accessRevision.toString() || after > account.revision) {
      return {status: 'reset_required'};
    }
    const scanned = account.log.filter((r) => revision(r.revision) > after).slice(0, scanLimit);
    const end = scanned.at(-1)?.revision ?? cursor.revision;
    return {status: 'page', rows: structuredClone(scanned.filter((r) => this.authorized(accountKey, r.entity, r.accessGeneration))),
      nextCursor: {scope: accountKey, revision: end, accessRevision: cursor.accessRevision},
      hasMore: revision(end) < account.revision};
  }
}

/** Capture the approved first-install policy after the server supplies valid content baselines. */
export function initialReadState(baselines, knownUnread) {
  const rows = new Map();
  for (const {entity, through} of baselines) {
    if (entity.kind !== 'read') throw new Error('invalid_baseline');
    const key = entityKey(entity);
    revision(through);
    if (rows.has(key)) throw new Error('duplicate_baseline');
    rows.set(key, {entity, baselineThrough: through, legacyUnread: knownUnread.has(key)});
  }
  // Unknown legacy tokens are quarantined locally, never guessed into another Mac/account.
  return {rows: [...rows.values()], unmapped: [...knownUnread].filter((key) => !rows.has(key))};
}

export function isUnread({baselineThrough, legacyUnread}, latest, readThrough = null) {
  const baseline = revision(baselineThrough);
  const read = revision(readThrough ?? '0');
  const head = revision(latest);
  return head > (read > baseline ? read : baseline) || (legacyUnread && (readThrough === null || read < baseline));
}
