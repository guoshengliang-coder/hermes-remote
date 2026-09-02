# Hermes Go Desktop design contract

This document adapts the Android contract in `DESIGN.md` to the native macOS companion. Shared brand
decisions remain authoritative; macOS-specific navigation and controls follow native platform
conventions.

## Product form

- A menu-bar utility with a reopenable main window.
- Closing the main window does not stop the Connector.
- The main window uses a fixed desktop sidebar: Overview, Diagnostics, Logs, Account & Devices,
  Settings. Legacy pairing is nested inside Account & Devices.
- Phase 0 is visibly labeled **compatibility observation mode** so it cannot be mistaken for Agent
  takeover.
- Pairing uses a two-column layout: an explicitly revealed QR card and a manual connection editor.
- A saved long-lived App Token is masked in fields and the QR stays hidden by default. Revealing it
  always keeps the nearby-person warning visible.

The I3-A alpha implements the account-first navigation: **Phone Pairing** is now **Account & Devices**,
Google login is the default action when both build configuration and Gateway capabilities allow it,
and QR/manual Token configuration lives in a collapsed **Advanced: Legacy connection** card. The
legacy editor and QR remain fully functional. Account binding takeover, replacement, and unbind are
not active in I3-A, so the current Connector remains authoritative until the later migration gate.
See `ACCOUNT_MODE_DESIGN.md`.

The future Google action uses the system default browser. Existing browser Google sessions appear in
Google's own account chooser, allowing direct authorization without re-entering credentials while
keeping browser cookies and profile data outside Hermes Go Desktop.

Implemented account states are capability checking/unavailable, signed out, browser authorization,
signed in with no binding, bound healthy/offline, pending/replacement/revoked, and session-needs-login.
The normal signed-in view shows the account, current Desktop, one binding, and independently removable
phones. Account errors appear with stable `HR-*` codes and copyable redacted diagnostics.

## Shared visual tokens

- Brand primary: `#0B5FD0` in light mode; blue is chrome/action color only.
- Light canvas: `#F7F9FD`; light card: `#FAFBFD`; hairline: `#EBEDF2`.
- Dark canvas: `#111820`; dark card: `#1A212A`; hairline: `#333A44`.
- Healthy, degraded, and failed use independent green, amber, and red semantic colors.
- Cards rely on a one-step surface difference, a 1 px hairline, and a restrained light-mode shadow.
- Controls use continuous 9–18 px radii and an 8 px spacing rhythm.
- Sidebar symbols use regular-weight SF Symbols at native macOS sizes. The product mark itself never
  uses a generated or substituted symbol.

## App icon decision

The only app-icon source of truth is
`android/app/src/main/ic_launcher-playstore.png`. The macOS app, DMG, sidebar identity, menu-bar
identity, About page, and QR center mark use that artwork without redrawing or recoloring it.
`desktop/Packaging/AppIcon.png` is a synchronized packaging copy, and the packaging gate fails if it
drifts from the canonical source.

## Status and error language

- Every layer keeps its own state: Desktop Agent, Gateway, local Hermes, optional observer, end to end.
- An optional observer failure may degrade the product but cannot mark a working main path offline.
- “Cannot reach from this Mac” must not be rewritten as “Hermes is down.”
- New user-visible errors must be registered in `ERROR_HANDLING.md` before implementation.
- Technical details and logs are secondary, selectable, and redacted before presentation.
- Current reachability and historical log warnings are separate. Old warning lines may be counted and
  shown as history, but cannot by themselves mark a currently healthy connection offline.
- End-to-end failures show a registered `HR-*` code and recovery action; raw HTTP bodies and tokens
  never become primary UI text.

## Concept references

- [Overview and menu bar](design/desktop/overview.png)
- [Phone pairing](design/desktop/pairing.png)
- [Diagnostics and logs](design/desktop/diagnostics.png)
- [Phase-0 SwiftUI implementation](design/desktop/implementation-phase0.png)

These are direction references, not pixel specifications. Generated placeholder QR codes are not
functional, and generated approximations of the H mark must be replaced by the canonical app icon.
