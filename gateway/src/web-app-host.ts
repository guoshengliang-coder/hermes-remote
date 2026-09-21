import { constants } from "node:fs";
import { open, realpath } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { extname, join, sep } from "node:path";
import { sendHttpError } from "./http-utils.js";

const MAX_FILE_BYTES = 16 * 1024 * 1024;

const CONTENT_TYPES: Readonly<Record<string, string>> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".webmanifest": "application/manifest+json",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
};

// Top-level files the service worker and the manifest must be able to re-fetch fresh: they carry
// no content hash, so a long cache would pin a stale shell (and block a rollback).
const REVALIDATED_FILES = new Set(["sw.js", "manifest.webmanifest"]);

export interface WebAppHostOptions {
  dir: string;
  /** Exact https origin of the Web app; adds its wss: form to connect-src for older WebKit. */
  webOrigin?: string;
}

// Serves the built Web app (web/dist) at /app/. The build is a separate artifact from the Gateway
// image: the directory is swapped on the host, so shipping the Web app never restarts the Gateway.
export class WebAppHost {
  private readonly documentHeaders: Record<string, string>;

  constructor(private readonly options: WebAppHostOptions) {
    const websocketOrigin = options.webOrigin && /^https:\/\/[A-Za-z0-9.-]+(?::\d{1,5})?$/.test(options.webOrigin)
      ? options.webOrigin.replace(/^https:/, "wss:")
      : undefined;
    this.documentHeaders = {
      ...BASE_HEADERS,
      "content-security-policy": [
        "default-src 'none'",
        "script-src 'self'",
        "style-src 'self'",
        "img-src 'self' blob: data:",
        `connect-src 'self'${websocketOrigin ? ` ${websocketOrigin}` : ""}`,
        "font-src 'self'",
        "worker-src 'self'",
        "manifest-src 'self'",
        "base-uri 'none'",
        "form-action 'self'",
        "frame-ancestors 'none'",
      ].join("; "),
      "cross-origin-opener-policy": "same-origin",
      "x-frame-options": "DENY",
      "permissions-policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
    };
  }

  handles(url: URL): boolean {
    return url.pathname === "/app" || url.pathname.startsWith("/app/");
  }

  async handle(request: IncomingMessage, response: ServerResponse, url: URL): Promise<void> {
    if (request.method !== "GET" && request.method !== "HEAD") {
      response.writeHead(405, { allow: "GET, HEAD", "content-type": "application/json" });
      response.end(JSON.stringify({ error: "method_not_allowed" }));
      return;
    }
    if (url.pathname === "/app") {
      response.writeHead(308, { location: `/app/${url.search}`, "cache-control": "no-store" });
      response.end();
      return;
    }
    const relative = safeRelativePath(url.pathname.slice("/app/".length));
    if (relative === undefined) {
      sendHttpError(response, 404, "not_found");
      return;
    }
    if (relative.startsWith("assets/")) {
      await this.sendFile(request, response, relative, "public, max-age=31536000, immutable");
      return;
    }
    if (extname(relative) && relative !== "index.html") {
      const revalidate = REVALIDATED_FILES.has(relative) || relative.endsWith(".json");
      await this.sendFile(request, response, relative, revalidate ? "no-cache" : "public, max-age=3600");
      return;
    }
    // Everything else is a client-side route: the shell decides what to render. It never needs a
    // credential, so a cross-site navigation (where SameSite=Strict withholds the cookies) still
    // loads the page, and its same-origin API calls then carry them.
    await this.sendFile(request, response, "index.html", "no-store");
  }

  private async sendFile(
    request: IncomingMessage,
    response: ServerResponse,
    relative: string,
    cacheControl: string,
  ): Promise<void> {
    const contentType = CONTENT_TYPES[extname(relative).toLowerCase()];
    const body = contentType ? await this.read(relative) : undefined;
    if (!contentType || !body) {
      sendHttpError(response, 404, "not_found");
      return;
    }
    const headers: Record<string, string> = {
      ...(relative.endsWith(".html") ? this.documentHeaders : BASE_HEADERS),
      "content-type": contentType,
      "content-length": String(body.length),
      "cache-control": cacheControl,
    };
    if (relative === "sw.js") headers["service-worker-allowed"] = "/app/";
    response.writeHead(200, headers);
    response.end(request.method === "HEAD" ? undefined : body);
  }

  private async read(relative: string): Promise<Buffer | undefined> {
    try {
      const root = await realpath(this.options.dir);
      const candidate = join(root, relative);
      // realpath resolves any symlink inside the build, so a link pointing outside it is refused
      // rather than followed.
      const resolved = await realpath(candidate);
      if (!resolved.startsWith(root + sep)) return undefined;
      const handle = await open(resolved, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        const stat = await handle.stat();
        if (!stat.isFile() || stat.size > MAX_FILE_BYTES) return undefined;
        return await handle.readFile();
      } finally {
        await handle.close();
      }
    } catch {
      return undefined;
    }
  }
}

const BASE_HEADERS: Readonly<Record<string, string>> = {
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "cross-origin-resource-policy": "same-origin",
};

function safeRelativePath(raw: string): string | undefined {
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return undefined;
  }
  if (decoded.endsWith("/")) decoded = decoded.slice(0, -1);
  if (decoded === "") return "index.html";
  if (decoded.length > 256 || /[\u0000-\u001f\u007f\\]/.test(decoded)) return undefined;
  const segments = decoded.split("/");
  if (segments.some((segment) => segment === "" || segment === "." || segment === ".."
      || segment.startsWith("."))) {
    return undefined;
  }
  return decoded;
}
