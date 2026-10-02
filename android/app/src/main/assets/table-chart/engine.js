/* HG-192: the same offline, dependency-free engine runs in both clients. No eval/HTML/network. */
(function (root) {
  "use strict";
  const MISSING = /^(?:|[-—–]|n\/?a|null|none|缺失|暂无|无数据)$/i;
  const TOTAL = /^(?:合计|总计|总和|汇总|小计|total|subtotal|grand total)$/i;
  const ID =
    /(?:^id$|(?:[_\s]|[\u4e00-\u9fff])id$|(?:user|account|device|session|order|product)id$|编号|序号|工号|订单号|账号|学号|证件号|身份证|手机号|电话|编码|邮编|identifier|phone|code$)/i;
  const VAGUE =
    /^(?:列\s*\d*|指标\s*\d*|数值\s*\d*|值|value|column\s*\d*|col\s*\d*)$/i;
  const TYPES = ["line", "bar", "horizontal"];
  function plain(s) {
    return String(s)
      .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/<[^>]*>/g, "")
      .replace(/(\*\*|__)(.+?)\1/g, "$2")
      .replace(/([*_`])(.+?)\1/g, "$2")
      .replace(/\\([|\\])/g, "$1")
      .trim();
  }
  function parseMarkdown(raw) {
    const lines = String(raw).trim().split(/\r?\n/);
    function cells(line) {
      line = line
        .trim()
        .replace(/^\|/, "")
        .replace(/(?<!\\)\|$/, "");
      const out = [];
      let part = "";
      let escaped = false;
      for (const c of line) {
        if (c === "|" && !escaped) {
          out.push(plain(part));
          part = "";
        } else part += c;
        escaped = c === "\\" && !escaped;
      }
      out.push(plain(part));
      return out;
    }
    return lines
      .filter(
        (line) =>
          line.trim() &&
          !/^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line),
      )
      .map(cells);
  }
  function headerUnit(header) {
    const m = /[（(]\s*([^()（）]+)\s*[)）]/.exec(header);
    const u = m ? m[1].trim() : /(?:%|％)/.test(header) ? "%" : "";
    return u === "百分比" || u === "percent" || u === "％" ? "%" : u;
  }
  function number(cell, unit) {
    const text = String(cell).trim();
    if (MISSING.test(text)) return { value: null, unit: unit || "" };
    const m =
      /^([¥￥$€]?)\s*([+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?|[+-]?\.\d+)\s*(%|％|[\p{L}]+)?$/u.exec(
        text,
      );
    if (!m) return null;
    const suffix = m[3] === "％" ? "%" : m[3] || "";
    const currency =
      { "¥": "元", "￥": "元", $: "USD", "€": "EUR" }[m[1]] || "";
    if (currency && suffix && currency !== suffix) return null;
    const actual = suffix || currency || unit || "";
    if (unit && actual !== unit) return null;
    const value = Number(m[2].replace(/,/g, ""));
    return Number.isFinite(value) ? { value, unit: actual } : null;
  }
  function time(cell, header) {
    const text = String(cell).trim();
    let y,
      m = 1,
      d = 1;
    let parts =
      /^(\d{4})[-/](\d{1,2})(?:[-/](\d{1,2}))?$/.exec(text) ||
      /^(\d{4})年(\d{1,2})月(?:(\d{1,2})日)?$/.exec(text);
    if (parts) {
      y = +parts[1];
      m = +parts[2];
      d = +(parts[3] || 1);
    } else if (
      /^(?:19|20|21)\d{2}$/.test(text) &&
      /年份|年度|year/i.test(header)
    )
      y = +text;
    else return null;
    const result = Date.UTC(y, m - 1, d),
      date = new Date(result);
    return date.getUTCFullYear() === y &&
      date.getUTCMonth() === m - 1 &&
      date.getUTCDate() === d
      ? result
      : null;
  }
  function analyze(input) {
    const rows = (Array.isArray(input) ? input : []).map((row) =>
      row.map((v) => String(v).trim()),
    );
    const headers = rows[0] || [],
      all = rows.slice(1);
    const body = all.filter(
      (row) => !row.some((cell, i) => i < 2 && TOTAL.test(cell)),
    );
    const columns = headers.map((header, index) => {
      const cells = body.map((row) => row[index] || "");
      const present = cells.filter((cell) => !MISSING.test(cell));
      const dates = present.map((cell) => time(cell, header));
      const values = present.map((cell) => number(cell, headerUnit(header)));
      const units = new Set(values.filter(Boolean).map((v) => v.unit));
      const identifier =
        ID.test(header) ||
        present.some(
          (cell) => /^0\d{2,}$/.test(cell) || /^\d{12,}$/.test(cell),
        );
      const temporal = present.length > 0 && dates.every((v) => v !== null);
      const numeric =
        !identifier &&
        !temporal &&
        present.length > 0 &&
        values.every(Boolean) &&
        units.size === 1;
      const mixedUnits = !identifier && values.every(Boolean) && units.size > 1;
      return {
        index,
        header,
        kind: temporal
          ? "time"
          : identifier
            ? "id"
            : numeric
              ? "number"
              : "category",
        unit: numeric ? [...units][0] : "",
        mixedUnits,
        ambiguous:
          !header ||
          VAGUE.test(header) ||
          (/日期|时间|date|timestamp/i.test(header) && !temporal),
        values: cells.map((cell) =>
          numeric
            ? number(cell, headerUnit(header)).value
            : temporal
              ? time(cell, header)
              : cell,
        ),
      };
    });
    const metrics = columns.filter((c) => c.kind === "number");
    const times = columns.filter((c) => c.kind === "time");
    const categories = columns.filter(
      (c) => c.kind === "category" && !c.mixedUnits,
    );
    const dimensions = [...times, ...categories];
    const eligible =
      body.length >= 2 && metrics.length > 0 && dimensions.length > 0;
    const dimension = dimensions[0]?.index ?? -1;
    const series =
      times.length === 1 && categories.length === 1 ? categories[0].index : -1;
    const reason =
      body.length < 2
        ? "single"
        : metrics.length === 0
          ? "noMetric"
          : dimensions.length === 0
            ? "noDimension"
            : columns.some((c) => c.mixedUnits)
              ? "mixedUnits"
              : columns.some((c) => c.ambiguous)
                ? "ambiguous"
                : times.length
                  ? "time"
                  : "category";
    const model = {
      headers,
      rows: body,
      columns,
      metrics,
      dimensions,
      eligible,
      reason,
      excluded: all.length - body.length,
      defaults: {
        metric: metrics[0]?.index ?? -1,
        dimension,
        series,
        type: times.length ? "line" : "bar",
        categories: [],
        from: "",
        to: "",
        view: "table",
        confirmed: false,
      },
    };
    const probe = points(model, model.defaults);
    model.automatic =
      eligible &&
      !columns.some((c) => c.ambiguous || c.mixedUnits) &&
      metrics.length === 1 &&
      times.length <= 1 &&
      categories.length <= 1 &&
      !probe.error;
    return model;
  }
  function intent(prompt) {
    const s = String(prompt || "");
    // Only explicit requests; arbitrary mentions in prose do not override the preference.
    const matches = [
      ...s.matchAll(
        /(?:请|用|使用|展示|显示|给我|画|绘制|做成|生成|show|use|draw|plot)\s*(?:为|成|as|an?|the)?\s*(表格|数据表|table|折线图|柱状图|横向条形图|条形图|图表|line chart|bar chart|horizontal bar|chart)/gi,
      ),
    ];
    const last = matches.at(-1)?.[1]?.toLowerCase();
    return last
      ? {
          view: /表|table/.test(last) && !/图/.test(last) ? "table" : "chart",
          type: /横|条形|horizontal/.test(last)
            ? "horizontal"
            : /柱|bar/.test(last)
              ? "bar"
              : /折线|line/.test(last)
                ? "line"
                : null,
        }
      : null;
  }
  function initial(model, saved, preference, prompt) {
    const state = { ...model.defaults };
    if (saved && typeof saved === "object") {
      for (const key of ["metric", "dimension", "series"])
        if (Number.isInteger(saved[key])) state[key] = saved[key];
      for (const key of ["from", "to"])
        if (typeof saved[key] === "string" && saved[key].length < 100)
          state[key] = saved[key];
      if (saved.confirmed === true) state.confirmed = true;
      if (TYPES.includes(saved.type)) state.type = saved.type;
      if (["chart", "table"].includes(saved.view)) state.view = saved.view;
      if (Array.isArray(saved.categories))
        state.categories = saved.categories
          .filter((x) => typeof x === "string")
          .slice(0, 10000);
    } else {
      const request = intent(prompt);
      state.view =
        request?.view ||
        (preference === "chart" || (preference === "auto" && model.automatic)
          ? "chart"
          : "table");
      if (request?.type) state.type = request.type;
    }
    if (!model.metrics.some((c) => c.index === state.metric))
      state.metric = model.defaults.metric;
    if (!model.dimensions.some((c) => c.index === state.dimension))
      state.dimension = model.defaults.dimension;
    if (
      state.series === state.dimension ||
      !model.dimensions.some(
        (c) => c.index === state.series && c.kind === "category",
      )
    )
      state.series = -1;
    if (
      model.columns[state.dimension]?.kind !== "time" &&
      state.type === "line"
    )
      state.type = "bar";
    if (
      !model.eligible ||
      (!model.automatic && !saved && preference === "auto" && !intent(prompt))
    )
      state.view = "table";
    return state;
  }
  function points(model, state) {
    const metric = model.columns[state.metric],
      dimension = model.columns[state.dimension];
    if (
      !metric ||
      metric.kind !== "number" ||
      !dimension ||
      !model.dimensions.some((c) => c.index === dimension.index)
    )
      return { data: [], error: "choose" };
    const data = [];
    const pairs = new Set();
    for (let i = 0; i < model.rows.length; i++) {
      const row = model.rows[i];
      const label = row[state.dimension] || "";
      if (MISSING.test(label) || dimension.values[i] === null) continue;
      const category = state.series >= 0 ? row[state.series] || "" : "";
      const filterValue =
        state.series >= 0
          ? category
          : dimension.kind === "category"
            ? label
            : "";
      if (state.categories.length && !state.categories.includes(filterValue))
        continue;
      const x = dimension.kind === "time" ? dimension.values[i] : label;
      const lower = time(state.from, dimension.header),
        upper = time(state.to, dimension.header);
      if (
        dimension.kind === "time" &&
        ((lower !== null && x < lower) || (upper !== null && x > upper))
      )
        continue;
      const pair = JSON.stringify([x, category]);
      if (pairs.has(pair)) return { data: [], error: "duplicates" };
      pairs.add(pair);
      data.push({
        label,
        x,
        category,
        value: metric.values[i],
        unit: metric.unit,
        row: i,
      });
    }
    if (dimension.kind === "time") data.sort((a, b) => a.x - b.x);
    return { data, error: null };
  }
  function hash(text) {
    let a = 2166136261,
      b = 5381;
    for (const ch of String(text)) {
      a = Math.imul(a ^ ch.charCodeAt(0), 16777619);
      b = Math.imul(b, 33) ^ ch.charCodeAt(0);
    }
    return (a >>> 0).toString(16) + (b >>> 0).toString(16);
  }
  const API = {
    parseMarkdown,
    number,
    time,
    analyze,
    intent,
    initial,
    points,
    hash,
  };
  root.HermesTableChart = API;
})(typeof globalThis !== "undefined" ? globalThis : this);
