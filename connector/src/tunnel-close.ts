/**
 * What the app should be told when a tunnel's local Hermes socket dies.
 *
 * Split out of `index.ts` so it can be tested: the module it came from opens sockets and reads the
 * environment at import time.
 */
export interface TunnelCloseForApp {
  code: number;
  reason: string;
}

/** `ws`'s message for a frame past `maxPayload`. Matched loosely; it is a library string. */
const OVERSIZED_FRAME = /max payload/i;

/**
 * Translate a local close into one the app can act on.
 *
 * The case this exists for: Hermes answers `session.resume` with a frame larger than the local
 * socket's `maxPayload`, `ws` raises "Max payload size exceeded" and destroys the socket. The close
 * that follows carries 1006 with no reason — and 1006 is precisely the code the gateway refuses to
 * forward (`safeCloseCode`), so the phone received a bare 1011 and could not tell this from a
 * network drop. It reconnected, resumed the same conversation, got the same oversized frame, and
 * did that 197 times in 24 minutes while every other feature sharing the tunnel — new conversation,
 * history, stop — died with it (HG-65, HG-64).
 *
 * 1009 is the standard "message too big" and, unlike 1006, survives the relay. Retrying still will
 * not help, but at least it is now a fact the app can name instead of an anonymous failure it is
 * right to retry.
 */
export function tunnelCloseForApp(
  localCode: number,
  localReason: string,
  localError: string | undefined,
  maxPayloadBytes: number,
): TunnelCloseForApp {
  if (localError !== undefined && OVERSIZED_FRAME.test(localError)) {
    return { code: 1009, reason: `local frame exceeds ${maxPayloadBytes} bytes` };
  }
  return { code: localCode, reason: localReason };
}
