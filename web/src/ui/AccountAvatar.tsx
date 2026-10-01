import type { PublicAccount } from "../api/gateway";

/**
 * The Hermes GO account avatar (HG-181): the uploaded photo, else the first character of the
 * display name on a neutral tile. Web has no per-profile accent colour (DESIGN §2.4 is Android's
 * photo → colour → auto pipeline), so the fallback is deliberately plain rather than inventing one.
 */
export function AccountAvatar({ account, large = false }: { account: PublicAccount | null; large?: boolean }) {
  const name = account?.displayName?.trim() || account?.email?.trim() || "?";
  const initial = Array.from(name)[0]?.toUpperCase() ?? "?";
  const className = `account-avatar${large ? " account-avatar-large" : ""}`;
  if (account?.avatarUrl) {
    return <img class={className} src={account.avatarUrl} alt="" />;
  }
  return <span class={`${className} account-avatar-fallback`} aria-hidden="true">{initial}</span>;
}
