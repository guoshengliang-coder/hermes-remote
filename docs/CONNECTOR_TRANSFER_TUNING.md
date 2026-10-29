# Per-Mac file transfer budget

The Connector already accepts `FILE_DOWNLOAD_BYTES_PER_SECOND` at startup: an aggregate budget
across its file-return streams, separate from the control WebSocket. The default remains
4 MiB/s; the supported integer range is 1 through 134217728 bytes/s. This does not change the
Android 4 MiB read-ahead window, the four-request concurrency limit, or the file-size ceiling.

Desktop now recognizes this single optional LaunchAgent environment key during connection repair
and Connector-only reconnect. Both bundled-release and component-store upgrades retain the key
from an owner-private, regular existing Connector plist. Missing plists are reconstructed without
an override and therefore use the Connector default. Rollback restores the exact saved plist.
Rates must use canonical decimal strings; zero, negative, fractional, padded, overflowing and
over-limit values fail closed. Other unexpected environment keys still block repair, and upgrades
do not import them. There is no new UI, wire field, capability, or error code: invalid installation
configuration uses the existing managed-service repair failure contract.

## Measurement and selected trial

On 2026-10-24, an M4 Mac mini sent synthetic bytes directly to a temporary authenticated TCP sink
on the Hong Kong host. Four connections ran for 12 seconds per case, twice in reverse order;
stream checksums matched. No payload passed through the operator's computer. Results in MiB/s:

| Aggregate cap | First round | Reverse round |
|---|---:|---:|
| 4 MiB/s | 3.994 | 3.587 |
| 8 MiB/s | 7.697 | 6.824 |
| Uncapped | 9.220 | 8.587 |

Public health requests all returned HTTP 200. Median latency increased from 84.8 ms idle to
155.7 ms during the 8 MiB/s trial and 231.4 ms uncapped. These are short WAN samples, not an
Android preview measurement or a guarantee of sustained HTTPS file-channel performance.
The temporary sink was removed. The chosen next trial is **8388608 bytes/s on this Mac only**;
there is no global default increase or automatic hardware-based tuning.

## Authorized rollout and rollback

Source merge does not apply the override. First install a Desktop build containing this support;
older Desktop versions may refuse repair/reconnect when they encounter the key. Keep the signed
Connector payload and launcher unchanged. No Connector or Android release is needed for this knob.

After an explicitly authorized single-Mac rollout:

1. Confirm both managed services are healthy and no installer/repair operation is active. Read
   only selected status fields; never print credential files, full process environments or full
   LaunchAgent contents. Back up the exact Connector plist outside `~/Library/LaunchAgents` in
   owner-only storage (directory 0700, file 0600).
2. Atomically add the string `FILE_DOWNLOAD_BYTES_PER_SECOND=8388608` to `EnvironmentVariables`
   in `~/Library/LaunchAgents/com.hermesgo.connector.plist`, preserving the rest and mode 0600.
   Validate the plist. Never modify the Hermes Server plist or signed managed components.
3. Reload only `com.hermesgo.connector` in the logged-in user's `gui/<uid>` launchd domain.
   A `kickstart` alone does not reload an edited plist: unload and bootstrap the exact label/plist.
   This briefly interrupts the Connector. Keep the Hermes Server running. Allow launchd teardown
   to settle before bootstrap; if bootstrap fails, inspect the exact label and use a bounded retry
   rather than assuming the new configuration loaded. Restore the backup if recovery fails.
4. Verify the loaded job contains the intended rate and a new running Connector PID, the Hermes
   PID is unchanged, and the exact account binding has a fresh healthy end-to-end observation.
   Public `/relay-health` alone cannot prove account-mode health: its `connectors` count is legacy.
5. Exercise a large file through the actual file channel, checking bytes/hash, parallel aggregate
   throughput and interactive responsiveness. Then check video startup and seek on Android.
   Raw TCP and local unit tests do not satisfy this device acceptance step.

