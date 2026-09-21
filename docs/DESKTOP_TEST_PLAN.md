# Hermes Go Desktop test plan

## Automated baseline

Run on macOS:

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
```

The current automated suite covers:

- overall status reduction and optional-capability degradation;
- legacy Connector config parsing with a strict non-secret allowlist;
- current Gateway WSS to Relay health URL conversion;
- legacy launchd/install/log discovery through injected command execution;
- log/header/query/known-secret redaction;
- profile validation that rejects insecure remote HTTP, URL credentials, queries, and fragments;
- that the staged Hermes component is importable by a **child** process with no `PYTHONPATH`
  (`scripts/test/desktop-managed-python-path.test.mjs`) — upstream strips the repo root out of every
  child environment, so a bundle that relies on `PYTHONPATH` alone cannot run slash commands;
- exact Android v1 JSON payload compatibility;
- Core Image QR output decoded back to the exact v1 payload with the native QR detector, including payload-size rejection;
- authenticated `/api/status` request path and header behavior;
- 401/403, offline Connector, and unmapped Relay error classification;
- bilingual error code, retryability, recovery-action, and diagnostic redaction contracts;
- recent-log warning counting independent of current health;
- full SwiftUI target compilation.
- Desktop account capability discovery and default-off behavior;
- PKCE S256, cryptographic state/nonce, provider cancellation, state mismatch rejection, and a real
  ephemeral `127.0.0.1` loopback callback;
- account HTTP paths/headers, bounded responses, stable error mapping, and diagnostic redaction;
- email sign-in challenge/exchange paths, normalized email, stable installation identity and
  exchange idempotency, macOS session validation, and bilingual `HR-AUTH-009` through
  `HR-AUTH-011` recovery contracts;
- email-only gray-rollout capability gating: after a successful exchange, disabled installation and binding routes
  are not requested, the session remains stored, and the account summary reaches the signed-in state;
- separate account-session and Connector-machine identity stores, Ed25519 challenge signing, access
  refresh, and persisted idempotency-key reuse after a lost refresh response;
- signed-in dashboard reduction, two-phone listing, current-account-email reauthentication before
  one-phone removal, rejection of non-phone targets, and Desktop-only sign-out without deleting the
  machine identity;
- bounded phone-revocation request bodies, scoped `account.installation.revoke` grants,
  Keychain-preserved exact retry after a lost response, and bilingual `HR-ACCOUNT-009` mapping;
- capability-gated permanent Cloud deletion, exact `DELETE /v2/account` body/headers,
  `account.delete` email reauthentication, explicit acknowledgement, Keychain-preserved exact retry
  after a lost response, account-session removal on success, and Connector machine-identity
  preservation;
- capability-gated whole-device sharing routes and decoding, owned/operator device separation,
  owner-only share lookups, exact disclosure acknowledgement, invite-link validation, bilingual
  `HR-SHARE-001` through `HR-SHARE-008` mapping, and credential redaction;
- fresh-email-code `device.share` reauthentication plus Keychain-preserved idempotency/grant recovery
  after lost reauthentication or invitation responses, without persisting the six-digit code;
- strict signed Desktop manifest parsing, pinned Ed25519 keys, tamper/expiry/origin/architecture and
  unknown-field rejection;
- schema-v2 component manifests pin compressed and extracted identities, activation/reuse contracts,
  entrypoints and content-addressed dependencies; Swift and the offline verifier reject unknown or
  duplicate components, dependency identity mismatch, cycles, and invalid compatibility fallbacks;
- resumable v2 component downloads require exact 206/Content-Range semantics, retain only an
  owner-only partial file after interruption, re-read the whole completed file for signed size and
  SHA-256, and remove a poisoned full-size partial;
- offline managed-release publication with owner-only Ed25519 key custody, exact two-archive output,
  independent public-key verification, no-overwrite/partial-cleanup behavior, and redacted
  `HR-RELEASE-004` diagnostics;
- component-archive construction from exact clean Git identities, an allowlisted secret-free Hermes
  source/runtime boundary, production-only Connector JavaScript, bundled architecture-matched
  runtimes, relative launchers, bounded trees, and no-overwrite cleanup;
- schema-v2 component archive construction keeps CPython/bootstrap dependencies, Hermes core, Node,
  Connector JavaScript, and any prepared browser/speech/document roots in disjoint archives; validates
  clean source commits, runtime versions, architectures, executable optional health entrypoints, fixed
  optional dependency edges, and rejects Homebrew/unresolved dynamic-library dependencies; reports
  compressed and normalized content hashes plus separate bootstrap/deferred bytes; removes only artifacts created by
  a failed attempt; and preserves HG-28 child imports through the activation-time core-root `.pth`;
- the arm64 core-only composition starts the real Hermes 0.21.0 server, emits its readiness marker,
  serves `/api/status`, imports its core API modules, and imports `tui_gateway` from a child interpreter
  after `PYTHONPATH` is removed; the measured bootstrap set is 98.12 MiB compressed;
- schema-v2 activation rehashes all four bootstrap components and their owner-only receipts, requires
  executable owner-safe entrypoints and successful bounded health probes, and rejects missing
  components, unexpected bootstrap kinds, or any deviation from the exact runtime dependency graph;
- component LaunchAgents use content-addressed Hermes/Connector entrypoints and inject only the exact
  Python/Node managed roots; v1 writers reject v2 runtime injection and v2 writers reject paths from a
  different managed layout or content mutated after planning;
- the v2 component installer accepts only a strict-verifier installation token, orders fresh
  Python/Node/Hermes/Connector acquisition by dependencies, reuses a second exact install without
  network access, and publishes the release reference only after the final activation plan passes;
- v2 component preparation downloads and extracts only into its private workspace, leaves the managed
  store and release reference absent before commit, binds commit/cancel to the issuing installer,
  rehashes staged content after the confirmation boundary, and publishes no reference after tampering;
- extraction or health failure removes only the current UUID workspace and leaves no release
  reference; a transport interruption instead preserves its partial download and owner-only manifest
  identity marker, clears completed archives/extractions before retry, allows only the same signed
  manifest and run ID to resume, and lets the bounded cancel path remove that exact marked workspace;
- the component preflight coordinator reports all bootstrap downloads and deferred optional bytes on
  a clean store, reuses only rehashed healthy managed content, permits a compatible probed external
  browser while keeping observed Python non-reusable, and stops before external scanning for an
  unhealthy managed component or invalid signed bootstrap topology;
- the component preflight presentation maps managed/system reuse and bootstrap/on-demand downloads to
  distinct bilingual rows, reports stable binary byte totals, names every supported component kind,
  and compiles as a native SwiftUI card without exposing an unwired install action;
- offline component-release publication safely extracts every candidate and recomputes the same
  relative-path/file-byte/executable-bit identity used by the Desktop store before signing, then the
  public-key-only verifier repeats compressed and extracted identity checks;
- componentized-install preflight planning: exact content identity is required for mutable runtimes,
  a version-only external Python is rejected, an explicitly compatible healthy system browser may be
  reused, wrong architecture/digest/failed probes are rejected, optional capabilities are deferred,
  and only missing bootstrap components contribute to the initial download total;
- compatibility requirements retain an exact managed fallback identity, allowing a downloaded optional
  component to be reused after external compatibility disappears;
- shared-component inventory rehashes relative paths, bytes, and executable bits before reuse;
  stable trees produce stable identities, byte/mode changes invalidate them, and symbolic links,
  unsafe receipt permissions, receipt mismatches, and tampered trees fail closed;
- shared-component commit stages content plus its receipt under one private UUID, rehashes and probes
  the staged copy, atomically exposes both together, is idempotent when exact content already exists,
  records deterministic release references, and removes only the current workspace on probe failure;
- optional-component references can be recorded only for browser, speech, or document content that
  already exists under a valid base release; exact retries are idempotent and a conflicting identity
  for the same release/kind is rejected;
- first-use component installation requires a verifier-only v2 token and an exact signed trigger,
  validates the base reference and all bootstrap health before scanning or network access, installs
  optional dependencies in topological order, reuses managed content or a revalidated system browser,
  publishes capability references only for managed content, resumes only a manifest/trigger-bound
  transport interruption, and safely cleans extraction failures or explicitly discarded workspaces;
- optional runtime projection probes the exact managed Python ABI, sorts multiple signed Python
  component roots into one content-addressed read-only `.pth` target, is idempotent for identical
  input, injects the target and browser executable through the upstream Hermes environment contract,
  and rejects external Python roots, duplicates, symlinks, unsafe permissions, failed probes, or a
  mutated existing projection;
- first-use runtime activation shares the migration operation lock, requires the exact committed
  account release and healthy managed service topology, replaces only the Hermes LaunchAgent,
  restarts and proves the new Hermes runtime, restores the exact previous plist and re-proves the old
  runtime on failure, and invokes the capability retry exactly once without rolling back a healthy
  activation when that retry itself fails;
- active optional-component resolution preserves previously referenced managed capabilities across a
  later independent trigger, revalidates each identity, receipt, content tree, entrypoint, and health
  probe against the signed release, and retains a current external browser only when its exact
  owner-only LaunchAgent path passes a fresh signed-compatibility scan;
- the default-off capability coordinator maps only browser/speech/document kinds to exact triggers in
  the verifier-backed manifest, orders install → bootstrap-plan regeneration → locked activation →
  one retry, rejects unsupported kinds before work begins, stops after install failure, and admits
  only one concurrent request;
- the default-off preflight runtime accepts only a fixed unambiguous HTTPS manifest URL, orders bounded
  manifest fetch → strict schema-v2 signature verification → read-only environment scan, prevents
  download/signature failures from reaching the scanner, and rejects overlapping refreshes;
- the trusted preflight session retains the exact verifier-only schema-v2 token beside the matching
  scanned presentation result, while the compatibility load path exposes only the ordinary result;
- the component bootstrap state machine rejects mismatched trusted input before cache/network work,
  writes only the private preparation before exact release confirmation, rejects foreign preparation
  handles, and then passes the committed content-addressed activation plan with exact Python/Node roots
  to one migration boundary; cancellation and migration failure remove the UUID workspace, inactive
  immutable references remain reusable/collectable, and post-success cleanup failure returns a bounded
  retry handle without reclassifying the committed migration; a transport interruption before handle
  issuance is removable only by the UUID-and-private-marker checked cleanup path;
- migration-journal schema 2 binds each run to bundled-release or component-store layout across every
  state transition, rejects a same-run layout change and malformed/unknown schema-layout combinations,
  reads historical schema 1 strictly as bundled-release, and atomically upgrades that legacy record on
  its next valid transition;
- the concrete component migration adapter rejects manifest/activation-plan mismatch before binding or
  filesystem mutation, starts the content-addressed Hermes entrypoint before Connector, commits only
  after local and cloud health, persists `component_store`, injects the exact Python/Node roots, and
  neither creates a bundled version directory nor changes `current`; failure and interrupted recovery
  restore legacy service state while preserving an unrelated bundled `current`, and the existing bundled
  recovery test still proves that a v1 candidate deactivates its expected link;
- the composed schema-v2 bootstrap runtime binds the trusted preflight, component installer, migration
  journal/controller and content-addressed commit configuration to the same managed paths and account
  boundary; construction preserves an absent managed root, cache, LaunchAgents directory, and Hermes
  home, proving that merely enabling dependency composition cannot start a download or mutate the Mac;
- the component-preflight package gate is false with an empty v2 URL by default, loads only a complete
  HTTPS URL plus canonical pinned release trust inputs, keeps the schema-v1 URL separate, and rejects
  partial, malformed, credential-bearing, or ambiguous configuration;
- the read-only managed-entrypoint probe requires the signed entrypoint to stay inside the rehashed
  component root as an owned, non-writable, regular executable file, rejecting paths outside the root,
  symlinks, non-executable files, and group-writable files without launching a process;
- fixed-path external-environment scanning reports Python/Node without trusting version-only reuse,
  resolves Homebrew-style runtime symlinks for reporting only, admits a safe architecture-matched
  system browser only after an explicit compatibility probe, and rejects browser symlinks,
  group-writable executables, failed/oversized probes, and architecture mismatch;
- read-only component garbage-collection planning retains every bootstrap and optional capability
  identity, permits identical optional content to be shared across base releases, requires current and
  rollback reference sets supplied by the caller, validates component content again, and fails closed
  on orphaned/unsafe references, unknown kinds, missing components, symlinks, or tampered content;
- redirect-free bounded downloads, exact size and streaming SHA-256 artifact validation;
- tar member preflight, including traversal/link/special-file rejection before extraction, realistic
  dependency trees above the former 4,096-entry limit, and rejection beyond the new 65,536 bound;
- private credential/LaunchAgent writes, immutable release staging, atomic activation and rollback;
- a rolled-back, app-marked inactive release can be atomically replaced by a freshly verified
  same-version retry, while active, unmarked, mismatched, or unsafe directories remain immutable;
- a terminal rollback can resume an already committed Cloud binding only when its recorded ID,
  generation, and retained machine-key fingerprint all match; it performs no replacement or second
  remote confirmation, while every mismatch remains blocked;
- acquisition ordering and lifecycle: private roots exist before verifier pinning, both signed
  components are required, digest/extraction failures remove only the current UUID workspace, and
  explicit discard preserves the parent workspace;
- managed-bootstrap preparation performs acquisition only and returns the exact signed release plus
  confirmation; commit rejects wrong or foreign preparations, while cancel and terminal paths discard
  the private workspace;
- launch configuration is derived from verified manifest entrypoints, the managed layout, frozen
  runtime, and HTTPS account origin rather than caller-supplied executable paths;
- committed cleanup trouble retains a one-purpose retry handle, maps to `HR-MIGRATE-005`, and neither
  claims rollback nor offers a second install;
- exact legacy/account user LaunchAgent labels, duplicate-Connector prevention, health-gated binding
  confirmation, lost-response idempotency, automatic rollback, ambiguous-commit stop, and restart
  recovery;
- persistent legacy-label disablement before managed startup, matching re-enablement before rollback,
  and startup repair that suppresses a Migration Assistant-restored duplicate only when an exact
  `account_active` journal and both managed services prove the committed authority;
- bounded launchd convergence after every managed bootstrap/bootout, including delayed legacy
  removal before managed startup or rollback decisions;
- signed Hermes entrypoint-only plist generation, separate exact Hermes/Connector labels, Hermes-first
  startup, process-specific post-checkpoint ready evidence plus loopback health, bounded/symlink-safe
  log reads, Connector suppression on Hermes timeout, and reverse-order rollback;
- default-off packaged bootstrap configuration, strict HTTPS/key/channel/architecture validation,
  exact `hermes-serve-v1` loopback arguments, Desktop marker, private session-token file path and
  sentinels, absent/mismatched Gateway capability rejection, and readiness only when both gates match;
- private installation-local Hermes token creation/reuse, unsafe file rejection, no token value in
  either LaunchAgent, signed-wrapper file validation, and Connector file loading of both canonical
  43-character base64url and historical 64-character lowercase-hex values with no symlink or
  group/world-readable fallback;
- committed pre-contract token migration preserving the existing 64-character local credential,
  removing both supported inline field names, writing only the canonical `0600` token file reference,
  and recording completion only after ordered service restart plus local health and an exact bound
  Cloud health timestamp strictly newer than the pre-Connector-start checkpoint;
- managed release 0.3.0 compatibility: startup returns before account refresh, file mutation, or
  service restart because its packaged Connector does not consume `HERMES_SESSION_TOKEN_FILE`; 0.3.1
  is the first immutable managed release admitted to the token-file migration;
- power-loss resume from a matching half-migrated plist pair, completed-state idempotency, rejection
  of mismatched inline values or account binding generation before mutation, and injected readiness
  or stale-Cloud-health failure that restores the exact old plist/token bytes and proves the restored
  running service pair with a fresh Cloud timestamp;
- an existing responder on reserved port 9119, including 401/403, blocks clean install;
- packaged ATS configuration explicitly permits local-network health probes while leaving arbitrary
  public and WebView HTTP loads disabled;
- candidate Hermes readiness uses a dedicated ephemeral session that explicitly disables HTTP, HTTPS,
  SOCKS, FTP, PAC, and automatic proxy discovery, so configured proxies cannot intercept or stall the
  `127.0.0.1:9119` commit gate;
- the production migration coordinator passes a 75-poll window to candidate readiness, covering the
  measured 35-second physical-Mac cold start while tests can still inject shorter deterministic limits;
- an active managed release exposes upgrade only for a strictly newer signed target; preparation
  routes to upgrade for both schema-v1 and schema-v2, while same-version/downgrade inputs fail before
  service mutation;
- managed upgrade never invokes binding creation/confirmation, preserves the exact binding ID and
  generation, stops Connector before Hermes, waits for the old 9119 listener to disappear, then starts
  Hermes before Connector and requires a strictly newer Cloud health timestamp;
- candidate failure restores byte-identical LaunchAgents, the previous bundled `current` pointer,
  both old services and the old `account_active` journal; restart recovery uses the same durable
  snapshot and leaves manual attention only when that restoration cannot be proved;
- restart inspection recognizes only an `account_active` journal plus both exact managed LaunchAgents
  as active; intermediate journals recover before a second install and mismatches fail closed;
- overview Agent reduction prefers that exact active managed installation over a stopped legacy
  label, reports a transferred managed service as running-but-unverified until account sign-in, and
  fails closed when the signed-in binding ID or generation differs;
- a newly confirmed run atomically replaces only a terminal `legacy_active` or `clean_uninstalled`
  rollback journal; intermediate, active, and manual-attention journals reject a different run ID;
- a revoked pending binding is recreated only when its generation matches the same Desktop's terminal
  `legacy_active`/`clean_uninstalled` rollback journal; missing or mismatched proof fails closed with
  `HR-BIND-006`, remains visible through the migration presentation boundary, and does not call binding
  creation;
- existing-install observation/recovery remains available when new-install rollout is disabled, and
  active state must match the current account's exact binding ID/generation before it is claimed;
- a recognized running legacy Connector uses its own configured Hermes status URL for the final
  migration preflight and can enter the signed two-stage migration only while that health check and
  the complete managed-install capability agree; stopped/unhealthy or unsigned cases stay read-only;
- bilingual `HR-MIGRATE-001` through `HR-MIGRATE-005` terminal-state mapping.
- local Hermes runtime (`DesktopLocalHermesTests`, `DesktopHermesRuntimeCoordinatorTests`):
  detection of the standard layout from git files alone (loose ref, `packed-refs`, detached `HEAD`)
  and of every surfaced shape — profiles, non-default `active_profile`, `HERMES_HOME` in the
  environment or an owner `ai.hermes.*` agent, an owner agent outside the checkout, a foreign
  entrypoint or pipx install, missing venv, group-writable entrypoint, version below 0.21.3,
  unreadable `HEAD`; upstream's update lock honoured only within its 20-minute ceiling; the planner's
  switch/keep/wait/restart/restore/surface table including the `hermes update` self-restart and the
  `git pack-refs` no-restart cases; the launcher **executed** against a stand-in Hermes (token handed
  over, `PYTHONPATH` cleared, unsafe token file, malformed token and foreign argv refused before
  exec); the exact local-mode `ProgramArguments`/environment and that `shlex.join` of them contains
  `hermes serve`; switch restarts only Hermes and keeps the exact bundled agent; a failed switch
  restores both files and proves the old server; restart on code change; no restart for a process
  started after the update; restore on removal and on turning the setting off; nothing mutated
  when disabled, unsupported or uninstalled; a managed upgrade keeps the local agent; the startup
  token/PATH reconcilers are inert in local mode; `HR-MIGRATE-006` is silent in local mode; the
  fresh-install gate; `HR-MIGRATE-008`/`009` bilingual contract and redaction; source wiring of the
  refresh call, both install gates, and the window card. Review fixes (each checked to fail when its
  fix is reverted): a failed return to bundled restores and proves the intact local Hermes, or keeps
  the bundled agent loaded when local is gone; a failed `bootstrap` during a switch or restart leaves
  the job loaded, and an unloaded local job is started again; an upgrade in local mode refreshes the
  kept bundled agent and a failed one restores the old one; a kept agent whose program is gone is no
  fallback (`HR-MIGRATE-010`); a loosened or older launcher is repaired while local mode stays
  recognised; the setting off does no detection on a bundled Mac (one `launchctl print` per refresh
  since 2026-09-21, to load an unloaded job); three restarts
  in 30 minutes pause and surface; launchd's loaded arguments that differ from the file reload the
  agent; adopting an update-started process; restarting only on a commit change, not a timestamp;
  waiting for reinstalled dependencies; a fresh install starting only the local Hermes; nothing
  shown mid-update; a separate non-retryable `HR-MIGRATE-010`; `/Users/<name>` redacted; an
  inline-token agent is never switched from; lease contention waits without an error. Re-review fixes (each checked to fail when reverted): two failed switches on one commit pause
  switching (`HR-MIGRATE-011`) until the commit changes or the setting is turned off; two failed
  setting-off rollbacks with one kept agent pause until it changes or the setting is turned on,
  leaving local Hermes running; a stopped local Hermes is restarted regardless of its venv; a
  persistent version mismatch and a missing or duplicated dist-info are shown; dist-info is read
  deterministically; setting off with an intact local Hermes and no kept agent is `HR-MIGRATE-012`;
  the once-per-launch running-agent check is wired.
- install-when-missing and the default-on setting (`DesktopHermesInstallerTests`,
  `DesktopHermesInstallOfferTests`, `DesktopSystemProxyTests`, `HermesInstallWiringTests`,
  `DesktopLocalHermesPresentationTests`). The driver runs **fake installer scripts with the real
  process runner** in a throwaway home — no test reaches the network, the real `~/.hermes`, launchd
  or `~/Library`: a successful run drives `--manifest` then every stage with `--stage <name>
  --non-interactive --json --dir … --hermes-home … --branch main` in manifest order, reports a
  skipped interactive stage as skipped, and returns what detection then sees; the child's `HOME` is
  the sandbox, its stdin is not a terminal, and `sudo` resolves to the refusing guard; the log is
  0600, carries the script's SHA-256 and the proxy summary, strips ANSI and redacts credentials and
  home names, and the private run directory is removed; a stage failing with "Could not resolve
  host" is retryable `HR-MIGRATE-015` and stops later stages; a compiler-style failure is
  retryable `016`; a stage without a result frame and a manifest with `protocol_version` 2 are
  non-retryable `017` before any stage runs; a captive-portal page instead of a script runs
  nothing; an unreachable download is `015`; an install detection does not accept is `018`;
  cancelling terminates the stage's process group through `SIGTERM` alone within four seconds (it
  failed through the five-second `SIGKILL` fallback before the child's signal mask was reset) and
  is not an error; root is refused before any download; a Hermes that appeared meanwhile is used
  without running anything, and an unusual one is not touched; an offer that was only shown runs
  nothing. Offers: only `.absent(hermesDataPresent: false)` on a fresh Mac with the setting on is
  offered; data without a checkout, profiles, custom home, pipx and too-old are not; an
  incomplete checkout is resumable only after Desktop's own attempt. The origin check accepts only
  HTTPS on the official host or exactly upstream's `main/scripts/install.sh` on GitHub; the manifest parser reads upstream's
  real 17b5df02 manifest line and refuses malformed ones; the stage result is the last frame; the
  run state folds progress; network signatures are recognised and a compiler error is not; output
  lines are cleaned like a terminal shows them; `HR-MIGRATE-015`–`018` are bilingual with the
  registered retryability and recovery and redact URL credentials and home names. Proxy: the
  System Settings dictionary is parsed; disabled, invalid and PAC-only settings export nothing
  (and PAC is named in the summary); each configured proxy is exported in both cases and only
  then, with IPv6 bracketed and SOCKS as `socks5h`; explicit variables win; loopback and the
  bypass list are always in `no_proxy`. Setting: on with nothing configured, off from the
  environment or after "use the bundled Hermes". Wiring (source assertions): exactly one
  `installer.install(` call site, inside `startHermesInstall()`; the card starts it only from the
  confirmation sheet and Retry; the refresh offers; both setup paths wait for the decision.
  Review fixes of 2026-09-22 (each checked to fail when its fix is reverted): a failure in
  `node-deps` after `python-deps` — detection already reads `.usable` — keeps Desktop's attempt
  pending (setup blocked, the half-installed Hermes still offered for resume) and Retry runs the
  stages again; an install that ends without upstream's `.hermes-bootstrap-complete` is `018`;
  `freshInstallProvider` refuses a Hermes whose Desktop-started install is unfinished and accepts
  it once the completion marker exists; a resume refuses a checkout replaced by the owner (inode and
  birth time differ) before downloading anything, and a first install refuses a checkout that
  appeared meanwhile; stale attempt records are dropped; the re-evaluation before running writes
  nothing, must match the shown offer and aborts without one (source assertion); `SSH_AUTH_SOCK`
  does not reach the installer; the prerequisites probe's "Could not reach https://duckduckgo.com/"
  and "Failed to download" do not turn a compiler failure into `015`; a manifest line over 4096
  bytes reaches the parser whole and log lines are split, not cut; a redirect hop off the origin is
  refused at the hop and the download stops past its limit; `*_API_KEY=`, `ghp_…`, `sk-…` and URL
  passwords containing `@` or `/` are redacted while `registry.npmjs.org/@scope` is not; quitting
  Desktop (`terminateAllProcessGroups`, wired to `applicationWillTerminate`) ends a running stage's
  process group. Re-review (both fail against d90fbcc): cancelling while the `repository` stage
  still runs after its clone landed keeps the checkout recorded, pending and resumable; after a
  quit, a record without a checkout claims one born after the install started and drops an older
  one. Source assertions: both refused fresh setups raise the flag that keeps “改用内置 Hermes”
  reachable, and the card offering it exists.
- 2026-09-21 restart-probe incident (`DESKTOP_E4_TEST_RECORD.md`), each checked to fail when
  reverted where the boundary allows: the loopback shutdown decision is a pure function —
  `.waiting`/`.failed` with `ECONNREFUSED` is "stopped", `.ready` is "listening", every other error,
  `.setup`, `.preparing` and `.cancelled` stay undecided; a real loopback test on an ephemeral port
  proves a free port stopped in one probe (the old mapping took the full 15 s of five attempts and
  failed) and a bound, listening socket not stopped; a switch whose stop and restore waits both time
  out ends with the job loaded, the bundled agent restored, exactly one reload bootstrap, and a
  `DesktopServiceRecoveryFailure` naming `switch-to-local`, both `hermesStopTimedOut` causes, and an
  `HR-MIGRATE-009` diagnostic carrying them; a reload refused twice by launchd succeeds on the third
  attempt and the log keeps launchd's stderr; a reload refused every time is `hermesReloadFailed` →
  retryable `HR-MIGRATE-013`, also after a failed local restart; an upgrade whose rollback also fails
  carries both causes into `HR-MIGRATE-004`; a bundled job that is not loaded plans `.loadAgent` with
  the setting on or off and even mid-update, and is loaded with a readiness proof, while a stopped or
  unreadable bundled job is left to launchd; the setting-off path loads on every refresh and reloads
  only once per launch; the operation log is 0600, single-line per event, redacted, rotated once,
  never created without the managed logs directory, never follows a symlink, logs mutations with
  status and stderr and summarises `print` polls; `SystemCommandRunner` keeps a bounded stderr only
  when asked; the migration journal reads a whole-second or offset RFC 3339 `updatedAt`, rewrites it
  canonically on the next transition, and still rejects anything that is not RFC 3339.
  Review follow-up (both fail against the first version): loading an unloaded job while another
  process accepts on 9119 makes exactly one single-attempt probe, bootstraps nothing, never reloads,
  and surfaces retryable `HR-MIGRATE-014`, with the setting on or off; a port taken between that probe
  and the start gives the same answer with no bootstrap. `HR-MIGRATE-013` copy claims no restart.

Remaining email-first release acceptance requires live-provider tests for resend/cooldown, expiry,
account-existence-neutral delivery behavior, packaged-UI inspection proving that an `email_otp`-only
Gateway exposes no Google action, and a packaged two-phone run proving the verification sheet and
selective revocation. The deterministic E7 transport/controller/error and retry cases are now
automated.

Every new phase-0 behavior requires a regression test when its boundary is deterministic. User-visible
error codes additionally require localization, retryability, recovery-action, and redaction tests under
the project-wide `ERROR_HANDLING.md` contract.

## Manual phase-0 matrix

| Case | Expected result | Status |
|---|---|---|
| Existing Connector running | Desktop observes it and does not launch a replacement | Verified on target Mac 2026-09-02; PID and launch count unchanged |
| Existing Connector migration gate | Healthy configured Hermes plus matching signed-release capability exposes preparation; stopped/unhealthy/unsigned cases preserve legacy | Automated; packaged target-Mac migration still pending |
| Migration Assistant preserves managed services | Overview reports the effective managed Agent rather than the stopped legacy label; missing this-device-only account credentials require sign-in; a restored legacy label is persistently suppressed only for a proven `account_active` installation | Desktop 0.2.4 packaged reboot passed on migrated Mac 2026-09-11: only managed labels recovered, legacy stayed disabled/unloaded, and post-reboot Android REST/WebSocket carried bidirectional traffic |
| Pre-contract managed token storage | A committed matching installation moves the existing token from both `0600` plists to the `0600` private file, restarts Hermes then Connector, and commits only after local readiness plus a same-binding Cloud health timestamp newer than Connector startup; injected failure restores the old configuration and requires fresh rollback health | Automated, including stale healthy snapshot regression; Desktop 0.2.9 with managed 0.3.4 completed the packaged migration and ordered service restart on 2026-09-12; physical Android account traffic and full Mac reboot remain pending |
| Existing Connector absent | UI reports not detected and offers no destructive action | Verified 2026-09-02 |
| Gateway available | Gateway layer is healthy with safe latency | Verified on target Mac 2026-09-02; 15–18 ms observed |
| Gateway offline/DNS failure | Only Gateway layer fails; Hermes wording remains accurate | Pending fault injection |
| Hermes returns 2xx | Local Hermes layer is healthy | Verified on target Mac 2026-09-02; 117–128 ms observed |
| Hermes returns 401/403 | Layer says reachable/authentication required, not “down” | Pending real-app check |
| Logs contain credentials | Display/export contains `<redacted>` only | Automated; target redacted UI preview inspected 2026-09-02, credential injection still pending |
| Window closes | Existing Connector remains running | Verified on target Mac 2026-09-02; menu-bar app remained and window reopened |
| Two phones use current Gateway | Existing phone configuration remains usable | Pending device check |
| Light/dark mode | Same cool-blue surface language and semantic states | Light verified 2026-09-02; dark pending |
| App icon and Dock presence | Desktop packaging copy equals the canonical Android icon; the packaged app declares `AppIcon`, appears in the Dock while running, and can be kept there with Dock’s native option | Icon copy verified by packaging gate 2026-09-02; Dock presence pending HG-55 physical check |
| Componentized v2 install | Default package remains absent/inert; valid local v2 configuration plus Gateway schema 2 and `hermes-serve-v1` retain one verifier-issued token beside the matching card. A safe machine adds “下载缺失组件”; preparation changes only private cache, an exact signed-version sheet gates commit, and v1/v2/account/refresh actions remain mutually exclusive. Capability loss or unsafe machine state before either stage blocks mutation; trust/scan failure cannot expose the v1 downloader; cleanup failure offers cleanup retry only | Core capability, manifest, token, prepare/commit/cancel/cleanup, component store, migration/rollback, structured error, and configuration boundaries automated; full SwiftUI compilation required. Enabled packaged UI, capability-withdrawal race, real signed archives, and clean/existing-Mac install remain pending |
| Managed Hermes search path (HG-58) | The written LaunchAgent carries `PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin`; an agent written before this change validates without it and is given one by the next optional-component activation; a malformed `PATH` on disk is refused | Automated (written plist, legacy-agent repair, malformed-`PATH` refusal, search-path shape). **Physically unverified**: that a PDF attachment from the phone now succeeds needs the managed service restarted with a rewritten agent, which no machine has done. Read-only check afterwards: the managed `hermes-server` process `PATH` contains `/opt/homebrew/bin` |
| Managed search-path startup repair (HG-58) | On a committed `account_active` installation whose agent predates the `PATH`, launching Desktop adds the key, restarts **only** Hermes, and proves it healthy; a second launch changes nothing; an existing well-formed `PATH` is left alone; a malformed one and an agent we did not write are refused before any service changes; a failed restart restores the exact file and the running server | Automated (6 cases in `DesktopMigrationCoordinatorTests`). **Physically unverified**: that the repair fires on a real migrated Mac, that the Connector survives the Hermes-only restart window, and that a PDF then sends |
| Managed startup reconciliation classification (HG-68) | A transferred-account Connector reconciliation failure remains a hard `HR-MIGRATE-002` block; token-storage or search-path repair failures surface as retryable `HR-MIGRATE-007` without changing an available managed-upgrade operation to failed | Automated issue/stage contract plus full Desktop compilation; packaged UI remains pending |
| Managed schema drift (HG-71) | The account window shows `HR-MIGRATE-006` with the drifted columns behind 详情 whenever the live `state.db` carries columns absent from the running release's `schemaBaseline`; it stays silent when there is no managed release, no readable database, or no drift; it is shown independently of the managed-installation state, so an `inconsistent` install does not hide it | Automated (drift comparison, derived paths, issue mapping, and `ManagedSchemaWiringTests` asserting the call site exists — the check was merged in 0.3.7 with no caller and stayed invisible). Verified on LGS-MACMINI 2026-09-20 with Desktop 0.2.19-dev against managed 0.3.7: the card reads “托管 Hermes 版本落后 … (HR-MIGRATE-006)” beneath the `HR-MIGRATE-002` card, on a Mac whose installation reads `inconsistent` — the state the advisory is deliberately not gated on. Live drift at the time: `messages` 24→26, `sessions` 58→59 |
| Local Hermes runtime switch | With `HermesGoLocalHermesRuntimeEnabled` on, a committed Mac with a usable standard Hermes rewrites only `com.hermesgo.hermes-server` to the local launcher, restarts only Hermes, passes the readiness proof, and the phone keeps REST and WebSocket traffic; the Connector's PID is unchanged; `HR-MIGRATE-006` disappears; with the owner's gateway running, `cron/executions.db` gains no rows from the serve's PID | Automated with in-memory launchctl; read-only dry run on LGS-MACMINI 2026-09-21 planned `switchToLocal` (0.21.3, `17b5df02`). **Physically verified once**: the Mac mini was switched by hand with Desktop 0.2.22 on 2026-09-21 (about 17 s of Hermes unavailability, Connector PID unchanged; `DESKTOP_E4_TEST_RECORD.md`, "2026-09-21 Mac mini switched to its own Hermes"). The automatic switch of a Mac by the default-on setting is not yet verified (next row) |
| Local Hermes restart on update | After `hermes update` on a local-mode Mac, the serve's start time is later than the checkout's ref time within one refresh, either because upstream kickstarted our job or because Desktop restarted it; no duplicate or detached serve holds 9119 | Planner and coordinator automated. **Physically unverified**, including upstream's kickstart of our job |
| Local Hermes rollback | Turning the setting off restores the exact bundled agent from `Managed/state/hermes-server.bundled.plist` and restarts only Hermes | Automated. **Physically unverified** |
| Local Hermes unsupported | A Mac with profiles, a custom `HERMES_HOME`, or a second install shows `HR-MIGRATE-008` and changes nothing; a fresh install on it is refused | Automated. Packaged UI pending |
| Local runtime on by default | Upgrading Desktop on a managed Mac with a usable standard Hermes and no `HermesGoLocalHermesRuntimeEnabled` key switches it on the first refresh exactly as the "Local Hermes runtime switch" row; a managed Mac without Hermes changes nothing and shows no install offer; `defaults write … -bool false` rolls back | Planner/coordinator automated; default value automated. **Physically unverified** on a Mac that has not been switched by hand |
| Install Hermes on a clean Mac | **Manual, on a clean macOS user account or VM** (no `~/.hermes`, no `~/.local/bin/hermes`, no Homebrew `hermes`, no pipx install; ideally one run with and one without a manual HTTP/HTTPS proxy in System Settings). Sign in to Desktop; the "这台 Mac 还没有 Hermes" card appears above the setup card and the setup download action is replaced by the decide-first line. "安装 Hermes…" opens the sheet; cancelling it runs nothing (no `~/.hermes`, no `hermes-install.log`). "开始安装" shows the manifest's stages in order; `setup` and `gateway` show as skipped; `ps -o pgid,command` shows the installer in its own process group, never as root, with no `sudo` process. `Managed/logs/hermes-install.log` is 0600, names the proxy, the script's SHA-256, every stage and no `/Users/<name>`. Afterwards `~/.hermes/hermes-agent/venv/bin/hermes --version` works, the card reads "Hermes 已安装", and continuing the normal setup writes `com.hermesgo.hermes-server` in local mode directly (`ProgramArguments` name the local launcher; `Managed/state/hermes-server.bundled.plist` exists; the bundled `hermes-server` never runs). Repeat with the network or proxy broken (for example a bogus manual proxy): `HR-MIGRATE-015` with Retry and "改用内置 Hermes"; fix the proxy and Retry resumes. Cancel during `python-deps`: no installer, `git`, `uv` or `curl` process remains within ~5 s; the card says cancelled, not failed; Retry resumes. "改用内置 Hermes" writes the setting off and the setup installs the bundled copy exactly as before | Driver, proxy composition, offer rules, cancellation and error codes automated with fake installer scripts. **Not run** against upstream's real installer or on a clean Mac |
| Managed patch 020 — bounded inline images (HG-74) | A bounded read carries `[image]` in place of an inline `data:` image; the image still appears on the phone, from the Mac path in the text part; an `https://` image URL is untouched; the placeholder never reaches a bubble | Both client-side behaviours the patch depends on are pinned by Android tests (`TimelineNoteTest`, `MultimodalContentMappingTest`) — neither is new, both were unpinned, and the patch lives in another program. Measured against the real database: one message 27,479,595→6,374 characters, the whole HG-65 session 105.07 MiB→0.265 MiB. **Not yet live**: needs a managed release and a controlled activation, so no phone has read a bounded response |
| Managed Hermes restart proof (2026-09-21) | On the packaged app, a runtime switch or managed upgrade that boots out `com.hermesgo.hermes-server` bootstraps the replacement within a few seconds of the old process exiting (`launchd.log` shows the bootstrap; `Managed/logs/desktop-runtime.log` shows `wait-stopped result=stopped attempts=1` or close to it), not after ~112 s | Automated (probe, loopback, coordinator). **Physically unverified** |
| Managed Hermes left unloaded | With Desktop running on a committed installation, `launchctl bootout gui/$UID/com.hermesgo.hermes-server` by hand is undone within one refresh (setting on or off): the job is bootstrapped again and passes readiness; if launchd refuses three bootstraps the window shows retryable `HR-MIGRATE-013` and tries again after five minutes. If another process listens on `127.0.0.1:9119` at that moment, nothing is bootstrapped, the lease is released after one probe, and the window shows retryable `HR-MIGRATE-014` | Planner and coordinator automated. **Physically unverified** |
| Service operation log | `Managed/logs/desktop-runtime.log` exists at 0600 after the first service operation, carries launchctl mutations with status and stderr and each wait's outcome, contains no token and no home-directory name, and stays under ~512 KiB across rotations | Automated. Packaged check pending |
| Hand-edited journal | A journal whose `updatedAt` is `2026-09-20T11:48:09Z` loads (no `HR-MIGRATE-002`), and the next Desktop transition rewrites it as `…09.000Z`; a non-RFC 3339 value still fails closed | Automated |
| Keychain profile | App Token persists across restart and is never shown in visible UI | Verified locally and on target with disposable test Token 2026-09-02 |
| Invalid App Token | End-to-end check reports `HR-AUTH-001` with recovery guidance | Automated + local/target UI verified 2026-09-02 |
| v1 QR payload | JSON contains only compatible `v`, `url`, and `token` fields | Automated 2026-09-02 |
| QR reveal | Real QR is hidden by default and carries an explicit long-lived-token warning | Local + target UI verified 2026-09-02; Android scan pending |
| End-to-end success | Saved App Token reaches Gateway → Connector → Hermes through `/api/status` | Pending target production-token check |
| Account-mode presentation | A signed-in account omits the legacy App-Token probe, and Overview uses the Desktop-selected Mac's authenticated Connector/Gateway/Hermes/end-to-end snapshot instead of a saved legacy profile; switching devices cannot rename or restart local services | Automated |
| Account mode disabled | Account & Devices reports unavailable and legacy connection remains usable | Automated core behavior; packaged UI inspection pending |
| Email account login | Email challenge and six-digit exchange create/restore only the Desktop management session | Controller/API automated; live delivery and packaged UI pending |
| Browser OAuth loopback | Listener binds an ephemeral `127.0.0.1` port and rejects mismatched state | Automated locally; live Google client pending |
| Account session restart | Keychain session refreshes without changing the Connector machine identity | In-memory/store contract automated; packaged Keychain run pending |
| Two account phones | Account & Devices lists both; owner-email verification removes only the selected phone | Controller/API/PostgreSQL automated; packaged UI and physical phones pending |
| Whole-device sharing off | No share controls appear and existing device selection remains unchanged | Automated core behavior; packaged UI pending |
| Owner invites account | Explicit sessions/files/config warning and fresh owner-email verification precede mail request | Controller/API automated; live mail/two-account run pending |
| Recipient accepts invite | Pasted link requires exact token + acknowledgement and creates operator access only | Parser/API automated; two-account physical run pending |
| Owner revokes / recipient leaves | Matching access and live stream end; owner, other grantees, Connector, and Hermes continue | Gateway automated; packaged Desktop and multi-node run pending |
| Account deletion off | No Desktop danger-zone action appears and the route is not called | Core/API automated; packaged UI pending |
| Permanent account deletion | Typed `DELETE`, acknowledgement, and fresh email code precede immediate Cloud logout; success and recovered ambiguous completion land on “deletion submitted”; the explicit other-email exit reaches an empty sign-in flow, while local Hermes remains intact | Core/API/PostgreSQL automated; disposable packaged-account and privacy review pending |
| Android account-mode cutover | Android signs in by email, selects the intended owned/shared Mac, proves REST and WebSocket traffic, then Desktop migrates; protected Connector status matches the exact binding/generation before and after Desktop/Mac restart | Primary single-phone 0.1.113 path accepted 2026-09-10; migrated-Mac reboot plus post-reboot account REST/WebSocket accepted 2026-09-11; activation-interruption and multi-phone/shared-Mac matrices remain pending |

The first real-app check verified that the ad-hoc app launches and remains running. The target Mac run
then verified the installed DMG against a live legacy Connector without changing its PID, launch count,
configuration, log size, or public Relay attachment. The diagnostics UI correctly kept the App-Token
end-to-end check unavailable. Physical two-phone traffic, injected fault cases, and takeover/rollback
remain pending; public Relay health alone is not evidence for a phone UI test.

The `0.2.0-dev` target upgrade additionally verified Keychain persistence across restart, explicit QR
reveal, and the invalid-token UI mapping without restarting the legacy Connector. The disposable Token
and temporary rollback package were removed after the run. A real production Token was deliberately
not retrieved as part of this test, so the successful end-to-end and Android scan rows remain pending.

## Android-account coordinated acceptance

Run this matrix only after the Android account branch has passed its package gate. Do not migrate the
Mac merely because email login or `/v2/devices` succeeds.

1. Keep the legacy Connector active. Install the versioned Android test artifact, sign in with email,
   and confirm the intended owned or shared Mac is listed without importing a legacy App Token.
2. Record the protected `/internal/account-connectors` snapshot and the authenticated Android device
   row. The former may show zero account connections before migration; `/relay-health` must still show
   the expected legacy Connector.
3. Complete the Desktop's two-confirmation migration. Require an `account_active` journal, both exact
   managed LaunchAgents, healthy local Hermes, and an internal snapshot row whose binding UUID and
   generation match Desktop state. Before any later token-storage repair, record the active managed
   release and require the signed runtime capability boundary; 0.3.0 must remain unchanged.
4. From Android, exercise one REST status request, one real WebSocket session, **and one slash
   command** through the selected device **before calling the Desktop candidate accepted or
   proceeding to a reboot**. An established Connector control socket and a loopback HTTP 200 do not
   satisfy this gate. For a shared Mac, repeat using the grantee account and confirm revocation
   closes only that grantee's live stream.

   The slash command is not padding. A prompt exercises the gateway in-process; a slash command is
   the only thing that makes Hermes spawn a **child** process, and a child gets a different
   environment from its parent. Managed release 0.3.0 served prompts perfectly and could not run a
   single slash command, because the child could not import `tui_gateway` — and nothing in this
   matrix would have caught it (HG-28). Switching the model in the Android model picker is the
   cheapest way to exercise it: it sends `/model … --session`. A failure appears as `slash.exec`
   error 5030 in the device diagnostics and `HR-RPC-007` on screen.
5. Restart Android and Desktop independently, then reboot the Mac. Require the same account binding,
   automatic managed-service recovery, a newer process-local `connectedAt`, and successful Android
   REST/WebSocket traffic without re-entering a legacy Token.
6. On any failed gate, capture redacted diagnostics and use the journaled Desktop rollback. Confirm
   the legacy Connector returns, `/relay-health` reports it online, and the old Android path remains
   usable. Do not call a mixed legacy/account state successful.

## Later takeover gate

Managed Agent takeover must not ship until automated and real-machine tests prove:

1. the new Agent does not start while the old Connector is active;
2. configuration validates before the old service stops;
3. the new Agent receives `hello_ack` and passes local/end-to-end checks;
4. failure stops the new Agent and restores the old service;
5. the phone retains its URL, token, sessions, and history after takeover and rollback.

Items 1–4 now have injected local core tests. They are not considered target-Mac verified: the
packaged UI path is compiled but hidden in the default-off build, and no real signed/notarized
artifact has exercised it. Item 5 and the physical clean-install/upgrade/interruption matrix remain
manual gates.
