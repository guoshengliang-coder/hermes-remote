// Whether the message list keeps following its bottom after a scroll event (HG-109).
//
// A scroll event does not say who moved the list. When the reader moved it, leaving the bottom
// means "stop following". When nobody did — content that filled in after we pinned to the bottom
// (Markdown, highlighting, images) pushed the real bottom further down — it must NOT count as the
// reader leaving, or a long conversation opens part-way up and stays there.

/** Within this distance of the end the list counts as "at the bottom". */
export const STICK_SLACK_PX = 80;
/** A scroll this soon after a touch, wheel or key press is the reader's, not a layout change. */
export const USER_SCROLL_WINDOW_MS = 1000;

export type FollowDecision = "follow" | "release" | "repin" | "unchanged";

export function followAfterScroll(input: {
  /** scrollHeight − scrollTop − clientHeight */
  distanceFromBottom: number;
  following: boolean;
  /** performance.now() now, and at the reader's last touch / wheel / key (−Infinity if never). */
  now: number;
  userAt: number;
  /** In-chat search moves the list to its hits on purpose. */
  searching: boolean;
  /** The list's scrollHeight differs from the last time we pinned it to the bottom. */
  contentGrew: boolean;
}): FollowDecision {
  if (input.distanceFromBottom < STICK_SLACK_PX) return "follow";
  if (input.now - input.userAt < USER_SCROLL_WINDOW_MS || input.searching) return "release";
  if (!input.following) return "unchanged";
  // Same height, yet no longer at the bottom: something scrolled the list on purpose (an element
  // brought into view, find-in-page, focus). Only a height change is content settling under us.
  return input.contentGrew ? "repin" : "release";
}
