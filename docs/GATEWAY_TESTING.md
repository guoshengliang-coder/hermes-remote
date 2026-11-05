# Gateway test isolation and process exit

Run the Node baseline from the repository root with `npm run test:ci`. Set `RUN_NETWORK_TESTS=1`
to include the opt-in loopback integration tests. Database cases additionally require
`ACCOUNT_TEST_DATABASE_URL` pointing to a disposable PostgreSQL instance; never use a live database.

After building, a focused run is:

```sh
cd gateway
node --test dist/doubao-voice-proxy.test.js dist/doubao-voice-proxy-deadline.test.js dist/doubao-voice-proxy-exit.test.js
```

## Why the voice deadline case is in its own file

On 2026-11-05, a file-by-file run of all 60 original Gateway test files isolated an intermittent
30-second suite tail to `doubao-voice-proxy.test.ts`. All four voice assertions completed in
milliseconds, yet that file took 30.151 seconds to exit. Each case run alone exited immediately.

The definitive-revocation case initiates a real WebSocket close handshake. A `WebSocketServer.close`
callback for its internally created HTTP server can complete before all upgraded WebSockets have
emitted their own `close` events. The following deadline case then enabled global MockTimers.
Tracing showed the earlier server WebSocket's 30,000ms native close timer created under real timers,
then its `close` handler executing with mocked `clearTimeout`. The real timer remained referenced
even though the WebSocket and underlying socket were already closed. It kept the test process alive.

The deadline case now lives in `doubao-voice-proxy-deadline.test.ts`. Node's default per-file process
isolation gives the mock timer and real-socket cases independent globals; see the
[Node 22 execution model](https://nodejs.org/download/release/v22.15.0/docs/api/test.html#test-runner-execution-model).
Keep that isolation enabled. The original authentication, binary relay, credential privacy,
session-specific revocation, immediate liveness recheck, 90-second survival, 330-second limit,
close-code, upstream-close and capacity assertions remain intact.

`doubao-voice-proxy-exit.test.ts` starts the real-socket test file directly in one child process and
requires it to exit naturally within five seconds, with its named tests passing and no cancellations.
The budget has ample scheduling headroom for the millisecond fixtures while detecting the 30-second
leak. The parent removes inherited `NODE_TEST_CONTEXT` for a normal standalone run. If the budget
fails, the timeout terminates only that fixture process; no forced exit makes a successful test pass.
Do not add `--test-force-exit`, shorten ws's close timeout, skip assertions or change production
voice deadlines to hide an exit leak. Production Gateway behavior and user-visible errors are not
changed by this test-only fix.

## Local evidence

Host: macOS arm64, Node v22.23.2. The new exit regression was run before the split: it failed at
5.010 seconds because the original voice fixture process remained alive. After the split, the
same regression and all four original voice behaviors passed together: five tests, zero failures,
about 0.095 seconds for that focused run.

Three runs of the complete built Gateway suite, with database/network opt-ins absent as in the
macOS component candidate job, took **2.168 / 1.599 / 1.947 seconds** wall time. Every run had
236 passed / 32 conditionally skipped / zero failures. The passing count grew by one exit regression;
none of the original four voice behaviors was removed. These local numbers are not a percentage
claim for the complete GitHub merge or Android publication pipeline.

`RUN_NETWORK_TESTS=1 npm run test:ci` also passed: Protocol 27, Connector 176, Gateway 248 plus
20 database-dependent skips, release-server 41, and scripts 444 plus one database-dependent skip
(936 passed / 21 skipped / zero failures overall). PostgreSQL was unavailable locally; the normal
PR CI provides its disposable PostgreSQL/network gate. No native client, signing, version bump or
production verification is part of this change.
