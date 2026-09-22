import { useEffect, useState } from "preact/hooks";

// A tiny History-API router under /app/. URLs only ever SELECT what to show: nothing reachable
// from a path, query or hash performs a mutation (docs/ACCOUNT_MODE_SECURITY.md, Web app).

export const BASE = "/app/";

export type Route =
  | { name: "list" }
  | { name: "chat"; sessionId: string }
  | { name: "new" }
  | { name: "archived" }
  | { name: "login" };

/** Stored session ids Hermes mints (e.g. 20260921_101500_ab12cd); anything else is not a route. */
const SESSION_ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;

export function matchRoute(pathname: string): Route {
  if (!pathname.startsWith(BASE)) return { name: "list" };
  const rest = pathname.slice(BASE.length).replace(/\/+$/, "");
  if (rest === "") return { name: "list" };
  if (rest === "new") return { name: "new" };
  if (rest === "archived") return { name: "archived" };
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
    case "login":
      return `${BASE}login`;
    case "chat":
      return `${BASE}s/${encodeURIComponent(route.sessionId)}`;
  }
}

const CHANGE = "hermes-go:navigate";

export function navigate(route: Route, options: { replace?: boolean } = {}): void {
  const path = routePath(route);
  if (path === location.pathname && !location.search && !location.hash) return;
  if (options.replace) history.replaceState(null, "", path);
  else history.pushState(null, "", path);
  window.dispatchEvent(new Event(CHANGE));
}

export function currentRoute(): Route {
  return matchRoute(location.pathname);
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(currentRoute);
  useEffect(() => {
    const update = () => setRoute(currentRoute());
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
