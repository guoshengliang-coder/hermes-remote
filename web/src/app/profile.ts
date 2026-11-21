import type { SessionListItem } from "../hermes/types";

/** An omitted profile selects the Hermes process's launch home, which may be a named
 * profile. Preserve the explicit default route when the server identifies its owner. */
export function explicitProfile(session: Pick<SessionListItem, "profile" | "is_default_profile"> | null | undefined): string | null {
  if (session?.is_default_profile) return "default";
  return session?.profile?.trim() || null;
}
