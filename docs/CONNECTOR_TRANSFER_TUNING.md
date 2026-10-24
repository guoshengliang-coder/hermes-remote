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
   This briefly interrupts the Connector. Keep the Hermes Server running.
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
environment rejection, both upgrade writers, and exact snapshot restoration. Installing the new
Desktop, applying the override, real file-channel throughput and Android playback remain separate
rollout/acceptance steps until recorded with actual results.
