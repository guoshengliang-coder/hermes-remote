import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { lstat, mkdir, open, readFile, readlink, rename, symlink, unlink } from "node:fs/promises";
import path from "node:path";
import { runtimeImageIds } from "./config.mjs";
import { OpsError } from "./errors.mjs";

export function createCommandRunner({ timeoutMs = 15_000 } = {}) {
  return {
    run(command, args = [], { allowFailure = false, timeout = timeoutMs } = {}) {
      if (typeof command !== "string" || command.includes("/") || !Array.isArray(args) || args.some((arg) => typeof arg !== "string")) {
        throw new OpsError("config", "unsafe_command_invocation", "command_validate");
      }
      const result = spawnSync(command, args, {
        encoding: "utf8",
        maxBuffer: 1024 * 1024,
        timeout,
        shell: false,
        stdio: ["ignore", "pipe", "pipe"],
      });
      const status = Number.isInteger(result.status) ? result.status : 1;
      const response = {
        status,
        stdout: String(result.stdout ?? "").slice(0, 1024 * 1024),
        stderr: String(result.stderr ?? "").slice(0, 1024 * 1024),
      };
      if (result.error && !allowFailure) {
        throw new OpsError("config", `${command}_execution_failed`, "command_execute", { cause: result.error });
      }
      if (status !== 0 && !allowFailure) {
        throw new OpsError("bootstrap", `${command}_returned_${status}`, "command_execute");
      }
      return response;
    },
  };
}

export function renderGatewayEnvironment(config) {
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

export function renderSystemdUnit(config, manifest, runtimeImageId = manifest.imageId) {
  if (!runtimeImageIds(manifest).includes(runtimeImageId)) {
    throw new OpsError("artifact", "runtime_image_identity_mismatch", "configuration_install");
  }
  const { configRoot, stateRoot } = config.paths;
  const service = config.service;
  return `[Unit]
Description=Hermes GO Gateway (${config.environment})
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=-/usr/bin/docker rm --force ${service.containerName}
ExecStart=/usr/bin/docker run --name ${service.containerName} --read-only --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m,uid=1000,gid=1000 --cap-drop=ALL --security-opt=no-new-privileges --memory=256m --cpus=1 --pids-limit=128 --publish 127.0.0.1:${service.gatewayPort}:8787 --env-file ${configRoot}/gateway.env --mount type=bind,src=${configRoot}/secrets,dst=/run/hermes-go/secrets,readonly --mount type=bind,src=${stateRoot}/gateway,dst=/var/lib/hermes-go --log-driver=local --log-opt max-size=10m --log-opt max-file=3 ${runtimeImageId}
ExecStop=/usr/bin/docker stop --time 20 ${service.containerName}
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

export function renderNginxConfig(config) {
  const { serverName, listenPort } = config.nginx;
  const gateway = `http://127.0.0.1:${config.service.gatewayPort}`;
  return `server {
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
        proxy_pass ${gateway}/healthz;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
    }

    location = /readyz {
        proxy_pass ${gateway}/readyz;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
    }

    location = /relay-health {
        proxy_pass ${gateway}/health;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
    }

    location = /v2/capabilities {
        proxy_pass ${gateway}/v2/capabilities;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
    }

    location = /api/ws {
        proxy_pass ${gateway};
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location /api/ {
        proxy_pass ${gateway};
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
    }

    location = /v1/connect {
        proxy_pass ${gateway}/v1/connect;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location / {
        return 404;
    }
}
`;
}

export async function ensureManagedDirectory(directory, mode, owner) {
  await assertNoSymlinkAncestors(directory);
  await mkdir(directory, { recursive: true, mode });
  const info = await lstat(directory);
  if (info.isSymbolicLink() || !info.isDirectory()) {
    throw new OpsError("bootstrap", "managed_directory_unsafe", "filesystem_prepare");
  }
  await import("node:fs/promises").then(async ({ chmod, chown }) => {
    await chmod(directory, mode);
    if (owner) await chown(directory, owner.uid, owner.gid);
  });
}

export async function atomicWrite(filePath, content, mode, owner) {
  await assertNoSymlinkAncestors(path.dirname(filePath));
  await mkdir(path.dirname(filePath), { recursive: true, mode: 0o755 });
  const parentInfo = await lstat(path.dirname(filePath));
  if (parentInfo.isSymbolicLink() || !parentInfo.isDirectory()) {
    throw new OpsError("bootstrap", "managed_parent_unsafe", "configuration_install");
  }
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile()) {
      throw new OpsError("bootstrap", "managed_file_unsafe", "configuration_install");
    }
    const existing = await readFile(filePath);
    const next = Buffer.isBuffer(content) ? content : Buffer.from(content);
    if (existing.equals(next)) {
      await setOwnershipAndMode(filePath, mode, owner);
      return false;
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }

  const temporary = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${randomUUID()}.tmp`);
  let committed = false;
  try {
    const handle = await open(temporary, "wx", 0o600);
    try {
      await handle.writeFile(content);
      await handle.sync();
    } finally {
      await handle.close();
    }
    await setOwnershipAndMode(temporary, mode, owner);
    await rename(temporary, filePath);
    committed = true;
  } finally {
    if (!committed) await unlink(temporary).catch(() => {});
  }
  return true;
}

export async function installCurrentSymlink(installRoot, releaseName) {
  const currentPath = path.join(installRoot, "current");
  const expectedTarget = path.join("releases", releaseName);
  try {
    const info = await lstat(currentPath);
    if (!info.isSymbolicLink()) throw new OpsError("bootstrap", "current_release_not_symlink", "release_commit");
    const currentTarget = await readlink(currentPath);
    if (currentTarget !== expectedTarget) throw new OpsError("bootstrap", "existing_release_requires_deploy", "release_commit");
    return false;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }

  const temporary = path.join(installRoot, `.current.${randomUUID()}.tmp`);
  await symlink(expectedTarget, temporary);
  try {
    await rename(temporary, currentPath);
  } catch (error) {
    await unlink(temporary).catch(() => {});
    throw error;
  }
  return true;
}

export async function assertNoSymlinkAncestors(target) {
  const normalized = path.normalize(target);
  let cursor = path.parse(normalized).root;
  for (const segment of normalized.slice(cursor.length).split(path.sep).filter(Boolean)) {
    cursor = path.join(cursor, segment);
    try {
      const info = await lstat(cursor);
      if (info.isSymbolicLink()) throw new OpsError("bootstrap", "managed_path_contains_symlink", "filesystem_prepare");
    } catch (error) {
      if (error instanceof OpsError) throw error;
      if (error?.code === "ENOENT") return;
      throw error;
    }
  }
}

async function setOwnershipAndMode(filePath, mode, owner) {
  const { chmod, chown } = await import("node:fs/promises");
  await chmod(filePath, mode);
  if (owner) await chown(filePath, owner.uid, owner.gid);
}
