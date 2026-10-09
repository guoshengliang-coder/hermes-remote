import { createContext } from "preact";
import { useContext } from "preact/hooks";

export type LayoutMode = "auto" | "single";
const MODE = "hermes-go.workspace.mode";
const WIDTH = "hermes-go.workspace.widthRem";
export const DEFAULT_LIST_REM = 18.75;

export function workspaceBudget(width: number, rootFont: number, preferred = DEFAULT_LIST_REM) {
  const rem = Math.max(16, Number.isFinite(rootFont) ? rootFont : 16);
  const minimum = 15 * rem;
  const divider = 1.5 * rem;
  const maximum = Math.min(30 * rem, width - 22.5 * rem - divider);
  return {
    eligible: Number.isFinite(width) && maximum >= minimum,
    minimum, maximum: Math.max(minimum, maximum), divider, rem,
    list: Math.max(minimum, Math.min(maximum, preferred * rem)),
  };
}

export function readLayoutMode(): LayoutMode {
  try { return localStorage.getItem(MODE) === "single" ? "single" : "auto"; } catch { return "auto"; }
}
export function readListWidth(): number {
  try {
    const value = Number(localStorage.getItem(WIDTH));
    return Number.isFinite(value) && value >= 15 && value <= 30 ? value : DEFAULT_LIST_REM;
  } catch { return DEFAULT_LIST_REM; }
}
export function saveLayoutMode(mode: LayoutMode) { try { localStorage.setItem(MODE, mode); } catch { /* memory fallback */ } }
export function saveListWidth(rem: number) { try { localStorage.setItem(WIDTH, String(rem)); } catch { /* memory fallback */ } }

interface WorkspaceValue {
  mode: LayoutMode;
  setMode: (mode: LayoutMode) => void;
  eligible: boolean;
  split: boolean;
  width: number;
  listWidth: number;
  selectedId: string | null;
  listVisible?: boolean;
  toggleList: () => void;
  resetWidth: () => void;
}
export const WorkspaceContext = createContext<WorkspaceValue>({
  mode: "auto", setMode: saveLayoutMode, eligible: false, split: false,
  width: 0, listWidth: 0, selectedId: null, listVisible: true, toggleList: () => {}, resetWidth: () => {},
});
export const useWorkspace = () => useContext(WorkspaceContext);
