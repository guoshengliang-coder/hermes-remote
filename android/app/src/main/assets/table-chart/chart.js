(function (root) {
  "use strict";
  const { analyze, parseMarkdown, initial, points } = root.HermesTableChart;
  const MISSING = /^(?:|[-—–]|n\/?a|null|none|缺失|暂无|无数据)$/i;
  const TYPES = ["line", "bar", "horizontal"];
  if (typeof document === "undefined") return;
  const TEXT = {
    single: [
      "至少需要两行数据，原始表格仍可查看。",
      "At least two data rows are needed. The original table is available.",
    ],
    noMetric: [
      "没有可确认的数值列；ID 和混合单位不能作为指标。",
      "No reliable numeric metric. IDs and mixed units are excluded.",
    ],
    noDimension: [
      "没有可用的时间或分类维度，请核对原表。",
      "No time or category dimension. Check the original table.",
    ],
    ambiguous: [
      "列含义不明确，请确认指标与维度后查看。",
      "Column meaning is unclear. Confirm the metric and dimension.",
    ],
    mixedUnits: [
      "混合单位的列已排除；每次只展示一个指标。",
      "Mixed-unit columns are excluded. One metric per axis.",
    ],
    time: [
      "时间＋数值推荐折线图；缺失值留空，不跨缺失连线。",
      "Time and numbers suggest a line chart. Missing values stay empty; lines break at gaps.",
    ],
    category: [
      "分类＋数值推荐柱状图；不合并重复行，不求和百分比。",
      "Categories and numbers suggest bars. Duplicate rows and percentages are never summed.",
    ],
    duplicates: [
      "当前维度存在重复数据。请选择分类拆分或其他维度；不会猜测聚合口径。",
      "Duplicate dimension values. Select a series or another dimension; aggregation is not inferred.",
    ],
    choose: [
      "请选择可用的指标和维度。",
      "Select a valid metric and dimension.",
    ],
  };
  let deliveredModel = false;
  let config,
    model,
    state,
    hits = [],
    currentData = [],
    selectedRow = null;
  const host = document.getElementById("chart");
  function t(zh, en) {
    return config?.language === "en" ? en : zh;
  }
  function send(type, payload) {
    const message = JSON.stringify({
      protocol: 1,
      nonce: config?.nonce || "",
      type,
      ...payload,
    });
    if (root.HermesChartBridge) root.HermesChartBridge.post(message);
    else
      root.parent.postMessage(
        JSON.parse(message),
        new URL(root.location.href).origin,
      );
  }
  function el(tag, text, cls) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (cls) node.className = cls;
    return node;
  }
  function notify() {
    send("state", {
      state,
      ...(!deliveredModel ? { model } : {}),
      eligible: model.eligible,
      automatic: model.automatic,
      reason: t(...TEXT[model.reason]),
    });
    deliveredModel = true;
  }
  function optionControl(label, items, value, change, multiple) {
    const wrap = el("label", label),
      select = el("select");
    select.multiple = !!multiple;
    for (const item of items) {
      const option = el("option", item.label);
      option.value = String(item.value);
      option.selected = multiple
        ? value.includes(String(item.value))
        : String(item.value) === String(value);
      select.append(option);
    }
    select.addEventListener("change", () => {
      change(
        multiple
          ? [...select.selectedOptions].map((o) => o.value)
          : select.value,
      );
      render();
      notify();
    });
    wrap.append(select);
    return wrap;
  }
  function render() {
    host.replaceChildren();
    if (!model.eligible) {
      host.append(el("p", t(...TEXT[model.reason]), "empty"));
      return;
    }
    const controls = el("div", undefined, "controls");
    controls.append(
      optionControl(
        t("指标", "Metric"),
        model.metrics.map((c) => ({
          value: c.index,
          label: c.header + (c.unit ? " · " + c.unit : ""),
        })),
        state.metric,
        (v) => {
          state.metric = +v;
          state.confirmed = false;
        },
      ),
    );
    controls.append(
      optionControl(
        t("维度", "Dimension"),
        model.dimensions.map((c) => ({ value: c.index, label: c.header })),
        state.dimension,
        (v) => {
          state.dimension = +v;
          state.confirmed = false;
          if (model.columns[+v].kind !== "time" && state.type === "line")
            state.type = "bar";
          state.from = "";
          state.to = "";
          if (state.series === +v) state.series = -1;
        },
      ),
    );
    controls.append(
      optionControl(
        t("分类拆分", "Series"),
        [
          { value: -1, label: t("不拆分", "None") },
          ...model.dimensions
            .filter((c) => c.kind === "category" && c.index !== state.dimension)
            .map((c) => ({ value: c.index, label: c.header })),
        ],
        state.series,
        (v) => {
          state.series = +v;
          state.categories = [];
        },
      ),
    );
    controls.append(
      optionControl(
        t("图表类型", "Chart type"),
        TYPES.map((value, i) => ({
          value,
          label: [
            t("折线图", "Line"),
            t("柱状图", "Bars"),
            t("横向条形图", "Horizontal bars"),
          ][i],
        })).filter(
          (item) =>
            model.columns[state.dimension].kind === "time" ||
            item.value !== "line",
        ),
        state.type,
        (v) => (state.type = v),
      ),
    );
    host.append(
      controls,
      el(
        "p",
        t(...TEXT[model.reason]) +
          (model.excluded
            ? t(
                ` 已排除 ${model.excluded} 行合计。`,
                ` ${model.excluded} total rows excluded.`,
              )
            : ""),
        "reason",
      ),
    );
    const dimension = model.columns[state.dimension];
    const range = dimension.values.filter(
      (x) => x !== null && !MISSING.test(String(x)),
    );
    const available =
      dimension.kind === "time"
        ? [...range]
            .sort((a, b) => a - b)
            .map((x) => new Date(x).toISOString().slice(0, 10))
        : [...new Set(range)];
    host.append(
      el(
        "p",
        t("原表范围：", "Source range: ") +
          (available.length ? `${available[0]} — ${available.at(-1)}` : "—") +
          t(`（${model.rows.length} 行）`, ` (${model.rows.length} rows)`) +
          (config.source ? ` · ${config.source}` : ""),
        "reason",
      ),
    );
    const filter = el("div", undefined, "controls filter");
    const categoryField =
      state.series >= 0
        ? state.series
        : dimension.kind === "category"
          ? state.dimension
          : -1;
    if (categoryField >= 0) {
      const cats = [
        ...new Set(model.rows.map((row) => row[categoryField] || "")),
      ];
      filter.append(
        optionControl(
          t("分类筛选（未选为全部）", "Categories (none selected = all)"),
          cats.map((value) => ({ value, label: value || "—" })),
          state.categories,
          (v) => (state.categories = v),
          true,
        ),
      );
    }
    if (dimension.kind === "time")
      for (const [key, label] of [
        ["from", t("开始", "From")],
        ["to", t("结束", "To")],
      ]) {
        const wrap = el("label", label),
          input = el("input");
        input.type = "date";
        input.min = available[0] || "";
        input.max = available.at(-1) || "";
        input.value = state[key];
        input.addEventListener("change", () => {
          if (
            input.value &&
            (input.value < input.min || input.value > input.max)
          ) {
            input.value = state[key];
            send("range", {});
            return;
          }
          state[key] = input.value;
          render();
          notify();
        });
        wrap.append(input);
        filter.append(wrap);
      }
    const reset = el("button", t("重置筛选", "Reset filters"));
    reset.onclick = () => {
      state.categories = [];
      state.from = "";
      state.to = "";
      selectedRow = null;
      render();
      notify();
    };
    filter.append(reset);
    host.append(filter);
    host.append(
      el(
        "p",
        t("当前筛选：", "Current filters: ") +
          (state.categories.join("、") || t("全部分类", "All categories")) +
          " · " +
          (state.from || t("起始", "Start")) +
          " — " +
          (state.to || t("结束", "End")),
        "reason",
      ),
    );
    const result = points(model, state);
    currentData = result.data;
    const ambiguous =
      model.columns[state.metric].ambiguous || dimension.ambiguous;
    if (ambiguous && !state.confirmed) {
      const confirm = el(
        "button",
        t("确认所选指标与维度", "Confirm selected metric and dimension"),
      );
      confirm.onclick = () => {
        state.confirmed = true;
        render();
        notify();
      };
      host.append(el("p", t(...TEXT.ambiguous)), confirm);
    } else if (result.error)
      host.append(el("p", t(...TEXT[result.error]), "empty"));
    else if (!currentData.some((p) => p.value !== null))
      host.append(
        el(
          "p",
          t(
            "当前筛选没有数值；可重置筛选或核对原表。",
            "No values in this filter. Reset filters or check the source table.",
          ),
          "empty",
        ),
      );
    else {
      const canvas = el("canvas");
      canvas.setAttribute(
        "aria-label",
        t(
          "点选图表查看数值，或使用下方数据点列表。",
          "Tap the chart for a value, or use the data-point list below.",
        ),
      );
      canvas.onclick = (event) => {
        const rect = canvas.getBoundingClientRect();
        const x = event.clientX - rect.left,
          y = event.clientY - rect.top;
        const hit = hits.reduce(
          (best, p) =>
            !best ||
            Math.hypot(x - p.x, y - p.y) < Math.hypot(x - best.x, y - best.y)
              ? p
              : best,
          null,
        );
        if (hit && Math.hypot(x - hit.x, y - hit.y) < 35) {
          selectedRow = hit.point.row;
          detail();
        }
      };
      host.append(canvas);
      const colors = palette(),
        cats = [...new Set(currentData.map((p) => p.category))];
      const legend = el("div", undefined, "legend");
      cats.forEach((c, i) => {
        const node = el("span", c || model.columns[state.metric].header),
          mark = el("i");
        mark.style.backgroundColor = colors[i % colors.length];
        node.prepend(mark);
        legend.append(node);
      });
      host.append(legend);
      host.append(
        optionControl(
          t("数据点（精确值）", "Data points (exact values)"),
          [
            { value: "", label: t("请选择", "Select") },
            ...currentData.map((p) => ({
              value: p.row,
              label: `${p.label} ${p.category} · ${p.value === null ? t("缺失", "Missing") : p.value + " " + p.unit}`,
            })),
          ],
          selectedRow ?? "",
          (v) => {
            selectedRow = v === "" ? null : +v;
          },
        ),
      );
      const d = el("p", "", "detail");
      d.id = "detail";
      host.append(d);
      detail();
      requestAnimationFrame(() => draw(canvas));
    }
    const fetch = el("div", undefined, "fetch");
    const label = el(
        "label",
        t("原表以外的所需范围", "Range beyond this table"),
      ),
      input = el("input");
    input.placeholder = t(
      "例如：2026-10-01 至 2026-10-31",
      "e.g. 2026-10-01 to 2026-10-31",
    );
    input.maxLength = 500;
    label.append(input);
    const button = el(
      "button",
      t("准备重新取数提问", "Prepare a new-data question"),
    );
    button.onclick = () => {
      if (input.value.trim()) send("query", { range: input.value.trim() });
      else input.focus();
    };
    fetch.append(label, button);
    host.append(
      fetch,
      el(
        "p",
        t(
          "只准备提问，检查后由你发送；不会扩充或推算原表数据。",
          "Prepares a question for you to review and send. No source data is extrapolated.",
        ),
        "reason",
      ),
    );
  }
  function detail() {
    const p = currentData.find((p) => p.row === selectedRow),
      d = document.getElementById("detail");
    if (d)
      d.textContent = p
        ? `${p.label} · ${p.category || model.columns[state.metric].header} · ${p.value === null ? t("缺失", "Missing") : p.value + " " + p.unit}`
        : t("点选查看原始数值", "Select a point to see its original value");
  }
  function palette() {
    return config.theme === "dark"
      ? ["#60A5FA", "#34D399", "#C084FC", "#FBBF24", "#22D3EE", "#FB923C"]
      : ["#2563EB", "#059669", "#9333EA", "#B45309", "#0891B2", "#C2410C"];
  }
  function draw(canvas) {
    const w = Math.max(260, canvas.clientWidth),
      h = 300,
      dpr = Math.min(root.devicePixelRatio || 1, 3);
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      send("error", {});
      return;
    }
    ctx.scale(dpr, dpr);
    hits = [];
    const data = currentData,
      vals = data.filter((p) => p.value !== null).map((p) => p.value),
      lo = Math.min(0, ...vals),
      hi = Math.max(0, ...vals),
      span = hi - lo || 1;
    const left = 60,
      right = w - 16,
      top = 20,
      bottom = h - 55,
      color = getComputedStyle(document.documentElement);
    ctx.font = "11px system-ui";
    ctx.fillStyle = color.getPropertyValue("--muted");
    ctx.strokeStyle = color.getPropertyValue("--grid");
    ctx.lineWidth = 1;
    const horizontal = state.type === "horizontal";
    for (let i = 0; i <= 4; i++) {
      const value = lo + (span * i) / 4;
      const p = horizontal
        ? left + ((right - left) * i) / 4
        : bottom - ((bottom - top) * i) / 4;
      ctx.beginPath();
      if (horizontal) {
        ctx.moveTo(p, top);
        ctx.lineTo(p, bottom);
        ctx.fillText(
          Number(value.toPrecision(4)).toString(),
          p - 15,
          bottom + 18,
        );
      } else {
        ctx.moveTo(left, p);
        ctx.lineTo(right, p);
        ctx.fillText(Number(value.toPrecision(4)).toString(), 2, p + 4);
      }
      ctx.stroke();
    }
    const categories = [...new Set(data.map((p) => p.category))],
      labels = [...new Set(data.map((p) => p.x))],
      colors = palette(),
      timeDimension = model.columns[state.dimension].kind === "time",
      minX = timeDimension ? Math.min(...labels) : 0,
      maxX = timeDimension ? Math.max(...labels) : 1;
    const labelIndex = new Map(labels.map((x, i) => [x, i])),
      rowIndex = new Map(data.map((p, i) => [p.row, i])),
      byCategory = new Map(categories.map((c) => [c, []]));
    data.forEach((p) => byCategory.get(p.category).push(p));
    const position = (p) =>
      timeDimension && state.type === "line"
        ? left + ((right - left) * (p.x - minX)) / (maxX - minX || 1)
        : left + ((right - left) * (labelIndex.get(p.x) + 0.5)) / labels.length;
    const valueY = (v) => bottom - ((v - lo) / span) * (bottom - top),
      valueX = (v) => left + ((v - lo) / span) * (right - left);
    categories.forEach((cat, ci) => {
      ctx.strokeStyle = ctx.fillStyle = colors[ci % colors.length];
      ctx.lineWidth = 2;
      ctx.setLineDash(ci >= colors.length ? [4, 3] : []);
      let previous = null;
      for (const p of byCategory.get(cat)) {
        if (p.value === null) {
          previous = null;
          continue;
        }
        let x = position(p),
          y = valueY(p.value);
        if (state.type === "line") {
          if (previous) {
            ctx.beginPath();
            ctx.moveTo(previous.x, previous.y);
            ctx.lineTo(x, y);
            ctx.stroke();
          }
          ctx.beginPath();
          ctx.arc(x, y, 3, 0, Math.PI * 2);
          ctx.fill();
          previous = { x, y };
        } else if (horizontal) {
          const slot = (bottom - top) / data.length;
          y = top + (rowIndex.get(p.row) + 0.5) * slot;
          x = valueX(p.value);
          ctx.fillRect(
            Math.min(x, valueX(0)),
            y - slot * 0.35,
            Math.abs(x - valueX(0)),
            Math.max(1, slot * 0.7),
          );
        } else {
          const slot = (right - left) / labels.length / categories.length;
          x = x - ((categories.length - 1) * slot) / 2 + ci * slot;
          ctx.fillRect(
            x - slot * 0.35,
            Math.min(y, valueY(0)),
            Math.max(1, slot * 0.7),
            Math.abs(y - valueY(0)),
          );
        }
        hits.push({ x, y, point: p });
      }
    });
    ctx.setLineDash([]);
    ctx.fillStyle = color.getPropertyValue("--muted");
    if (horizontal) {
      data.forEach((p, i) => {
        if (i % Math.max(1, Math.ceil(data.length / 8)) === 0)
          ctx.fillText(
            timeDimension ? p.label.slice(5) : p.label.slice(0, 8),
            2,
            top + ((i + 0.5) * (bottom - top)) / data.length + 4,
          );
      });
    } else
      labels.forEach((x, i) => {
        if (
          i % Math.max(1, Math.ceil(labels.length / 4)) === 0 ||
          i === labels.length - 1
        ) {
          const p = data.find((p) => p.x === x);
          ctx.fillText(
            p.label.slice(0, 12),
            Math.min(right - 55, Math.max(left - 15, position(p) - 20)),
            bottom + 20,
          );
        }
      });
    ctx.fillText(
      model.columns[state.metric].unit || t("原表数值", "Source values"),
      left,
      12,
    );
  }
  function receive(message) {
    if (!message || message.protocol !== 1) return;
    if (message.type === "init") {
      if (config && message.nonce !== config.nonce) return;
      config = message;
      document.documentElement.lang = message.language === "en" ? "en" : "zh";
      document.documentElement.dataset.theme =
        message.theme === "dark" ? "dark" : "light";
      document.documentElement.style.fontSize =
        15 * Math.max(1, Math.min(Number(message.fontScale) || 1, 2)) + "px";
      model =
        message.model ||
        model ||
        analyze(message.rows || parseMarkdown(message.raw || ""));
      state = initial(model, message.state, message.preference, message.prompt);
      render();
      notify();
    } else if (
      config &&
      message.nonce === config.nonce &&
      message.type === "appearance"
    ) {
      config.theme = message.theme === "dark" ? "dark" : "light";
      document.documentElement.dataset.theme = config.theme;
      document.documentElement.style.fontSize =
        15 * Math.max(1, Math.min(Number(message.fontScale) || 1, 2)) + "px";
      render();
    } else if (
      config &&
      message.nonce === config.nonce &&
      message.type === "view" &&
      ["table", "chart"].includes(message.view)
    ) {
      state.view = model.eligible ? message.view : "table";
      notify();
    }
  }
  root.HermesChartReceive = receive;
  root.addEventListener("message", (event) => {
    if (
      event.source === root.parent &&
      event.origin === new URL(root.location.href).origin
    )
      receive(event.data);
  });
  root.addEventListener("error", () => send("error", {}));
  root.addEventListener("unhandledrejection", () => send("error", {}));
  root.addEventListener("resize", () => {
    const canvas = host.querySelector("canvas");
    if (canvas) draw(canvas);
  });
  if (typeof ResizeObserver !== "undefined")
    new ResizeObserver(() => {
      if (config)
        send("height", {
          height: Math.ceil(host.getBoundingClientRect().height),
        });
    }).observe(host);
  send("ready", {});
})(typeof globalThis !== "undefined" ? globalThis : this);
