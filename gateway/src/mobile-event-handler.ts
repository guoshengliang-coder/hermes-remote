import type { IncomingMessage, ServerResponse } from "node:http";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { AccountModeError, accountErrors } from "./account/model.js";
import type { LifecycleEventStore } from "./lifecycle-event-store.js";
import {
  nonNegativeIntegerQuery,
  parseEventIds,
  positiveIntegerQuery,
  readRequestBody,
  sendHttpError,
  sendJson,
} from "./http-utils.js";

export async function handleLegacyMobileEvents(
  request: IncomingMessage,
  response: ServerResponse,
  url: URL,
  lifecycleEvents: LifecycleEventStore,
  maxBodyBytes: number,
): Promise<void> {
  if (url.pathname === "/api/mobile/events" && request.method === "GET") {
    const after = nonNegativeIntegerQuery(url, "after", 0);
    const limit = positiveIntegerQuery(url, "limit", 100, 500);
    if (after === undefined || limit === undefined) {
      sendHttpError(response, 400, "invalid_query");
      return;
    }
    const page = await lifecycleEvents.list(after, limit);
    sendJson(response, 200, page);
    return;
  }

  if ((url.pathname === "/api/mobile/events/ack" || url.pathname === "/api/mobile/events/read")
      && request.method === "POST") {
    let body: Buffer;
    try {
      body = await readRequestBody(request, Math.min(maxBodyBytes, 256 * 1024));
    } catch {
      sendHttpError(response, 413, "request_too_large");
      return;
    }
    let eventIds: string[];
    try {
      eventIds = parseEventIds(body);
    } catch {
      sendHttpError(response, 400, "invalid_request");
      return;
    }
    const changed = url.pathname.endsWith("/read")
      ? await lifecycleEvents.markRead(eventIds)
      : await lifecycleEvents.markDelivered(eventIds);
    sendJson(response, 200, { ok: true, changed });
    return;
  }

  sendHttpError(response, 404, "not_found");
}

export async function handleAccountMobileEvents(
  request: IncomingMessage,
  response: ServerResponse,
  url: URL,
  authorization: string,
  control: AccountGatewayControl | undefined,
  maxBodyBytes: number,
  sendAccountError: (response: ServerResponse, error: unknown) => void,
): Promise<void> {
  try {
    if (!control) throw accountErrors.featureDisabled();
    const principal = await control.authenticate(authorization);
    if (url.pathname === "/api/mobile/events" && request.method === "GET") {
      const after = nonNegativeIntegerQuery(url, "after", 0);
      const limit = positiveIntegerQuery(url, "limit", 100, 500);
      if (after === undefined || limit === undefined) {
        throw accountErrors.invalidRequest("Lifecycle event pagination is invalid.");
      }
      sendJson(response, 200, await control.listLifecycleEvents(principal, after, limit));
      return;
    }

    if ((url.pathname === "/api/mobile/events/ack" || url.pathname === "/api/mobile/events/read")
        && request.method === "POST") {
      let body: Buffer;
      try {
        body = await readRequestBody(request, Math.min(maxBodyBytes, 256 * 1024));
      } catch {
        throw accountErrors.invalidRequest("Lifecycle event acknowledgement is too large.");
      }
      let eventIds: string[];
      try {
        eventIds = parseEventIds(body);
      } catch {
        throw accountErrors.invalidRequest("Lifecycle event acknowledgement is invalid.");
      }
      const changed = await control.markLifecycleEvents(
        principal,
        eventIds,
        url.pathname.endsWith("/read") ? "read" : "delivered",
      );
      sendJson(response, 200, { ok: true, changed });
      return;
    }

    throw new AccountModeError(
      404,
      "HR-ACCOUNT-004",
      "The account endpoint was not found.",
      false,
      "none",
    );
  } catch (error) {
    sendAccountError(response, error);
  }
}
