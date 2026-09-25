import { expect, it } from "vitest";
import { chatViewportGeometry, followChatVisualViewport } from "./chatViewport";

it("fits the chat frame to the iOS keyboard's visible viewport and restores it afterward", () => {
  // iOS 26.5 Simulator: focusing a plain textarea left 100dvh/clientHeight at 714px, while the
  // visible area became 377px and was panned 337px. The old 100dvh-only frame lost its header.
  expect(chatViewportGeometry(714, 377, 337, 1)).toEqual({ height: 377, top: 337, compact: true });
  expect(chatViewportGeometry(714, 714, 0, 1)).toBeNull();
  expect(chatViewportGeometry(377, 377, 0, 1)).toBeNull(); // Chrome resizes the layout viewport.
  expect(chatViewportGeometry(714, 670, 0, 1)).toBeNull(); // Browser chrome, not a keyboard.
});

it("updates on visual viewport pan and removes the override when the keyboard closes", () => {
  const page = document.createElement("div");
  const viewport = new EventTarget() as VisualViewport;
  Object.assign(viewport, { height: 377, offsetTop: 337, scale: 1 });
  const doc = { documentElement: { clientHeight: 714 } } as Document;
  let pending: FrameRequestCallback | undefined;
  const win = Object.assign(new EventTarget(), {
    visualViewport: viewport,
    document: doc,
    requestAnimationFrame: (callback: FrameRequestCallback) => { pending = callback; return 1; },
    cancelAnimationFrame: () => { pending = undefined; },
  }) as unknown as Window;
  const stop = followChatVisualViewport(page, win);
  expect(page.classList.contains("keyboard-viewport")).toBe(true);
  expect(page.style.getPropertyValue("--chat-viewport-height")).toBe("377px");
  expect(page.style.getPropertyValue("--chat-viewport-top")).toBe("337px");

  Object.assign(viewport, { height: 340, offsetTop: 374 });
  viewport.dispatchEvent(new Event("scroll"));
  pending?.(0);
  expect(page.style.getPropertyValue("--chat-viewport-top")).toBe("374px");

  Object.assign(viewport, { height: 714, offsetTop: 0 });
  viewport.dispatchEvent(new Event("resize"));
  pending?.(0);
  expect(page.classList.contains("keyboard-viewport")).toBe(false);
  expect(page.style.getPropertyValue("--chat-viewport-height")).toBe("");
  stop();
});
