# Upstream Android source

- Repository: https://github.com/adebnar/hermes-android
- Initial tag: `v0.1.52`
- Initial commit: `9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a`
- License: GNU General Public License v3.0
- Upstream package: `com.hermes.client`
- Upstream application ID: `com.hermes.client`

## License handling

The Android derivative must retain the upstream copyright and GPL notices. If the modified APK is conveyed to other people, the corresponding source must be made available under GPLv3 terms. Gateway and Connector licensing can be considered separately when they are independent programs communicating over a protocol, but the Android derivative itself is GPLv3.

Do not copy the upstream signing keystore. Hermes Remote will use its own application ID and signing key.

## Import record

The source was imported from the pinned commit for the first Relay-compatible MVP. Product-specific changes include a new application ID and branding, token-only setup, Hermes Remote Relay defaults, display-payload normalization, collapsed tool results, and a redesigned Compose chat shell. Future upstream changes should be reviewed against the pinned commit.
