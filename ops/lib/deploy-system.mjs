import path from "node:path";
import { runtimeImageIds } from "./config.mjs";
import { OpsError } from "./errors.mjs";

export const DEPLOY_SLOTS = Object.freeze(["blue", "green"]);

export function otherSlot(slot) {
  assertSlot(slot);
  return slot === "blue" ? "green" : "blue";
}

export function renderDeployGatewayEnvironment(config, slot) {
  assertSlot(slot);
  return [
    "PORT=8787",
    "HOST=0.0.0.0",
    "APP_TOKEN_FILE=/run/hermes-go/secrets/app-token",
    "CONNECTOR_TOKEN_FILE=/run/hermes-go/secrets/connector-token",
    "INTERNAL_STATUS_TOKEN_FILE=/run/hermes-go/secrets/internal-status-token",
    `DEFAULT_DEVICE_ID=${config.gateway.defaultDeviceId}`,
    "LIFECYCLE_EVENT_STORE_FILE=/var/lib/hermes-go/lifecycle-events.json",
    "ACCOUNT_AUTH_ENABLED=0",
    "ACCOUNT_BINDING_ENABLED=0",
    "MAX_LIFECYCLE_EVENTS=10000",
    "",
  ].join("\n");
}

export function renderDeploySystemdUnit(config, manifest, slot, runtimeImageId = manifest.imageId) {
  assertSlot(slot);
  assertRuntimeImageId(manifest, runtimeImageId);
  const selected = config.slots[slot];
  if (!selected) throw new OpsError("deployment", "candidate_slot_missing", "candidate_template");
  const { configRoot, stateRoot } = config.paths;
  const environmentPath = path.join(configRoot, "slots", slot, "gateway.env");
  const statePath = path.join(stateRoot, "gateway-slots", slot);
  return `[Unit]
Description=Hermes GO Gateway ${slot} (${config.environment})
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=-/usr/bin/docker rm --force ${selected.containerName}
ExecStart=/usr/bin/docker run --name ${selected.containerName} --read-only --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m,uid=1000,gid=1000 --cap-drop=ALL --security-opt=no-new-privileges --memory=256m --cpus=1 --pids-limit=128 --publish 127.0.0.1:${selected.gatewayPort}:8787 --env-file ${environmentPath} --mount type=bind,src=${configRoot}/secrets,dst=/run/hermes-go/secrets,readonly --mount type=bind,src=${statePath},dst=/var/lib/hermes-go --log-driver=local --log-opt max-size=10m --log-opt max-file=3 ${runtimeImageId}
ExecStop=/usr/bin/docker stop --time 20 ${selected.containerName}
Restart=always
RestartSec=3
TimeoutStartSec=90
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictRealtime=true
LockPersonality=true
SystemCallArchitectures=native
CapabilityBoundingSet=
UMask=0077

[Install]
WantedBy=multi-user.target
`;
}

function assertRuntimeImageId(manifest, runtimeImageId) {
  if (!runtimeImageIds(manifest).includes(runtimeImageId)) {
    throw new OpsError("artifact", "runtime_image_identity_mismatch", "candidate_template");
  }
}

export function renderNginxUpstream(config, slot) {
  assertSlot(slot);
  const selected = config.slots[slot];
  if (!selected) throw new OpsError("deployment", "candidate_slot_missing", "upstream_template");
  return `upstream ${upstreamName(config)} {
    server 127.0.0.1:${selected.gatewayPort};
    keepalive 32;
}
`;
}

export function renderDeployNginxConfig(config) {
  const { serverName, listenPort, upstreamConfigFile } = config.nginx;
  return `include ${upstreamConfigFile};

server {
    listen ${listenPort} ssl;
    server_name ${serverName};

    ssl_certificate ${config.paths.configRoot}/tls/fullchain.pem;
    ssl_certificate_key ${config.paths.configRoot}/tls/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:HermesStagingTLS:10m;
    ssl_session_timeout 1d;
    server_tokens off;
    client_max_body_size 10m;

    location = /healthz {
        proxy_pass http://${upstreamName(config)}/healthz;
        ${commonProxyHeaders(75)}
    }

    location = /readyz {
        proxy_pass http://${upstreamName(config)}/readyz;
        ${commonProxyHeaders(75)}
    }

    location = /relay-health {
        proxy_pass http://${upstreamName(config)}/health;
        ${commonProxyHeaders(75)}
    }

    location = /v2/capabilities {
        proxy_pass http://${upstreamName(config)}/v2/capabilities;
        ${commonProxyHeaders(75)}
    }

    location = /api/ws {
        proxy_pass http://${upstreamName(config)};
        ${webSocketProxyHeaders()}
    }

    location /api/ {
        proxy_pass http://${upstreamName(config)};
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        ${forwardedHeaders()}
        proxy_connect_timeout 5s;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
    }

    location = /v1/connect {
        proxy_pass http://${upstreamName(config)}/v1/connect;
        ${webSocketProxyHeaders()}
    }

    location / {
        return 404;
    }
}
`;
}

function upstreamName(config) {
  return config.managedBaseline === true ? "hermes_go_gateway_production" : "hermes_go_gateway_staging";
}

function commonProxyHeaders(timeoutSeconds) {
  return `${forwardedHeaders()}
        proxy_connect_timeout 5s;
        proxy_read_timeout ${timeoutSeconds}s;
        proxy_send_timeout ${timeoutSeconds}s;`;
}

function webSocketProxyHeaders() {
  return `proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        ${forwardedHeaders()}
        proxy_connect_timeout 5s;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;`;
}

function forwardedHeaders() {
  return `proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;`;
}

function assertSlot(slot) {
  if (!DEPLOY_SLOTS.includes(slot)) {
    throw new OpsError("deployment", "slot_must_be_blue_or_green", "candidate_slot");
  }
}
