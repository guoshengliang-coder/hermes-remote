import { afterEach, describe, expect, it, vi } from "vitest";
import { copyText } from "./clipboard";

const realClipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, "clipboard", { value, configurable: true });
}

afterEach(() => {
  if (realClipboard) Object.defineProperty(navigator, "clipboard", realClipboard);
  vi.restoreAllMocks();
});

describe("copyText", () => {
  it("uses the async Clipboard API when it works", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    await copyText("hello");
    expect(writeText).toHaveBeenCalledWith("hello");
  });

  it("falls back to execCommand when the API refuses, and leaves no textarea behind", async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new DOMException("denied", "NotAllowedError")) });
    const exec = vi.fn().mockReturnValue(true);
    (document as unknown as { execCommand: unknown }).execCommand = exec;
    await copyText("fallback");
    expect(exec).toHaveBeenCalledWith("copy");
    expect(document.querySelector("textarea")).toBeNull();
  });

  it("throws HR-WEB-007 with the refusal in diagnostics when both paths fail", async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new DOMException("denied", "NotAllowedError")) });
    (document as unknown as { execCommand: unknown }).execCommand = vi.fn().mockReturnValue(false);
    await expect(copyText("x")).rejects.toMatchObject({ code: "HR-WEB-007", retryable: false, details: expect.stringContaining("NotAllowedError") });
  });
});
