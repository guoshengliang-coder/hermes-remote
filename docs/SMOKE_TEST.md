# Local relay smoke test

Start the gateway and mock connector as described in the root README. Connect a WebSocket client to `ws://127.0.0.1:8787/v1/connect` and send:

```json
{"type":"hello","version":1,"role":"app","deviceId":"android-dev","token":"dev-app"}
```

Then send:

```json
{"type":"command","version":1,"id":"request-1","targetDeviceId":"mac-mini","payload":{"kind":"chat","input":"hello"}}
```

Expected terminal event:

```json
{"type":"event","version":1,"requestId":"request-1","event":"complete","data":{"sessionId":"request-1"}}
```

## Hermes compatibility smoke test

The repository also includes a protocol-shaped Mock Hermes server that requires the same Basic Auth → Cookie → WS Ticket sequence as the real dashboard.

Run the Mock Hermes server, Gateway, and Connector on separate terminals, then run:

```bash
npm run smoke:compat
```

The test passes only after `/api/status` traverses the REST tunnel and a JSON-RPC request traverses `/api/ws` in both directions.

For a deployed relay, set `PUBLIC_GATEWAY_URL` and `APP_TOKEN` in the invoking shell before running the same script. Keep the token out of command history and source control. A successful real-Hermes run accepts `gateway.ready` and any non-error result from `session.create`.

## Attachment and large-response smoke test

With Gateway and Connector running locally and `FILES_ROOT` pointing at a dedicated test directory:

1. Upload a small text, PDF, and processed photo through the Android attachment sheet. Confirm each
   appears on the outgoing user turn and Hermes receives the attachment before the prompt.
2. Capture a photo with the system camera, cancel once, then capture successfully. Confirm cancel is
   harmless and the successful capture produces a thumbnail.
3. Have Hermes return `@image:/absolute/path/to/image.png` and `@file:/absolute/path/to/report.pdf`.
   Confirm the image opens full screen and the file card opens/shares through the Android system UI.
4. Download a file larger than the former whole-message ceiling (for example 24 MiB but below
   `MAX_FILE_BYTES`). Confirm the download completes and the Connector remains online afterward.
5. Attempt a path outside `FILES_ROOT`, an upload above `MAX_UPLOAD_BYTES`, and a download above
   `MAX_FILE_BYTES`. Expect request-scoped 403/413 errors with no control-WebSocket disconnect.
