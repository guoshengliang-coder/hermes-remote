import type { ReasoningValue } from "../hermes/params";
export const REASONING_LABEL: Record<ReasoningValue, [string, string]> = {
  none: ["关", "Off"],
  minimal: ["最小", "Min"],
  low: ["低", "Low"],
  medium: ["中", "Med"],
  high: ["高", "High"],
  xhigh: ["极高", "XHigh"],
  max: ["最高", "Max"],
  ultra: ["超高", "Ultra"],
};
