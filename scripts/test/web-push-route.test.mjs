import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
test("HG-191 edge route is exact, bounded and reuses the managed upstream without widening Web routes", async () => {
  const route = await readFile(
    new URL("../../deploy/nginx-web-push.conf.template", import.meta.url),
    "utf8",
  );
  assert.match(route, /location = \/v2\/web\/push-subscription \{/);
  assert.equal((route.match(/^location /gm) ?? []).length, 1);
  assert.match(route, /client_max_body_size 8k;/);
  assert.match(route, /proxy_pass http:\/\/hermes_go_gateway_production;/);
  assert.match(route, /X-Forwarded-For \$remote_addr;/);
  assert.match(route, /proxy_read_timeout 15s;/);
  assert.doesNotMatch(route, /Authorization|VAPID|public_key|private_key/);
});
