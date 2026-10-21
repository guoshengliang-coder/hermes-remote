import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { loadDeployConfig, loadOpsConfig } from "../../ops/lib/config.mjs";
import { renderNginxConfig } from "../../ops/lib/system.mjs";
import { renderDeployNginxConfig } from "../../ops/lib/deploy-system.mjs";
import { renderMultiDeviceNginxRoutes } from "../../ops/lib/production-multi-device-rollout.mjs";

test("all managed edge templates stream file data without request/response staging", async () => {
  const configs = [renderNginxConfig(await loadOpsConfig("ops/staging.example.json")),
    renderDeployNginxConfig(await loadDeployConfig("ops/staging.deploy.example.json")),
    await readFile("deploy/hermes-edge.nginx.conf.template", "utf8")];
  for (const config of configs) {
    for (const path of ["/api/file-channel/", "/api/files/downloads/"]) {
      const start = config.indexOf(` ${path} {`);
      assert.ok(start > 0);
      const body = config.slice(start, config.indexOf("\n    }", start));
      for (const directive of ["proxy_buffering off;", "proxy_request_buffering off;", "proxy_cache off;", "client_max_body_size 8m;"]) {
        assert.ok(body.includes(directive), `${path}: ${directive}`);
      }
    }
  }
  const account = renderMultiDeviceNginxRoutes();
  const download = account.indexOf("location ~ ^/v2/devices/[^/]+/api/files/downloads/");
  const generic = account.indexOf("location ~ ^/v2/devices/[^/]+/api(?:/|$)");
  assert.ok(download >= 0 && download < generic);
  assert.match(account.slice(download, generic), /proxy_buffering off;/);
  assert.match(account.slice(download, generic), /proxy_request_buffering off;/);
});