For rollback, restore the private backup byte-for-byte and reload only the Connector again;
repeat the health checks. Removing the optional key before reload restores the default 4 MiB/s.
If the plist is lost entirely, repair uses the default; reapply tuning only after health is restored.

Automated coverage includes both release layouts, reconnect/repair, invalid values, unknown
environment rejection, both upgrade writers, and exact snapshot restoration. Rollout evidence
follows; Android playback acceptance remains separate.

## 2026-10-24 M4 rollout and file-channel observations

The owner authorized the single-Mac trial after Desktop **0.2.38 / build 41** was installed.
The managed Connector **0.4.12** was running in account mode. Under its existing operation lease,
the operator saved the original plist byte-for-byte in a private recovery directory (0700,
backup 0600), changed only the rate environment key to `8388608`, validated the replacement plist,
and reloaded only the Connector. The first immediate bootstrap failed; the original configuration
and running service were restored before retrying with a teardown wait. The successful retry
retained mode 0600, produced a new Connector PID, and preserved the Hermes Server PID. Both the
loaded launchd job and actual Node process environment contain the override. No signed component,
Gateway service, other Mac, account binding, or client release was changed.

An authenticated `/v2/devices` read matched the target binding ID and public-key fingerprint and
confirmed a fresh post-reload observation: Connector online, Hermes reachable, end-to-end healthy.
Hermes **0.21.3** also returned local HTTP 200 / `ok: true`. Subsequent lifecycle acknowledgements
and a relayed tunnel round trip were observed. The public legacy connector count was not used as
account-mode acceptance.

A desktop probe requested a temporary **64 MiB synthetic file** from the M4 through the actual
public HTTPS file channel. It negotiated four ranges, grew 256 KiB requests to at most 1 MiB using
the Android batch thresholds, and checked HTTP 206, ETag, Content-Range, exact length and final
SHA-256. Two complete transfers passed:

| Desktop probe using its existing system proxy | Elapsed | Throughput | Requests |
|---|---:|---:|---:|
| Round 1 | 27.268 s | 2.347 MiB/s | 71 |
| Round 2 | 20.389 s | 3.139 MiB/s | 69 |

During these transfers, 39 routed `/api/health` probes all returned HTTP 200 (median 158.5 ms,
p95 667.5 ms). The exact account binding remained healthy afterwards. These are health-route
latencies, not chat-generation latency or evidence of unchanged interactive performance.

The configured 8 MiB/s ceiling is verified, but these observations **do not establish an 8 MiB/s
end-to-end rate or an improvement over 4 MiB/s**: there was no same-path 4/8 A/B comparison, and the
desktop receiver path adds another network segment. A direct-TLS Python desktop probe also
completed one 64 MiB transfer with the same validation at 0.988 MiB/s (64.787 s); its second
attempt failed an HTTP check before completion, with only the error class retained. This slower
receiver path is not sufficient to locate a unique bottleneck. A subsequent direct-TLS 16 MiB
prefix read passed range/checksum validation at 2.105 MiB/s (7.602 s), with 7/7 health probes
successful and the target binding still healthy; the earlier HTTP failure remains unclassified.
Do not enlarge the Android range
or read-ahead window based on these samples. No physical Android device was attached; original
video cold-start, seek and sustained playback over Wi-Fi/cellular remain pending.

## HG-244 opt-in extension

The negotiated mode additionally permits the strict `CONNECTOR_TRANSPORT_ISOLATION=0|1` override. Its process-wide budget includes independent file returns, bulk business fragments and upload ACK pacing; congestion halves the cap and reduces new range admission to one. Three healthy round trips permit gradual recovery. Removing/disabling the negotiated capability restores the original single-connection behavior. This extension needs a compatible Gateway and Connector release and explicit rollout authorization; the earlier file-rate-only rollout does not enable it. See `HG_244_CONGESTION.md`.
