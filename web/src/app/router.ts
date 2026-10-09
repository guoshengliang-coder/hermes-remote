import { useEffect, useState } from "preact/hooks";
import { deferUntilOverlayRewind, yieldPageEntries, yieldTopEntry } from "./overlayHistory";

// A tiny History-API router under /app/. URLs only ever SELECT what to show: nothing reachable
// from a path, query or hash performs a mutation (docs/ACCOUNT_MODE_SECURITY.md, Web app).

export const BASE = "/app/";

export type Route =
  | { name: "list" }
  | { name: "chat"; sessionId: string }
  | { name: "new" }
  | { name: "archived" }
  | { name: "account" }
  | { name: "login" };

/** Stored session ids Hermes mints (e.g. 20260921_101500_ab12cd); anything else is not a route. */
const SESSION_ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;

export function matchRoute(pathname: string): Route {
  if (!pathname.startsWith(BASE)) return { name: "list" };
  const rest = pathname.slice(BASE.length).replace(/\/+$/, "");
  if (rest === "") return { name: "list" };
  if (rest === "new") return { name: "new" };
  if (rest === "archived") return { name: "archived" };
  if (rest === "account") return { name: "account" };
  if (rest === "login") return { name: "login" };
  const chat = /^s\/([^/]+)$/.exec(rest);
  if (chat) {
    let id: string;
    try {
      id = decodeURIComponent(chat[1]!);
    } catch {
      return { name: "list" };
    }
    if (SESSION_ID.test(id)) return { name: "chat", sessionId: id };
  }
  return { name: "list" };
}

export function routePath(route: Route): string {
  switch (route.name) {
    case "list":
      return BASE;
    case "new":
      return `${BASE}new`;
    case "archived":
      return `${BASE}archived`;
    case "account":
      return `${BASE}account`;
    case "login":
      return `${BASE}login`;
    case "chat":
      return `${BASE}s/${encodeURIComponent(route.sessionId)}`;
  }
}

const CHANGE = "hermes-go:navigate";
interface PageEntry { id: number; path: string; returnSteps?: number }
let pageSequence = 0;
const knownPages = new Set<number>();
const snapshots = new Map<string, unknown>();

function pageEntry(): PageEntry {
  const page = history.state?.hrPage as PageEntry | undefined;
  if (page && knownPages.has(page.id) && page.path === location.pathname) return page;
  const initial = { id: ++pageSequence, path: location.pathname };
  knownPages.add(initial.id);
  history.replaceState({ ...history.state, hrPage: initial }, "");
  return initial;
}

/** Per browser entry and Mac: search and scroll survive both toolbar and system Back. */
export function pageSnapshotKey(scope: string): string { return `${pageEntry().id}:${scope}`; }
export function readPageSnapshot<T>(key: string): T | undefined { return snapshots.get(key) as T | undefined; }
export function savePageSnapshot(key: string, value: unknown): void {
  snapshots.set(key, value);
  if (snapshots.size > 50) snapshots.delete(snapshots.keys().next().value!);
}
export function clearPageSnapshots(): void { snapshots.clear(); knownPages.clear(); }

export function navigate(route: Route, options: { replace?: boolean } = {}): void {
  if (deferUntilOverlayRewind(() => navigate(route, options))) return;
  const path = routePath(route);
  if (path === location.pathname && !location.search && !location.hash) return;
  const source = pageEntry();
  const sourceRoute = currentRoute();
  const leaf = route.name === "chat" || route.name === "new";
  const sourceLeaf = sourceRoute.name === "chat" || sourceRoute.name === "new";
  // A leaf replaces the existing chat, including its focused-composer history steps. Rewind
  // those steps first so system Back from the new chat reaches the same original list entry.
  if (leaf && sourceLeaf) {
    const overlays = yieldPageEntries();
    const next: PageEntry = { ...source, path };
    const replaceLeaf = () => {
      history.replaceState({ hrPage: next }, "", path);
      window.dispatchEvent(new Event(CHANGE));
    };
    if (overlays) {
      window.addEventListener("popstate", replaceLeaf, { once: true, capture: true });
      history.go(-overlays);
    } else replaceLeaf();
    return;
  }
  const overlay = yieldTopEntry();
  const replace = overlay || options.replace;
  const next: PageEntry = { id: replace && !overlay ? source.id : ++pageSequence, path };
  knownPages.add(next.id);
  if (leaf) {
    if ((sourceRoute.name === "list" || sourceRoute.name === "archived") && source.path === routePath(sourceRoute)) next.returnSteps = 1;
  }
  // Leaving from an open sheet or viewer takes its history entry's place, so back from the new
  // page does not land on a dead "overlay open" step (app/overlayHistory.ts).
  if (replace) history.replaceState({ hrPage: next }, "", path);
  else history.pushState({ hrPage: next }, "", path);
  window.dispatchEvent(new Event(CHANGE));
}

/** Toolbar Back leaves the chat, including composer/other same-page overlay steps. */
export function returnFromChat(): void {
  if (deferUntilOverlayRewind(returnFromChat)) return;
  const page = pageEntry();
  if (page.returnSteps) history.go(-(page.returnSteps + yieldPageEntries()));
  else navigate({ name: "list" }, { replace: true });
}

export function currentRoute(): Route {
  return matchRoute(location.pathname);
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(currentRoute);
  useEffect(() => {
    // Overlay back steps fire popstate on the same URL: keep the same route object then.
    const update = () => setRoute((prev) => {
      const next = currentRoute();
      return sameRoute(prev, next) ? prev : next;
    });
    window.addEventListener("popstate", update);
    window.addEventListener(CHANGE, update);
    return () => {
      window.removeEventListener("popstate", update);
      window.removeEventListener(CHANGE, update);
    };
  }, []);
  return route;
}

export function sameRoute(a: Route, b: Route): boolean {
  return routePath(a) === routePath(b);
}
