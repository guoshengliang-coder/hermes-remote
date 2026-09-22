import type { SessionListItem } from "../hermes/types";

/**
 * The profile to name in resume / history / create for this session, or null for the default
 * profile (Hermes' own default applies). The Web list spans every profile and the Web app never
 * switches the Mac's active profile, so a non-default session must carry its profile explicitly.
 */
export function explicitProfile(session: Pick<SessionListItem, "profile" | "is_default_profile"> | null | undefined): string | null {
  const profile = session?.profile?.trim();
  if (!profile || session?.is_default_profile || profile === "default") return null;
  return profile;
}
