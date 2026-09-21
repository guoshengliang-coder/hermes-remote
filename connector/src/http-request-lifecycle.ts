export class InFlightHttpRequests {
  private readonly controllers = new Map<string, AbortController>();

  begin(requestId: string): AbortController {
    this.controllers.get(requestId)?.abort(new Error("request_replaced"));
    const controller = new AbortController();
    this.controllers.set(requestId, controller);
    return controller;
  }

  cancel(requestId: string, reason: string): boolean {
    const controller = this.controllers.get(requestId);
    if (!controller || controller.signal.aborted) return false;
    controller.abort(new Error(reason));
    return true;
  }

  finish(requestId: string, controller: AbortController): void {
    if (this.controllers.get(requestId) === controller) this.controllers.delete(requestId);
  }

  abortAll(reason: string): number {
    let aborted = 0;
    for (const controller of this.controllers.values()) {
      if (controller.signal.aborted) continue;
      controller.abort(new Error(reason));
      aborted += 1;
    }
    this.controllers.clear();
    return aborted;
  }

  get size(): number { return this.controllers.size; }
}

interface ChunkWaiter {
  resolve: () => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
  detachAbort: () => void;
}

export class ResponseChunkWaiters {
  private readonly waiters = new Map<string, ChunkWaiter>();

  wait(requestId: string, sequence: number, timeoutMs: number, signal: AbortSignal): Promise<void> {
    if (signal.aborted) return Promise.reject(abortError(signal));
    const key = `${requestId}:${sequence}`;
    return new Promise<void>((resolve, reject) => {
      const settle = (error?: Error): void => {
        const waiter = this.waiters.get(key);
        if (!waiter) return;
        this.waiters.delete(key);
        clearTimeout(waiter.timer);
        waiter.detachAbort();
        if (error) reject(error); else resolve();
      };
      const onAbort = (): void => settle(abortError(signal));
      signal.addEventListener("abort", onAbort, { once: true });
      const timer = setTimeout(() => settle(new Error("response_chunk_ack_timeout")), timeoutMs);
      this.waiters.set(key, {
        resolve: () => settle(),
        reject: (error) => settle(error),
        timer,
        detachAbort: () => signal.removeEventListener("abort", onAbort),
      });
      // Abort may have raced the initial check before the waiter was registered.
      if (signal.aborted) settle(abortError(signal));
    });
  }

  acknowledge(requestId: string, sequence: number): boolean {
    const waiter = this.waiters.get(`${requestId}:${sequence}`);
    if (!waiter) return false;
    waiter.resolve();
    return true;
  }

  rejectAll(error: Error): void {
    for (const waiter of [...this.waiters.values()]) waiter.reject(error);
  }

  get size(): number { return this.waiters.size; }
}

function abortError(signal: AbortSignal): Error {
  return signal.reason instanceof Error ? signal.reason : new Error("request_cancelled");
}
