import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
import type { PushProvider, PushProviderName, PushWakeHint } from "./push-provider.js";
import type { PushRegistrationStore } from "./push-registration-store.js";

/**
 * Events worth waking a phone for: the run needs the user, finished, or ended without a confirmed
 * completion. Start/resume only move a running card the phone shows while it is already awake.
 */
const PUSHED_EVENTS: ReadonlySet<string> = new Set([
  "run.waiting",
  "run.completed",
  "run.interrupted",
  "run.unknown",
]);

export function shouldPush(event: SessionLifecycleEvent["event"]): boolean {
  return PUSHED_EVENTS.has(event);
}

export function wakeHint(event: SessionLifecycleEvent): PushWakeHint {
  return {
    eventId: event.eventId,
    event: event.event,
    state: event.state,
    deviceId: event.deviceId,
    storedSessionId: event.storedSessionId,
    runtimeSessionId: event.runtimeSessionId,
    ...(event.profile ? { profile: event.profile } : {}),
    occurredAt: event.occurredAt,
  };
}

export interface PushFanoutMetrics {
  sent: number;
  failed: number;
  tokensDropped: number;
}

export class PushFanout {
  private readonly providers: Map<PushProviderName, PushProvider>;
  private readonly metrics: PushFanoutMetrics = { sent: 0, failed: 0, tokensDropped: 0 };

  constructor(
    private readonly store: PushRegistrationStore,
    providers: PushProvider[],
    private readonly reportFailure: (message: string) => void = (message) => console.error(message),
  ) {
    this.providers = new Map(providers.map((provider) => [provider.name, provider]));
  }

  get providerNames(): PushProviderName[] {
    return [...this.providers.keys()];
  }

  snapshot(): PushFanoutMetrics {
    return { ...this.metrics };
  }

  /**
   * Called after a lifecycle event is durably stored. Never throws: a push is only a wake hint, so
   * a failed send must not fail the Connector's ack — the phone's periodic inbox sync still runs.
   */
  async notify(accountId: string, event: SessionLifecycleEvent): Promise<void> {
    if (!shouldPush(event.event)) return;
    let targets;
    try {
      targets = await this.store.listTargets(accountId);
    } catch {
      this.metrics.failed += 1;
      this.reportFailure("push fan-out could not load registrations");
      return;
    }
    const hint = wakeHint(event);
    await Promise.all(targets.map(async (target) => {
      const provider = this.providers.get(target.provider);
      if (!provider) return;
      const result = await provider.send(target.token, hint);
      if (result === "sent") {
        this.metrics.sent += 1;
        return;
      }
      if (result === "token_invalid") {
        this.metrics.tokensDropped += 1;
        await this.store.removeToken(target.installationId, target.token).catch(() => {
          this.reportFailure("push fan-out could not drop an invalid registration");
        });
        return;
      }
      this.metrics.failed += 1;
      this.reportFailure(`push fan-out send failed provider=${target.provider}`);
    }));
  }
}
