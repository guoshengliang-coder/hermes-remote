import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { ImageViewer } from "./ImageViewer";

// The viewer's gesture state machine in a real (happy-dom) render: the math is covered by
// viewer-math.test.ts; this pins the wiring between pointer events and the view.

const app = { t: (zh: string) => zh, language: "zh", client: {}, device: null } as unknown as AppContextValue;
let root: HTMLDivElement;

function mount(count = 2) {
  root = document.createElement("div");
  document.body.appendChild(root);
  const images = Array.from({ length: count }, (_, i) => ({ kind: "local" as const, url: `blob:img-${i}`, name: `p${i}` }));
  act(() => {
    render(
      <AppContext.Provider value={app}>
        <ImageViewer images={images} index={0} onClose={() => undefined} />
      </AppContext.Provider>,
      root,
    );
  });
  const stage = root.querySelector(".viewer-stage") as HTMLElement;
  stage.getBoundingClientRect = () => ({ left: 0, top: 0, width: 400, height: 800, right: 400, bottom: 800, x: 0, y: 0, toJSON: () => ({}) });
  return stage;
}

function pointer(stage: HTMLElement, type: string, x: number, y: number, id = 1) {
  act(() => {
    stage.dispatchEvent(new PointerEvent(type, { pointerId: id, clientX: x, clientY: y, bubbles: true }));
  });
}

const transform = () => (root.querySelector(".viewer-image") as HTMLElement).style.transform;
const counter = () => root.querySelector(".viewer-count")?.textContent;

afterEach(() => {
  render(null, root);
  root.remove();
});

describe("ImageViewer gestures", () => {
  it("double tap zooms in and stays zoomed (the end-of-gesture snap used to undo it)", () => {
    const stage = mount();
    pointer(stage, "pointerdown", 200, 400);
    pointer(stage, "pointerup", 200, 400);
    pointer(stage, "pointerdown", 200, 400);
    pointer(stage, "pointerup", 200, 400);
    expect(transform()).toContain("scale(2.5)");
  });

  it("a horizontal swipe at 1× pages, and a drag while zoomed does not", () => {
    const stage = mount();
    expect(counter()).toBe("1 / 2");
    pointer(stage, "pointerdown", 300, 400);
    pointer(stage, "pointermove", 200, 402);
    pointer(stage, "pointermove", 150, 404);
    pointer(stage, "pointerup", 150, 404);
    expect(counter()).toBe("2 / 2");

    pointer(stage, "pointerdown", 200, 400);
    pointer(stage, "pointerup", 200, 400);
    pointer(stage, "pointerdown", 200, 400);
    pointer(stage, "pointerup", 200, 400);
    pointer(stage, "pointerdown", 200, 400);
    pointer(stage, "pointermove", 320, 400);
    pointer(stage, "pointerup", 320, 400);
    expect(counter()).toBe("2 / 2");
    expect(transform()).toContain("scale(2.5)");
  });
});
