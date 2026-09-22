import { appError } from "../errors";

// Copy to the clipboard from a tap. The async Clipboard API is the normal path (Safari allows it
// inside a user gesture on a secure origin); the hidden-textarea `execCommand` path covers browsers
// that refuse it. When both fail the caller shows HR-WEB-007 — the text is still selectable.

export async function copyText(text: string): Promise<void> {
  let cause = "clipboard api unavailable";
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch (error) {
      cause = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    }
  }
  if (legacyCopy(text)) return;
  throw appError("HR-WEB-007", `${cause}; execCommand copy refused`);
}

function legacyCopy(text: string): boolean {
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.className = "visually-hidden";
  document.body.appendChild(area);
  try {
    area.select();
    area.setSelectionRange(0, text.length);
    return typeof document.execCommand === "function" && document.execCommand("copy");
  } catch {
    return false;
  } finally {
    area.remove();
  }
}
