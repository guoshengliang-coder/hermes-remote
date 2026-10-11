// @vitest-environment jsdom
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { afterEach, expect, it, vi } from "vitest";
import { engine, type ChartState } from "./model";
const dates = [
  ["日期", "人数（人）"],
  ["2026-01-01", "10"],
  ["2026-01-02", "20"],
];
afterEach(() => {
  document.body.replaceChildren();
  vi.restoreAllMocks();
});
function renderer(rows: string[][] = dates, state?: ChartState) {
  document.body.innerHTML = '<main id="chart"></main>'; // Fixed fixture, never message HTML.
  const text = vi.fn();
  const ctx = {
    scale: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    fillText: text,
    setLineDash: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
    fillRect: vi.fn(),
    measureText: (s: string) => ({ width: s.length * 12 }),
  };
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(
    ctx as unknown as CanvasRenderingContext2D,
  );
  const post = vi.fn();
  const root = {
    document,
    URL,
    location: { href: "https://gateway.example/app/charts/chart.html" },
    HermesChartBridge: { post },
    HermesTableChart: engine,
    addEventListener: vi.fn(),
    requestAnimationFrame: (f: () => void) => f(),
    getComputedStyle,
    innerWidth: 412,
    innerHeight: 915,
    devicePixelRatio: 1,
    HermesChartReceive: (_: unknown) => {},
  };
  runInNewContext(
    readFileSync("../android/app/src/main/assets/table-chart/chart.js", "utf8"),
    root,
  );
  root.HermesChartReceive({
    protocol: 1,
    type: "init",
    nonce: "test",
    rows,
    state,
    language: "zh",
    theme: "light",
  });
  const latest = () =>
    JSON.parse(
      post.mock.calls
        .filter(([s]) => JSON.parse(s).type === "state")
        .at(-1)![0],
    );
  return { root, post, text, latest };
}
it("shows a chart using all data without open configuration, filters or fetching", () => {
  const h = renderer();
  expect(document.querySelector("canvas")).not.toBeNull();
  expect(document.querySelector<HTMLDetailsElement>(".adjustments")!.open).toBe(
    false,
  );
  expect(document.querySelector(".fetch")!.closest("details")).toHaveProperty(
    "open",
    false,
  );
  expect(document.querySelector(".active-filter")).toBeNull();
  expect(h.latest().state).toMatchObject({
    view: "chart",
    categories: [],
    from: "",
    to: "",
  });
  expect(document.querySelectorAll(".exact-values option")).toHaveLength(3);
  expect(h.text.mock.calls.some(([label]) => label === "2026-01-02")).toBe(
    true,
  );
});
it("changes filters only on user input, marks the scope and restores all data", () => {
  const h = renderer();
  const adjustments =
    document.querySelector<HTMLDetailsElement>(".adjustments")!;
  adjustments.open = true;
  adjustments.dispatchEvent(new Event("toggle"));
  const from = document.querySelector<HTMLInputElement>('input[type="date"]')!;
  from.value = "2026-01-02";
  from.dispatchEvent(new Event("change"));
  expect(document.querySelector<HTMLDetailsElement>(".adjustments")!.open).toBe(
    true,
  );
  expect(document.querySelector(".active-filter")!.textContent).toContain(
    "2026-01-02",
  );
  expect(h.latest().state.from).toBe("2026-01-02");
  document.querySelector<HTMLButtonElement>(".active-filter button")!.click();
  expect(document.querySelector(".active-filter")).toBeNull();
  expect(document.querySelectorAll(".exact-values option")).toHaveLength(3);
});
it("restored filters stay labelled but the adjustment panel reopens collapsed", () => {
  renderer(dates, {
    ...engine.analyze(dates).defaults,
    view: "chart",
    from: "2026-01-02",
  });
  expect(document.querySelector(".active-filter")!.textContent).toContain(
    "2026-01-02",
  );
  expect(document.querySelector<HTMLDetailsElement>(".adjustments")!.open).toBe(
    false,
  );
});
it("dense plots explicitly page every point and never trim the original value list", () => {
  const rows = [
    ["渠道", "收入（元）"],
    ...Array.from({ length: 205 }, (_, i) => [`渠道${i}`, `${i}`]),
  ];
  renderer(rows);
  expect(document.querySelector(".plot-pages")!.textContent).toContain(
    "共 205",
  );
  expect(document.querySelectorAll(".exact-values option")).toHaveLength(206);
  for (let i = 0; i < 2; i++)
    document
      .querySelectorAll<HTMLButtonElement>(".plot-pages button")[1]!
      .click();
  expect(document.querySelector(".plot-pages")!.textContent).toContain(
    "201–205",
  );
});
it("uses space on rotation and paints complete long category labels", () => {
  const labels = [
    "这个分类名称非常长并且需要完整显示",
    "第二个分类名称也需要完整显示",
  ];
  const h = renderer([
    ["分类", "人数"],
    [labels[0]!, "10"],
    [labels[1]!, "20"],
  ]);
  expect(h.latest().state.type).toBe("horizontal");
  expect(h.text.mock.calls.map(([label]) => label).join("")).toContain(
    labels[0],
  );
  const before = document.querySelector("canvas")!.style.height;
  h.root.innerHeight = 420;
  // Reinitialization is the same path used by Android after a configuration change.
  h.root.HermesChartReceive({
    protocol: 1,
    type: "init",
    nonce: "test",
    rows: [],
    state: h.latest().state,
    language: "en",
    theme: "dark",
    fontScale: 1.3,
  });
  expect(document.querySelector("canvas")!.style.height).not.toBe(before);
  expect(document.documentElement.dataset.theme).toBe("dark");
});

it("keeps the full ordinary plot visible after a hidden renderer is resized", () => {
  const h = renderer();
  const canvas = document.querySelector("canvas")!;
  expect(
    parseFloat(canvas.parentElement!.style.maxHeight),
  ).toBeGreaterThanOrEqual(parseFloat(canvas.style.height));
  h.root.innerHeight = 420;
  h.root.addEventListener.mock.calls.find(([name]) => name === "resize")![1]();
  expect(
    parseFloat(canvas.parentElement!.style.maxHeight),
  ).toBeGreaterThanOrEqual(parseFloat(canvas.style.height));
  expect(parseFloat(canvas.style.height)).toBeLessThan(500);
});
