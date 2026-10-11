import { createContext } from "preact";
import { useContext, useLayoutEffect } from "preact/hooks";
import type { RefObject } from "preact";
import type { AppError } from "../errors";
import type { Route } from "../app/router";

export interface StartupGate {
  readonly pending: boolean;
  bind(retry: () => void, timeout: () => void): () => void;
  complete(): void;
  fail(error: AppError): void;
  language(language: "zh" | "en"): void;
  dispose(): void;
}
declare global { interface Window { hermesStartup?: StartupGate } }

export const StartupContext = createContext<{
  target: Route["name"];
  report: (error?: AppError | null) => void;
} | null>(null);

/** Only the initial target reports. A split-pane sidebar and every later refresh are background. */
export function useStartupPage(page: Route["name"], ready: boolean, error?: AppError | null, root?: RefObject<HTMLElement>) {
  const startup = useContext(StartupContext);
  useLayoutEffect(() => {
    if (!startup || startup.target !== page || (!ready && !error)) return;
    if (error) { startup.report(error); return; }
    const element = root?.current;
    const check = () => {
      if (element?.querySelector('.markdown[aria-busy="true"]')) return;
      startup.report();
    };
    // Stored messages may have arrived before the deferred Markdown has painted.
    const observer = element ? new MutationObserver(check) : null;
    if (element) observer?.observe(element, { attributes: true, attributeFilter: ["aria-busy"], childList: true, subtree: true });
    check();
    return () => observer?.disconnect();
  }, [startup, page, ready, error, root]);
}
