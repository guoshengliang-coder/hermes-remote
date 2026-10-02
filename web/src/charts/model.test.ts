import { beforeEach, describe, expect, vi, it } from "vitest";
import {
  engine,
  storageKey,
  restore,
  persist,
  preference,
  savePreference,
  clearCharts,
  queryPrompt,
} from "./model";
import { appError, diagnostics } from "../errors";
const dates = [
  ["日期", "新增人数（人）"],
  ["2026-09-01", "10"],
  ["2026-09-02", "—"],
  ["2026-09-03", "20"],
  ["合计", "30"],
];
const channels = [
  ["渠道", "收入（元）"],
  ["官网", "¥1,200"],
  ["门店", "800"],
];
const platforms = [
  ["日期", "平台", "新增人数（人）"],
  ["2026-09-01", "安卓", "10"],
  ["2026-09-01", "Web", "20"],
  ["2026-09-02", "安卓", "15"],
  ["2026-09-02", "Web", "25"],
];
beforeEach(() => localStorage.clear());
describe("HG-192 shared offline engine", () => {
  it.each([
    [dates, "line"],
    [channels, "bar"],
    [platforms, "line"],
  ] as const)("recommends the real table structure %#", (rows, type) => {
    const m = engine.analyze(rows.map((r) => [...r]));
    expect(m.automatic).toBe(true);
    expect(m.defaults.type).toBe(type);
    expect(engine.initial(m, null, "auto", "").view).toBe("chart");
  });
  it("renders a cached model after structured clone or JSON bridge serialization", () => {
    const m = JSON.parse(JSON.stringify(engine.analyze(platforms)));
    expect(engine.points(m, m.defaults).data).toHaveLength(4);
  });
  it("retains gaps, excludes totals and original data stays intact", () => {
    const m = engine.analyze(dates);
    const p = engine.points(m, m.defaults).data;
    expect(p.map((x) => x.value)).toEqual([10, null, 20]);
    expect(m.excluded).toBe(1);
    expect(dates).toHaveLength(5);
    expect(p[0]?.unit).toBe("人");
  });
  it("uses real dates, not equally spaced sorted strings", () => {
    const m = engine.analyze([
      ["日期", "人数"],
      ["2026-10-01", "10"],
      ["2026-02-01", "20"],
    ]);
    expect(engine.points(m, m.defaults).data.map((x) => x.label)).toEqual([
      "2026-02-01",
      "2026-10-01",
    ]);
    expect(engine.time("2026-02-30", "日期")).toBeNull();
  });
  it("splits a time/category table and filters locally without summing", () => {
    const m = engine.analyze(platforms);
    expect(m.defaults.series).toBe(1);
    const s = {
      ...m.defaults,
      categories: ["安卓"],
      from: "2026-09-02",
      to: "2026-09-02",
    };
    expect(engine.points(m, s).data.map((x) => x.value)).toEqual([15]);
    expect(engine.points(m, { ...m.defaults, series: -1 }).error).toBe(
      "duplicates",
    );
  });
  it.each([
    [
      ["项目", "详情"],
      ["A", "文字"],
      ["B", "其他"],
    ],
    [
      ["日期", "人数"],
      ["2026-01-01", "1"],
    ],
    [
      ["ID", "编号"],
      ["001", "123456789012"],
      ["002", "123456789013"],
    ],
    [
      ["渠道", "金额"],
      ["A", "10元"],
      ["B", "20USD"],
    ],
  ])("keeps unsuitable data in the table %#", (...rows) => {
    const m = engine.analyze(rows as string[][]);
    expect(m.eligible).toBe(false);
    expect(engine.initial(m, null, "chart", "请画图表").view).toBe("table");
  });
  it("excludes ID metrics, leaves percentage values as supplied and keeps units separate", () => {
    const m = engine.analyze([
      ["渠道", "用户ID", "收入（元）", "转化率（%）"],
      ["A", "123", "20", "5%"],
      ["B", "124", "30", "10%"],
    ]);
    expect(m.metrics.map((x) => x.index)).toEqual([2, 3]);
    expect(
      engine.points(m, { ...m.defaults, metric: 3 }).data.map((x) => x.value),
    ).toEqual([5, 10]);
    expect(m.columns[3]?.unit).toBe("%");
    expect(m.columns[2]?.unit).toBe("元");
  });
  it("requires manual choice for ambiguous columns and refuses duplicate aggregation", () => {
    const m = engine.analyze([
      ["列1", "值"],
      ["A", "1"],
      ["A", "2"],
    ]);
    expect(m.automatic).toBe(false);
    expect(engine.initial(m, null, "auto", "").view).toBe("table");
    expect(engine.points(m, m.defaults).error).toBe("duplicates");
  });
  it("respects all defaults and explicit request overrides", () => {
    const m = engine.analyze(channels);
    expect(engine.initial(m, null, "table", "").view).toBe("table");
    expect(engine.initial(m, null, "chart", "").view).toBe("chart");
    expect(engine.initial(m, null, "chart", "请用表格展示").view).toBe("table");
    expect(engine.initial(m, null, "table", "请画横向条形图").type).toBe(
      "horizontal",
    );
  });
  it("restores chart/filter state and invalidates changes across every identity boundary", () => {
    const scope = [
        "https://a",
        "account",
        "mac",
        "profile",
        "session",
        "h-1",
        "0",
      ],
      key = storageKey(scope, dates),
      m = engine.analyze(dates),
      state = { ...m.defaults, view: "chart" as const, from: "2026-09-02" };
    persist(key, state);
    expect(engine.initial(m, restore(key), "table", "").from).toBe(
      "2026-09-02",
    );
    scope.forEach((_, i) =>
      expect(
        storageKey(
          scope.map((v, j) => (i === j ? v + "x" : v)),
          dates,
        ),
      ).not.toBe(key),
    );
    expect(storageKey(scope, [...dates, ["2026-09-04", "2"]])).not.toBe(key);
    savePreference("auto");
    clearCharts();
    expect(restore(key)).toBeNull();
    expect(preference()).toBe("auto");
  });
  it("parses escaped pipes and formatted headers without executing HTML", () => {
    expect(
      engine.parseMarkdown(
        "| **渠道** | 收入（元） |\n| --- | ---: |\n| A\\|B | 2 |\n| [门店](https://x) | 3 |",
      ),
    ).toEqual([
      ["渠道", "收入（元）"],
      ["A|B", "2"],
      ["门店", "3"],
    ]);
  });
  it("prepares source/range/definition text; this function never sends a request", () => {
    const text = queryPrompt(
      "会话 s1 消息 h-2 来源：销售报表",
      ["日期", "收入（元）"],
      "十月",
      "zh",
    );
    expect(text).toContain("销售报表");
    expect(text).toContain("十月");
    expect(text).toContain("不推算");
  });
  it.each(["HR-CHART-001", "HR-CHART-002", "HR-CHART-003"] as const)(
    "has bilingual registered errors and redacted diagnostics %s",
    (code) => {
      const e = appError(code, "Authorization: Bearer secret-value");
      expect(e.zh).toMatch(/图表|原表/);
      expect(e.en).toMatch(/chart|table/);
      expect(e.retryable).toBe(code !== "HR-CHART-003");
      expect(diagnostics(e)).not.toContain("secret-value");
      expect(JSON.parse(JSON.stringify(e)).code).toBe(code);
    },
  );
});

it("reports storage read failures while preserving a usable table fallback", () => {
  const failed = vi.fn();
  const get = vi.spyOn(localStorage, "getItem").mockImplementation(() => {
    throw new Error("unavailable");
  });
  expect(preference(failed)).toBe("table");
  expect(restore("key", failed)).toBeNull();
  expect(failed).toHaveBeenCalledTimes(2);
  get.mockRestore();
});
it("excludes short numeric order and student identifiers from metrics", () => {
  for (const header of ["订单号", "学号", "账号"]) {
    const model = engine.analyze([
      [header, "人数"],
      ["12", "3"],
      ["13", "4"],
    ]);
    expect(model.columns[0]?.kind).toBe("id");
    expect(model.metrics.map((c) => c.index)).toEqual([1]);
  }
});
