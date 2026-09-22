// Hand a generated file to the user: the system share sheet when the browser can share files (iOS
// offers Save to Photos / Files from there), otherwise a download. A cancelled share is not an error.

export type SaveOutcome = "shared" | "downloaded" | "cancelled";

export async function saveOrShareFile(file: File, title: string): Promise<SaveOutcome> {
  if (typeof navigator.canShare === "function" && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title });
      return "shared";
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") return "cancelled";
      // Share refused for another reason: fall back to a download.
    }
  }
  const url = URL.createObjectURL(file);
  const a = document.createElement("a");
  a.href = url;
  a.download = file.name;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
  return "downloaded";
}
