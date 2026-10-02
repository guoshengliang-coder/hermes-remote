import {
  engine,
  persist,
  preference,
  queryPrompt,
  restore,
  storageKey,
  type ChartState,
} from "./model";
import { appError, display, type AppError, type Language } from "../errors";
import { tableRows } from "../markdown/tableImage";
export interface ChartContext {
  scope: string[];
  prompt: string;
  source: string;
  prepare: (text: string) => void;
}
export function mountChart(
  table: HTMLTableElement,
  context: ChartContext,
  language: Language,
  theme: "dark" | "light",
  flash: (e: AppError) => void,
): () => void {
  const t = (zh: string, en: string) => (language === "en" ? en : zh);
  const card = table.closest<HTMLElement>(".table-card")!,
    head = card.querySelector(".block-head")!,
    body = card.querySelector<HTMLElement>(".table-scroll")!;
  const rows = tableRows(table).map((r) => r.cells),
    model = engine.analyze(rows),
    key = storageKey(context.scope, rows);
  const storageFailure = () => flash(appError("HR-CHART-002"));
  let state = engine.initial(
      model,
      restore(key, storageFailure),
      preference(storageFailure),
      context.prompt,
    ),
    frame: HTMLIFrameElement | null = null,
    timeout: ReturnType<typeof setTimeout> | null = null,
    ready = false,
    error = false;
  const nonce = crypto.randomUUID(),
    toggle = document.createElement("span");
  toggle.className = "table-view-toggle";
  toggle.setAttribute("role", "group");
  toggle.setAttribute("aria-label", t("数据视图", "Data view"));
  const tableButton = document.createElement("button"),
    chartButton = document.createElement("button");
  for (const [button, name] of [
    [tableButton, t("表格", "Table")],
    [chartButton, t("图表", "Chart")],
  ] as const) {
    button.type = "button";
    button.textContent = name;
    toggle.append(button);
  }
  head.querySelector(".block-label")?.replaceWith(toggle);
  const notice = document.createElement("p");
  notice.className = "chart-notice";
  card.append(notice);
  const chartHost = document.createElement("div");
  chartHost.className = "table-chart-host";
  card.append(chartHost);
  const reason = () =>
    model.rows.length < 2
      ? t("至少需要两行数据。", "At least two data rows are needed.")
      : !model.metrics.length
        ? t(
            "没有可确认的数值列；ID 与混合单位不能作为指标。",
            "No reliable numeric metric; IDs and mixed units are excluded.",
          )
        : t(
            "没有可用的时间或分类维度。",
            "No usable time or category dimension.",
          );
  function send(type: string, extra: object = {}) {
    frame?.contentWindow?.postMessage(
      { protocol: 1, nonce, type, ...extra },
      "*",
    );
  }
  function save() {
    try {
      persist(key, state);
    } catch {
      flash(appError("HR-CHART-002"));
    }
  }
  function fail() {
    error = true;
    ready = false;
    if (timeout) clearTimeout(timeout);
    state.view = "table";
    paint();
    notice.textContent = display(appError("HR-CHART-001"), language);
    const retry = document.createElement("button");
    retry.type = "button";
    retry.textContent = t("重试", "Retry");
    retry.onclick = () => {
      error = false;
      frame?.remove();
      frame = null;
      state.view = "chart";
      paint();
    };
    notice.append(" ", retry);
  }
  function ensureFrame() {
    if (frame) return;
    frame = document.createElement("iframe");
    frame.className = "table-chart-frame";
    frame.title = t("交互图表", "Interactive chart");
    frame.setAttribute("sandbox", "allow-scripts");
    frame.setAttribute(
      "allow",
      "camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'",
    );
    frame.referrerPolicy = "no-referrer";
    frame.src = "/app/charts/chart.html";
    frame.onerror = fail;
    chartHost.append(frame);
    timeout = setTimeout(fail, 10000);
  }
  function paint() {
    const chart = state.view === "chart" && model.eligible && !error;
    body.hidden = chart;
    chartHost.hidden = !chart;
    tableButton.setAttribute("aria-pressed", String(!chart));
    chartButton.setAttribute("aria-pressed", String(chart));
    chartButton.disabled = !model.eligible;
    notice.hidden = model.eligible && !error;
    if (!model.eligible) notice.textContent = reason();
    if (chart) ensureFrame();
  }
  tableButton.onclick = () => {
    state.view = "table";
    send("view", { view: "table" });
    paint();
    save();
  };
  chartButton.onclick = () => {
    state.view = "chart";
    send("view", { view: "chart" });
    paint();
    save();
  };
  function onMessage(event: MessageEvent) {
    if (
      !frame ||
      event.source !== frame.contentWindow ||
      !event.data ||
      typeof event.data !== "object" ||
      event.data.protocol !== 1
    )
      return;
    const m = event.data;
    if (m.type === "ready") {
      send("init", {
        rows,
        model,
        state,
        preference: preference(),
        prompt: context.prompt,
        source: context.source,
        language,
        theme:
          document.documentElement.dataset.theme === "dark" ? "dark" : "light",
        fontScale:
          parseFloat(getComputedStyle(document.documentElement).fontSize) / 16,
      });
      return;
    }
    if (m.nonce !== nonce) return;
    if (m.type === "state" && m.state && typeof m.state === "object") {
      state = engine.initial(model, m.state, preference(), context.prompt);
      ready = true;
      if (timeout) clearTimeout(timeout);
      paint();
      save();
    } else if (
      m.type === "height" &&
      typeof m.height === "number" &&
      Number.isFinite(m.height)
    )
      frame.style.height = `${Math.max(320, Math.min(2048, m.height))}px`;
    else if (m.type === "error") fail();
    else if (m.type === "range") flash(appError("HR-CHART-003"));
    else if (
      m.type === "query" &&
      typeof m.range === "string" &&
      m.range.trim() &&
      m.range.length <= 500
    )
      context.prepare(
        queryPrompt(context.source, model.headers, m.range, language),
      );
  }
  const appearance = new MutationObserver(() =>
    send("appearance", {
      theme:
        document.documentElement.dataset.theme === "dark" ? "dark" : "light",
      fontScale:
        parseFloat(getComputedStyle(document.documentElement).fontSize) / 16,
    }),
  );
  appearance.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["data-theme", "data-font-size"],
  });
  window.addEventListener("message", onMessage);
  paint();
  return () => {
    appearance.disconnect();
    if (timeout) clearTimeout(timeout);
    window.removeEventListener("message", onMessage);
    frame?.remove();
  };
}
