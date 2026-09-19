/**
 * What to do with a frame from the local Hermes that is too large to relay.
 *
 * Split out of `index.ts` so it can be tested: that module opens sockets and reads the
 * environment at import time.
 */

/**
 * JSON-RPC's reserved implementation-defined range (-32000 to -32099). Upstream Hermes numbers its
 * own errors in the positive 4xxx/5xxx space, so a code from here cannot collide with one it mints
 * later — which matters because we do not own those numbers and cannot version-negotiate them
 * (docs/HERMES_CONTRACT.md).
 */
export const OVERSIZED_FRAME_RPC_CODE = -32001;

/** Only this much of the frame is scanned for the id; a response's envelope is at its front. */
const ID_SCAN_BYTES = 4096;

export interface OversizedFrameDecision {
  /** Whether the frame may be relayed to the app. */
  forward: boolean;
  /** A synthetic JSON-RPC error to send in its place, or null when the caller can only drop it. */
  replacement: string | null;
  /** The request id the dropped frame was answering, when it could be read. */
  rpcId: number | null;
}

/**
 * Read the `id` out of a JSON-RPC response without parsing the whole frame.
 *
 * Parsing 26 MiB of JSON to learn one integer is exactly the cost this path exists to avoid, and
 * the envelope (`{"jsonrpc":"2.0","id":123,...`) is at the front by construction.
 */
export function readRpcId(frame: Buffer): number | null {
  const head = frame.subarray(0, Math.min(frame.length, ID_SCAN_BYTES)).toString("utf8");
  const match = /"id"\s*:\s*(-?\d{1,15})/.exec(head);
  if (!match) return null;
  const id = Number(match[1]);
  return Number.isSafeInteger(id) ? id : null;
}

/**
 * Decide what the app gets when Hermes answers with more than the relay can carry.
 *
 * Before this, an oversized frame killed the socket: `ws` treats a `maxPayload` violation as a
 * protocol error and destroys the connection, so ONE conversation whose transcript had grown past
 * the ceiling took down the whole tunnel — new conversations, the stop button, every other
 * conversation's history — and did it again on every reconnect (HG-65, HG-64: a 26.3 MiB
 * `session.resume` answer against a 12 MiB ceiling, 197 closes in 24 minutes).
 *
 * Now the frame is received and dropped, the tunnel lives, and the call that asked for it gets an
 * answer instead of hanging until its 60s timeout. Everything else on that tunnel keeps working.
 */
export function decideOversizedFrame(
  frame: Buffer,
  limitBytes: number,
): OversizedFrameDecision {
  if (frame.length <= limitBytes) return { forward: true, replacement: null, rpcId: null };
  const rpcId = readRpcId(frame);
  if (rpcId === null) {
    // An event rather than a response, or an envelope we cannot read: there is nothing to answer,
    // so the frame is dropped on its own. The tunnel still survives, which is the point.
    return { forward: false, replacement: null, rpcId: null };
  }
  const replacement = JSON.stringify({
    jsonrpc: "2.0",
    id: rpcId,
    error: {
      code: OVERSIZED_FRAME_RPC_CODE,
      message:
        `response is ${frame.length} bytes, over the ${limitBytes}-byte relay frame limit; ` +
        "it was not delivered",
    },
  });
  return { forward: false, replacement, rpcId };
}
