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
  const doc = Object.assign(new EventTarget(), {
    documentElement: { clientHeight: 714 }, activeElement: document.createElement("textarea"),
  }) as unknown as Document;
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

// HG-229: recorded iOS 26.5 measurements during keyboard dismissal: the document returns
// to zero before visualViewport stops reporting height=377 and offsetTop=245.
function viewportFixture() {
  const page = document.createElement("main");
  const input = document.createElement("textarea"); input.value = "未发送的草稿";
  const messages = document.createElement("div"); messages.scrollTop = 350;
  page.append(messages, input); document.body.append(page); input.focus();
  const viewport = Object.assign(new EventTarget(), { height: 377, offsetTop: 245, scale: 1 });
  let pending: FrameRequestCallback | undefined;
  const win = Object.assign(new EventTarget(), {
    visualViewport: viewport, document, scrollX: 0, scrollY: 245,
    requestAnimationFrame: (callback: FrameRequestCallback) => { pending = callback; return 1; },
    cancelAnimationFrame: () => { pending = undefined; },
    scrollTo: () => { win.scrollX = 0; win.scrollY = 0; },
  });
  const height = Object.getOwnPropertyDescriptor(document.documentElement, "clientHeight");
  Object.defineProperty(document.documentElement, "clientHeight", { configurable: true, value: 714 });
  const stop = followChatVisualViewport(page, win as unknown as Window);
  const flush = () => { const next = pending; pending = undefined; next?.(0); };
  return { page, input, messages, viewport, win, flush, close() {
    stop(); page.remove();
    if (height) Object.defineProperty(document.documentElement, "clientHeight", height);
    else Reflect.deleteProperty(document.documentElement, "clientHeight");
  } };
}

it("drops the keyboard frame on blur even while Safari reports its old viewport (HG-229)", () => {
  const f = viewportFixture();
  try {
    expect(f.page.style.getPropertyValue("--chat-viewport-top")).toBe("245px");
    f.win.scrollY = 0;
    f.input.blur(); f.flush();
    expect(f.page.classList.contains("keyboard-viewport")).toBe(false);
    expect(f.page.style.getPropertyValue("--chat-viewport-top")).toBe("");
    expect(f.page.style.getPropertyValue("--chat-viewport-height")).toBe("");
    f.viewport.dispatchEvent(new Event("resize")); f.flush();
    expect(f.page.classList.contains("keyboard-viewport")).toBe(false);
    expect(f.input.value).toBe("未发送的草稿");
    expect(f.messages.scrollTop).toBe(350);
    f.input.focus(); f.flush();
    expect(f.page.classList.contains("keyboard-viewport")).toBe(true);
  } finally { f.close(); }
});

it("unwinds an unfocused document pan on scroll, pageshow and visibility restoration (HG-229)", () => {
  const f = viewportFixture();
  try {
    f.input.blur(); f.flush();
    for (const event of ["scroll", "pageshow"]) {
      f.win.scrollY = 245; f.win.dispatchEvent(new Event(event)); f.flush();
      expect(f.win.scrollY, event).toBe(0);
      expect(f.page.classList.contains("keyboard-viewport")).toBe(false);
    }
    f.win.scrollY = 245;
    document.dispatchEvent(new Event("visibilitychange")); f.flush();
    expect(f.win.scrollY).toBe(0);
    expect(f.messages.scrollTop).toBe(350);
  } finally { f.close(); }
});

it("preserves zoom and other text fields and removes recovery listeners on disposal", () => {
  const f = viewportFixture();
  const search = document.createElement("input"); search.type = "search"; document.body.append(search);
  try {
    search.focus(); f.flush();
    expect(f.win.scrollY).toBe(245);
    search.blur(); Object.assign(f.viewport, { scale: 2 }); f.flush();
    f.win.dispatchEvent(new Event("scroll")); f.flush();
    expect(f.win.scrollY).toBe(245);
  } finally { search.remove(); f.close(); }
  f.win.scrollY = 245;
  f.win.dispatchEvent(new Event("scroll")); f.win.dispatchEvent(new Event("pageshow"));
  document.dispatchEvent(new Event("visibilitychange")); f.flush();
  expect(f.win.scrollY).toBe(245);
  expect(f.page.classList.contains("keyboard-viewport")).toBe(false);
});

it.each(["button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit"])(
  "does not mistake an input[type=%s] for a keyboard owner", (type) => {
    const f = viewportFixture();
    const control = document.createElement("input"); control.type = type; document.body.append(control);
    try {
      f.input.blur(); control.focus(); f.flush();
      expect(f.page.classList.contains("keyboard-viewport")).toBe(false);
    } finally { control.remove(); f.close(); }
  },
);

it("ignores a focused read-only field when discarding stale keyboard measurements", () => {
  const f = viewportFixture();
  try {
    f.input.readOnly = true;
    f.viewport.dispatchEvent(new Event("resize")); f.flush();
    expect(f.page.classList.contains("keyboard-viewport")).toBe(false);
    expect(f.input.value).toBe("未发送的草稿");
  } finally { f.close(); }
});
