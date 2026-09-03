# Desktop concept images

These three first-round concepts were generated with the built-in image generation workflow on
2026-09-02 after reading `docs/DESIGN.md` and inspecting the current light/dark Android screenshots.

- `overview.png`: normal overview, topology, and menu-bar popover.
- `pairing.png`: QR/manual pairing and shared-identity explanation.
- `diagnostics.png`: layered checks, actionable authentication failure, and sanitized log preview.
- `implementation-phase0.png`: the first running SwiftUI implementation captured on macOS. It shows
  the expected safe empty/absent-legacy state on the development machine, not a simulated healthy
  production connection.

Prompt set: high-fidelity native macOS utility; primary blue `#0B5FD0`; cool `#F7F9FD` canvas;
`#FAFBFD` low-contrast cards; independent semantic status colors; rounded thin icons; no gradients,
purple cast, terminal-first layout, or real credentials.

The H artwork visible in generated concepts is only a directional approximation. Product code and
packaging must use the canonical app icon directly, as specified in `docs/DESKTOP_DESIGN.md`.
