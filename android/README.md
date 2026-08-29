# Android client

This directory is reserved for the selected open-source Hermes Android client.

Before importing it, confirm:

1. Repository URL and exact revision used by the tested APK.
2. License compatibility and required attribution.
3. Existing networking/session code that can be retained.
4. Whether the UI is Views or Jetpack Compose.

The client will connect to `/v1/connect`, send an authenticated `hello`, target `mac-mini`, and render protocol events as structured chat blocks rather than raw JSON.
