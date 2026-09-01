# Android signing policy

Hermes Remote currently uses one temporary, shared Android debug signing identity so APKs built by
Hermes, Codex, or Claude Code can update the same installed application.

## Canonical certificate

```text
Subject: C=US, O=Android, CN=Android Debug
SHA-256: 06:C1:8D:FC:4A:85:23:30:65:4C:2D:A0:40:A5:78:BC:
         CA:B1:3B:71:DD:E4:AC:96:2B:B9:BC:22:71:DD:32:C5
```

The private key is never stored in this repository. On each explicitly authorized build host it is
provisioned at `~/.android/debug.keystore` with user-only permissions. Do not generate, replace, copy,
or expose that file unless the project owner explicitly authorizes signing-key provisioning.

`android/app/build.gradle.kts` verifies the public certificate digest before every debug assembly.
A missing or different keystore fails the build instead of producing an APK that cannot update the
installed app.

Ordinary CI never receives this key. It runs Android unit tests, lint, and source compilation without
packaging an APK. Only the release workflow provisions the canonical key, and the secret is scoped to
one trusted shell step instead of the whole job. That step runs the full package gate and removes the
key with a trap before the separate SSH deployment step begins; signing and deployment credentials
never coexist on disk.

## Build and verification

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Only distribute the versioned APK staged under:

```text
android/app/build/outputs/apk/distribution/debug/
```

The build passing `verifyDebugSigningKey` proves that Gradle's default debug signing key has the
canonical certificate. When investigating an external APK, also run the newest installed Android SDK
`apksigner verify --print-certs <apk>` and compare its SHA-256 digest with the value above.

## Security and future migration

This is a compatibility bridge, not the final production signing design. Android debug keystores use
well-known credentials and must not be used for a public store release. Before public distribution,
create a dedicated release key, store it in an approved secret manager with recoverable backups, update
the Gradle release pipeline to fail closed, and perform the planned one-time migration from the debug-
signed app.
