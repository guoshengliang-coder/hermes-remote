import "../../../android/app/src/main/assets/table-chart/engine.js";
export interface ChartState {
  view: "table" | "chart";
  metric: number;
  dimension: number;
  series: number;
  type: "line" | "bar" | "horizontal";
  categories: string[];
  from: string;
  to: string;
  confirmed?: boolean;
}
export interface Column {
  index: number;
  header: string;
  kind: "number" | "time" | "category" | "id";
  unit: string;
  mixedUnits: boolean;
  ambiguous: boolean;
  values: (number | string | null)[];
}
export interface Model {
  headers: string[];
  rows: string[][];
  columns: Column[];
  metrics: Column[];
  dimensions: Column[];
  eligible: boolean;
  automatic: boolean;
  reason: string;
  excluded: number;
  defaults: ChartState;
}
export interface Point {
  label: string;
  x: number | string;
  category: string;
  value: number | null;
  unit: string;
  row: number;
}
declare global {
  var HermesTableChart: {
    analyze(rows: string[][]): Model;
    time(value: string, header: string): number | null;
    parseMarkdown(raw: string): string[][];
    initial(model: Model, saved: unknown): ChartState;
    points(
      model: Model,
      state: ChartState,
    ): { data: Point[]; error: string | null };
    hash(text: string): string;
  };
}
export const engine = globalThis.HermesTableChart;
const PREFIX = "hermes-go.table-charts.fullscreen-v2.";
export function storageKey(scope: readonly string[], rows: string[][]): string {
  return (
    PREFIX +
    engine.hash(JSON.stringify(scope)) +
    "." +
    engine.hash(JSON.stringify(rows))
  );
}
export function restore(key: string, onFailure?: () => void): unknown {
  try {
    return JSON.parse(localStorage.getItem(key) || "null");
  } catch {
    onFailure?.();
    return null;
  }
}
export function persist(key: string, state: ChartState): void {
  localStorage.setItem(key, JSON.stringify(state));
  // Bound the cache without persisting source tables. Never erase other local preferences.
  const keys = Object.keys(localStorage).filter((k) => k.startsWith(PREFIX));
  if (keys.length > 500)
    for (const old of keys.slice(0, keys.length - 500))
      if (old !== key) localStorage.removeItem(old);
}
export function clearCharts(): void {
  for (const key of Object.keys(localStorage))
    if (key.startsWith(PREFIX)) localStorage.removeItem(key);
}
export function queryPrompt(
  source: string,
  headers: string[],
  range: string,
  language: string,
): string {
  return language === "en"
    ? `Please fetch fresh data from the original source of ${source}. Columns: ${headers.join(", ")}. Requested range: ${range}. Keep the same metric definitions and units; state the source and actual coverage. Do not extrapolate missing data.`
    : `请从原表来源重新取数：${source}。原表列：${headers.join("、")}。所需范围：${range}。沿用原指标口径与单位，注明来源和实际覆盖范围，不推算缺失数据。`;
}
