// Saved prompts (Android PromptStore): a title and a body, kept in this browser (owner decision
// 2026-09-22), global rather than per Mac, and removed on sign-out.

const KEY = "hermes-go.prompts";

export interface SavedPrompt {
  id: string;
  title: string;
  body: string;
}

export function loadPrompts(): SavedPrompt[] {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(KEY) ?? "[]");
    if (!Array.isArray(v)) return [];
    return v.flatMap((p) =>
      p && typeof p === "object" && typeof (p as SavedPrompt).id === "string" && typeof (p as SavedPrompt).body === "string"
        ? [{ id: (p as SavedPrompt).id, title: typeof (p as SavedPrompt).title === "string" ? (p as SavedPrompt).title : "", body: (p as SavedPrompt).body }]
        : [],
    );
  } catch {
    return [];
  }
}

function save(list: SavedPrompt[]): SavedPrompt[] {
  try {
    if (list.length) localStorage.setItem(KEY, JSON.stringify(list));
    else localStorage.removeItem(KEY);
  } catch {
    /* private mode */
  }
  return list;
}

/** Replace the prompt with the same id in place, or append (Android upsertPrompt). */
export function upsertPrompt(prompt: SavedPrompt): SavedPrompt[] {
  const list = loadPrompts();
  return save(list.some((p) => p.id === prompt.id) ? list.map((p) => (p.id === prompt.id ? prompt : p)) : [...list, prompt]);
}

export function deletePrompt(id: string): SavedPrompt[] {
  return save(loadPrompts().filter((p) => p.id !== id));
}

export function clearPrompts(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* nothing stored */
  }
}

export function newPromptId(): string {
  return `p-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
