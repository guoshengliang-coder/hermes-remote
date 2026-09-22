import { describe, expect, it } from "vitest";
import { matchRoute, routePath, type Route } from "./router";

describe("router", () => {
  it.each([
    ["/app/", { name: "list" }],
    ["/app", { name: "list" }],
    ["/app/new", { name: "new" }],
    ["/app/new/", { name: "new" }],
    ["/app/login", { name: "login" }],
    ["/app/s/20260921_101500_ab12cd", { name: "chat", sessionId: "20260921_101500_ab12cd" }],
    ["/app/s/stored-mock-1", { name: "chat", sessionId: "stored-mock-1" }],
  ] as Array<[string, Route]>)("%s", (path, route) => {
    expect(matchRoute(path)).toEqual(route);
  });

  it.each([
    "/app/s/",
    "/app/s/a/b",
    "/app/s/%2Fetc%2Fpasswd",
    "/app/s/..",
    "/app/s/%E0%A4%A",
    "/app/s/-leading-dash",
    "/app/whatever",
    "/elsewhere",
  ])("%s falls back to the list (URLs only select what to show)", (path) => {
    expect(matchRoute(path)).toEqual({ name: "list" });
  });

  it("round-trips every route", () => {
    const routes: Route[] = [{ name: "list" }, { name: "new" }, { name: "login" }, { name: "chat", sessionId: "20260921_1_x" }];
    for (const route of routes) expect(matchRoute(routePath(route))).toEqual(route);
  });

  it("never derives an action from the query or hash", () => {
    expect(matchRoute("/app/new")).toEqual({ name: "new" });
    // matchRoute only sees the pathname; a ?text=… or #send never reaches it.
    expect(Object.keys(matchRoute("/app/new"))).toEqual(["name"]);
  });
});

describe("navigate() from an open overlay", () => {
  it("takes the overlay's history entry instead of stacking on it", async () => {
    const { vi } = await import("vitest");
    const { navigate } = await import("./router");
    const { pushOverlay, resetOverlays } = await import("./overlayHistory");
    resetOverlays();
    history.replaceState(null, "", "/app/s/one");
    const push = vi.spyOn(history, "pushState");
    const replace = vi.spyOn(history, "replaceState");
    const release = pushOverlay(() => undefined);
    expect(push).toHaveBeenCalledTimes(1); // the overlay's entry
    navigate({ name: "list" });
    expect(push).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenLastCalledWith(null, "", "/app/");
    const go = vi.spyOn(history, "go");
    release(); // the overlay unmounts with the old page: nothing left to rewind
    expect(go).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });
});
