import assert from "node:assert/strict";
import { test } from "node:test";
import { Readable } from "node:stream";
import type { IncomingMessage, ServerResponse } from "node:http";
import { GatewayHttpRouter } from "./gateway-http-router.js";
import { browserRouteFor, modelPreferenceMutation } from "./account/web-device-access.js";

const mutation = { instanceId: "00000000-0000-4000-8000-000000000001", provider: "p/a", model: "m::b", field: "favorite", baseRevision: "0", value: true };
test("browser model preferences admit only the exact field-CAS shape", () => {
  assert.equal(modelPreferenceMutation(mutation), true);
  for (const value of [{ ...mutation, profile: "other" }, { ...mutation, field: "api_key" }, { ...mutation, value: "true" }, { ...mutation, baseRevision: 0 }]) assert.equal(modelPreferenceMutation(value), false);
  assert.equal(modelPreferenceMutation({ ...mutation, field: "hidden", model: "", value: null }), true);
  assert.equal(browserRouteFor("GET", new URL("https://fixture/api/model/preferences"))?.noBody, true);
  assert.equal(browserRouteFor("POST", new URL("https://fixture/api/model/preferences"))?.body?.(mutation), true);
});

test("owner-only mutations are enforced for scoped native, implicit native and browser routes before forwarding", async () => {
  for (const path of ["/v2/devices/mac/api/model/preferences", "/api/model/preferences"]) {
    for (const method of ["POST", "GET"]) for (const access of ["owner", "operator"]) {
      const principal = { account: { id: "a" }, installation: { id: "i" } };
      let forwarded = 0, refusal: unknown;
      const connector = { deviceId: "mac", socket: {} };
      const router = new GatewayHttpRouter({
        accountControl: { authenticate: async () => principal, resolveDevice: async () => ({ access }) },
        serverRelease: { handle: async () => false },
        accountController: { handle: async () => {} },
        connectorRegistry: {}, httpTunnels: { forward: async () => { forwarded++; } },
        resolveAccountConnector: async () => connector,
        resolveAccountConnectorFor: async () => connector,
        sendAccountError: (_response: unknown, error: unknown) => { refusal = error; },
      } as never);
      const request = Object.assign(Readable.from([]), { url: path, method, headers: { host: "fixture", authorization: "Bearer fixture" } }) as IncomingMessage;
      await router.handle(request, {} as ServerResponse);
      if (method === "POST" && access === "operator") {
        assert.equal(forwarded, 0);
        assert.equal((refusal as { code: string }).code, "HR-MODEL-003");
      } else assert.equal(forwarded, 1);
    }
  }
});

test("a browser cookie cannot bypass owner-only model preference writes", async () => {
  const principal = { account: { id: "a" }, installation: { id: "browser" } };
  for (const access of ["owner", "operator"]) {
    let forwarded=0, refusal: unknown;
    const router=new GatewayHttpRouter({
      accountControl: { resolveDevice: async()=>({access}), webDeviceAccess: { presents:()=>true,authenticateRequest:async()=>principal } },
      serverRelease:{handle:async()=>false}, accountController:{handle:async()=>{}},
      httpTunnels:{forward:async()=>{forwarded++;}},resolveAccountConnectorFor:async()=>({deviceId:"mac",socket:{}}),
      sendAccountError:(_response:unknown,error:unknown)=>{refusal=error;},
    } as never);
    const payload=JSON.stringify(mutation);
    const request=Object.assign(Readable.from([Buffer.from(payload)]),{url:"/v2/devices/mac/api/model/preferences",method:"POST",headers:{host:"fixture",cookie:"fixture",'content-type':"application/json",'content-length':String(Buffer.byteLength(payload))}}) as IncomingMessage;
    await router.handle(request,{} as ServerResponse);
    assert.equal(forwarded,access==="owner"?1:0);
    if(access==="operator")assert.equal((refusal as {code:string}).code,"HR-MODEL-003");
  }
});
