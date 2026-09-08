import type { EmailOtpPurpose, TransactionalEmailSender } from "./email-otp-service.js";
import type { DeviceShareEmailSender } from "./account-sharing-model.js";
import type { EmailProviderReceipt } from "./email-delivery.js";

const RESEND_EMAIL_ENDPOINT = "https://api.resend.com/emails";
const MESSAGE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PROVIDER_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

export class ResendEmailSender implements TransactionalEmailSender, DeviceShareEmailSender {
  constructor(
    private readonly apiKey: string,
    private readonly from: string,
    private readonly userAgent: string,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly timeoutMs = 10_000,
  ) {
    if (!/^re_[A-Za-z0-9_-]{8,}$/.test(apiKey)) {
      throw new Error("ACCOUNT_RESEND_API_KEY must be a Resend API key");
    }
    if (from.length < 3 || from.length > 320 || /[\r\n]/.test(from)) {
      throw new Error("ACCOUNT_EMAIL_FROM must be a bounded single-line sender");
    }
    if (userAgent.length < 3 || userAgent.length > 128 || /[\r\n]/.test(userAgent)) {
      throw new Error("transactional email User-Agent must be bounded and single-line");
    }
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30_000) {
      throw new Error("transactional email timeout must be between 1 and 30000 milliseconds");
    }
  }

  async sendLoginCode(input: {
    messageId: string;
    recipient: string;
    code: string;
    purpose: EmailOtpPurpose;
    expiresInMinutes: number;
  }): Promise<EmailProviderReceipt> {
    if (!MESSAGE_ID_PATTERN.test(input.messageId)
        || !/^\d{6}$/.test(input.code)
        || !Number.isSafeInteger(input.expiresInMinutes)
        || input.expiresInMinutes < 1
        || input.expiresInMinutes > 60) {
      throw new Error("transactional email input is invalid");
    }

    return this.send({
      messageId: input.messageId,
      idempotencyNamespace: "email-otp",
      recipient: input.recipient,
      subject: subject(input.purpose),
      text: message(input.code, input.expiresInMinutes, input.purpose),
    });
  }

  async sendDeviceShareInvitation(input: {
    messageId: string;
    recipient: string;
    ownerDisplayName?: string;
    deviceDisplayName: string;
    acceptUrl: string;
    expiresInHours: number;
  }): Promise<EmailProviderReceipt> {
    let parsedUrl: URL;
    try {
      parsedUrl = new URL(input.acceptUrl);
    } catch {
      throw new Error("transactional email input is invalid");
    }
    if (!MESSAGE_ID_PATTERN.test(input.messageId)
        || input.recipient.length < 3 || input.recipient.length > 254 || /[\r\n]/.test(input.recipient)
        || input.deviceDisplayName.length < 1 || input.deviceDisplayName.length > 128
        || /[\r\n]/.test(input.deviceDisplayName)
        || (input.ownerDisplayName !== undefined
          && (input.ownerDisplayName.length < 1 || input.ownerDisplayName.length > 128
            || /[\r\n]/.test(input.ownerDisplayName)))
        || parsedUrl.protocol !== "https:"
        || input.acceptUrl.length > 2048
        || !Number.isSafeInteger(input.expiresInHours)
        || input.expiresInHours < 1 || input.expiresInHours > 168) {
      throw new Error("transactional email input is invalid");
    }
    const owner = input.ownerDisplayName ?? "一位 Hermes GO 用户 / A Hermes GO user";
    return this.send({
      messageId: input.messageId,
      idempotencyNamespace: "device-share",
      recipient: input.recipient,
      subject: "Hermes GO 整机共享邀请 / Whole-device sharing invitation",
      text: [
        `${owner} 邀请你使用 Mac「${input.deviceDisplayName}」上的 Hermes。`,
        "接受后，你可能看到该 Hermes 实例已有的会话、文件和配置元数据。此权限覆盖整台设备，并非单个项目或对话。",
        `${input.expiresInHours} 小时内接受：${input.acceptUrl}`,
        "如果你不认识邀请者，请忽略此邮件。",
        "",
        `${owner} invited you to use Hermes on the Mac “${input.deviceDisplayName}”.`,
        "If accepted, you may see existing sessions, files, and configuration metadata exposed by that Hermes instance. This grants whole-device access, not access to one project or conversation.",
        `Accept within ${input.expiresInHours} hours: ${input.acceptUrl}`,
        "If you do not recognize the inviter, ignore this email.",
      ].join("\n"),
    });
  }

  private async send(input: {
    messageId: string;
    idempotencyNamespace: "email-otp" | "device-share";
    recipient: string;
    subject: string;
    text: string;
  }): Promise<EmailProviderReceipt> {
    let response: Response;
    try {
      response = await this.fetchImpl(RESEND_EMAIL_ENDPOINT, {
        method: "POST",
        headers: {
          authorization: `Bearer ${this.apiKey}`,
          "content-type": "application/json",
          "idempotency-key": `${input.idempotencyNamespace}/${input.messageId}`,
          "user-agent": this.userAgent,
        },
        body: JSON.stringify({
          from: this.from,
          to: [input.recipient],
          subject: input.subject,
          text: input.text,
          tags: [
            { name: "hermes_kind", value: input.idempotencyNamespace.replace("-", "_") },
            { name: "hermes_message_id", value: input.messageId },
          ],
        }),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch {
      throw new Error("transactional_email_request_failed");
    }

    if (!response.ok) throw new Error("transactional_email_request_failed");
    try {
      const body: unknown = await response.json();
      if (!isRecord(body) || typeof body.id !== "string" || !PROVIDER_ID_PATTERN.test(body.id)) {
        throw new Error("invalid provider response");
      }
      return { providerMessageId: body.id };
    } catch {
      throw new Error("transactional_email_request_failed");
    }
  }
}

function subject(purpose: EmailOtpPurpose): string {
  switch (purpose) {
    case "sign_in": return "Hermes GO 登录验证码 / Sign-in code";
    case "link_identity": return "Hermes GO 绑定邮箱验证码 / Link email code";
    case "reauthenticate": return "Hermes GO 安全验证代码 / Security code";
  }
}

function message(code: string, minutes: number, purpose: EmailOtpPurpose): string {
  const action = purpose === "sign_in"
    ? "登录 Hermes GO"
    : purpose === "link_identity"
      ? "绑定 Hermes GO 邮箱"
      : "确认 Hermes GO 安全操作";
  const englishAction = purpose === "sign_in"
    ? "sign in to Hermes GO"
    : purpose === "link_identity"
      ? "link this email to Hermes GO"
      : "confirm a Hermes GO security action";
  return [
    `你的验证码是：${code}`,
    `此验证码用于${action}，${minutes} 分钟内有效。请勿转发。`,
    "如果这不是你的操作，请忽略此邮件。",
    "",
    `Your verification code is: ${code}`,
    `Use it to ${englishAction}. It expires in ${minutes} minutes. Do not share it.`,
    "If you did not request this, you can ignore this email.",
  ].join("\n");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
