export interface EmailProviderReceipt {
  providerMessageId: string;
}

// Resend may retry or deliver events long after submission. Keep opaque correlation state slightly
// longer than the controller's 31-day accepted event-time window, then remove it in bounded batches.
export const EMAIL_DELIVERY_CORRELATION_RETENTION_MS = 35 * 24 * 60 * 60 * 1_000;

export type EmailProviderSubmission =
  | { status: "accepted"; providerMessageId: string }
  | { status: "failed" };

export type EmailDeliveryMessageKind = "email_otp" | "device_share";

export type EmailDeliveryEventType =
  | "email.sent"
  | "email.delivery_delayed"
  | "email.delivered"
  | "email.complained"
  | "email.bounced"
  | "email.failed"
  | "email.suppressed";

export interface EmailDeliveryEvent {
  webhookId: string;
  providerMessageId: string;
  messageKind: EmailDeliveryMessageKind;
  messageId: string;
  eventType: EmailDeliveryEventType;
  eventCreatedAt: Date;
  receivedAt: Date;
}

export interface EmailDeliveryEventStore {
  record(event: EmailDeliveryEvent): Promise<"applied" | "duplicate" | "unmatched" | "stale">;
}
