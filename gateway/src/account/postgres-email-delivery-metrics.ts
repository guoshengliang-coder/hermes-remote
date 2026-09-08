import type { Pool } from "pg";

export interface EmailDeliveryCounters {
  requested: number;
  providerAccepted: number;
  providerFailed: number;
  pending: number;
  finalDelivered: number;
  finalHardFailed: number;
  finalDelayed: number;
}

export interface AccountEmailDeliveryMetrics {
  observedAt: string;
  windowStartedAt: string;
  windowSeconds: 3600;
  emailOtp: EmailDeliveryCounters & { verified: number };
  deviceShare: EmailDeliveryCounters & { invitationAccepted: number };
}

interface OtpMetricRow {
  requested: string;
  provider_accepted: string;
  provider_failed: string;
  pending: string;
  verified: string;
  final_delivered: string;
  final_hard_failed: string;
  final_delayed: string;
}

interface ShareMetricRow {
  requested: string;
  provider_accepted: string;
  provider_failed: string;
  pending: string;
  invitation_accepted: string;
  final_delivered: string;
  final_hard_failed: string;
  final_delayed: string;
}

export class PostgresEmailDeliveryMetrics {
  constructor(
    private readonly pool: Pick<Pool, "query">,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async snapshot(): Promise<AccountEmailDeliveryMetrics> {
    const observedAt = this.now();
    const windowStartedAt = new Date(observedAt.getTime() - 60 * 60 * 1_000);
    const [otp, share] = await Promise.all([
      this.pool.query<OtpMetricRow>(
        `SELECT count(*)::text AS requested,
                count(*) FILTER (WHERE provider_message_id IS NOT NULL)::text AS provider_accepted,
                count(*) FILTER (WHERE provider_message_id IS NULL AND delivery_status = 'failed')::text AS provider_failed,
                count(*) FILTER (WHERE provider_message_id IS NULL AND delivery_status = 'pending')::text AS pending,
                count(*) FILTER (WHERE final_delivery_status = 'delivered')::text AS final_delivered,
                count(*) FILTER (WHERE final_delivery_status IN ('bounced', 'complained', 'failed', 'suppressed'))::text AS final_hard_failed,
                count(*) FILTER (WHERE final_delivery_status = 'delivery_delayed')::text AS final_delayed,
                count(*) FILTER (WHERE consumed_at IS NOT NULL)::text AS verified
           FROM email_otp_challenges
          WHERE created_at >= $1`,
        [windowStartedAt],
      ),
      this.pool.query<ShareMetricRow>(
        `SELECT count(*)::text AS requested,
                count(*) FILTER (WHERE provider_message_id IS NOT NULL)::text AS provider_accepted,
                count(*) FILTER (WHERE provider_message_id IS NULL AND delivery_status = 'failed')::text AS provider_failed,
                count(*) FILTER (WHERE provider_message_id IS NULL AND delivery_status = 'pending')::text AS pending,
                count(*) FILTER (WHERE final_delivery_status = 'delivered')::text AS final_delivered,
                count(*) FILTER (WHERE final_delivery_status IN ('bounced', 'complained', 'failed', 'suppressed'))::text AS final_hard_failed,
                count(*) FILTER (WHERE final_delivery_status = 'delivery_delayed')::text AS final_delayed,
                count(*) FILTER (WHERE status = 'accepted')::text AS invitation_accepted
           FROM device_share_invitations
          WHERE created_at >= $1`,
        [windowStartedAt],
      ),
    ]);
    return {
      observedAt: observedAt.toISOString(),
      windowStartedAt: windowStartedAt.toISOString(),
      windowSeconds: 3600,
      emailOtp: {
        requested: count(otp.rows[0]?.requested),
        providerAccepted: count(otp.rows[0]?.provider_accepted),
        providerFailed: count(otp.rows[0]?.provider_failed),
        pending: count(otp.rows[0]?.pending),
        finalDelivered: count(otp.rows[0]?.final_delivered),
        finalHardFailed: count(otp.rows[0]?.final_hard_failed),
        finalDelayed: count(otp.rows[0]?.final_delayed),
        verified: count(otp.rows[0]?.verified),
      },
      deviceShare: {
        requested: count(share.rows[0]?.requested),
        providerAccepted: count(share.rows[0]?.provider_accepted),
        providerFailed: count(share.rows[0]?.provider_failed),
        pending: count(share.rows[0]?.pending),
        finalDelivered: count(share.rows[0]?.final_delivered),
        finalHardFailed: count(share.rows[0]?.final_hard_failed),
        finalDelayed: count(share.rows[0]?.final_delayed),
        invitationAccepted: count(share.rows[0]?.invitation_accepted),
      },
    };
  }
}

function count(value: string | undefined): number {
  if (!value || !/^\d+$/.test(value)) throw new Error("email delivery metric count is invalid");
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw new Error("email delivery metric count is unsafe");
  return parsed;
}
