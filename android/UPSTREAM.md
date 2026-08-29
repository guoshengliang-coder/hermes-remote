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

## Import policy

Keep the upstream repository as a pinned reference until the first Hermes-compatible relay is ready. Then import the Android source with attribution, record the upstream commit, and keep future upstream changes reviewable.
