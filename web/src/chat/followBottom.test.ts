import { describe, expect, it } from "vitest";
import { followAfterScroll, STICK_SLACK_PX, USER_SCROLL_WINDOW_MS } from "./followBottom";

const base = { following: true, now: 5_000, userAt: Number.NEGATIVE_INFINITY, searching: false, contentGrew: true };

describe("following the bottom of the message list (HG-109)", () => {
  it("at the bottom it follows", () => {
    expect(followAfterScroll({ ...base, distanceFromBottom: 0 })).toBe("follow");
    expect(followAfterScroll({ ...base, following: false, distanceFromBottom: STICK_SLACK_PX - 1 })).toBe("follow");
  });

  it("content that grew under our own pin re-pins instead of letting go", () => {
    // The reported bug: 1032px of Markdown filled in after the pin, and the scroll event that
    // followed was read as the reader leaving the bottom.
    expect(followAfterScroll({ ...base, distanceFromBottom: 1032 })).toBe("repin");
  });

  it("the reader scrolling away releases it", () => {
    expect(followAfterScroll({ ...base, distanceFromBottom: 1032, userAt: 4_500 })).toBe("release");
  });

  it("an old touch is not the reader moving now", () => {
    expect(followAfterScroll({ ...base, distanceFromBottom: 1032, userAt: 5_000 - USER_SCROLL_WINDOW_MS - 1 })).toBe("repin");
  });

  it("never having touched the list is not 'just touched' in the first second after a load", () => {
    // performance.now() counts from page load: a 0 default made every scroll in the first second
    // look like the reader's, which is how WebKit kept opening 8,600px short of the bottom.
    expect(followAfterScroll({ ...base, now: 120, distanceFromBottom: 8600 })).toBe("repin");
  });

  it("search hits move the list on purpose", () => {
    expect(followAfterScroll({ ...base, distanceFromBottom: 1032, searching: true })).toBe("release");
  });

  it("once let go, layout changes leave it alone", () => {
    expect(followAfterScroll({ ...base, following: false, distanceFromBottom: 1032 })).toBe("unchanged");
  });

  it("a deliberate programmatic scroll (height unchanged) lets go instead of fighting it", () => {
    // scrollIntoView / find-in-page / focus: pulling the list back would make the target unreachable.
    expect(followAfterScroll({ ...base, distanceFromBottom: 1032, contentGrew: false })).toBe("release");
  });
});
