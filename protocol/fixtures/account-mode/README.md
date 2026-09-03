# Account-mode contract fixtures

These sanitized I0 fixtures freeze representative V2 shapes before the parsers and consumers are
implemented. They are not production credentials and intentionally use reserved example data.

I1/I2 tests must load these fixtures (or generated equivalents with the same asserted semantics),
validate all bounded fields, and add negative/malformed siblings. Changing a committed meaning
requires updating `docs/ACCOUNT_MODE_API.md`, every affected consumer, and the compatibility tests.

- `capabilities-dual.json`: account features available while both legacy credentials remain accepted.
- `verified-google-claims-android.json`: normalized output of the provider-verification boundary, not
  a raw Google token.
- `binding-healthy.json`: the one remote-device response consumed by Desktop and Android.
- `auth-error-refresh-reuse.json`: stable structured refresh-replay failure.
- `connector-identify.json`: non-secret binding/generation identification before challenge.
- `connector-challenge.json`: server challenge before Connector authentication.
- `connector-authenticate.json`: canonical Ed25519 proof response shape.
- `connector-preflight-request.json` / `connector-preflight-result.json`: read-only Hermes health
  negotiation before the socket becomes ready.
- `connector-ready.json`: pending/active routing decision after proof and preflight.

Never add a real token, email, key, host address, provider response, or production identifier here.
