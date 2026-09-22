import assert from 'node:assert/strict';
import test from 'node:test';

import {classifyChangedPaths} from '../ci/changed-components.mjs';

test('documentation-only changes skip component build jobs', () => {
  assert.deepEqual(classifyChangedPaths(['README.md', 'docs/SMOKE_TEST.md']), {
    node: false,
    android: false,
    desktop: false,
    assets: false,
    web: false,
  });
});

test('component paths select only their affected build jobs', () => {
  assert.deepEqual(classifyChangedPaths(['gateway/src/index.ts']), {
    node: true,
    android: false,
    desktop: false,
    assets: false,
    web: false,
  });
  assert.deepEqual(classifyChangedPaths(['android/app/src/main/kotlin/App.kt']), {
    node: false,
    android: true,
    desktop: false,
    assets: false,
    web: false,
  });
  assert.deepEqual(classifyChangedPaths(['desktop/Sources/App.swift']), {
    node: false,
    android: false,
    desktop: true,
    assets: false,
    web: false,
  });
  assert.deepEqual(classifyChangedPaths(['web/src/hermes/client.ts', 'web/package-lock.json']), {
    node: false,
    android: false,
    desktop: false,
    assets: false,
    web: true,
  });
});

test('the Gateway surfaces the Web app calls also run the Web job', () => {
  for (const file of [
    'gateway/src/gateway-http-router.ts',
    'gateway/src/account/web-device-access.ts',
    'gateway/src/account/account-http-controller.ts',
    'gateway/src/web-app-host.ts',
    'docs/hermes-rpc-params.json',
  ]) {
    assert.equal(classifyChangedPaths([file]).web, true, file);
  }
  for (const file of ['gateway/src/index.ts', 'connector/src/index.ts', 'package-lock.json', 'docs/DESIGN.md']) {
    assert.equal(classifyChangedPaths([file]).web, false, file);
  }
});

test('shared CI and launcher assets select every dependent job', () => {
  assert.deepEqual(classifyChangedPaths(['.github/workflows/ci.yml']), {
    node: true,
    android: true,
    desktop: true,
    assets: true,
    web: true,
  });
  assert.deepEqual(classifyChangedPaths(['android/app/src/main/ic_launcher-playstore.png']), {
    node: false,
    android: true,
    desktop: false,
    assets: true,
    web: false,
  });
  assert.equal(classifyChangedPaths(['desktop/Packaging/AppIcon.png']).assets, true);
});

test('root Node metadata and test scripts select Node checks', () => {
  for (const file of ['package-lock.json', '.github/workflows/sast.yml', 'scripts/test/workflows.test.mjs', 'ops/lib/config.mjs']) {
    assert.equal(classifyChangedPaths([file]).node, true, file);
  }
  assert.equal(classifyChangedPaths(['package.json']).desktop, true);
});
