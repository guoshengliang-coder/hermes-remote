// The one slash command the Web app sends (Android ChatViewModel.sessionModelCommand). The Gateway
// admits exactly this shape (gateway/src/account/web-rpc-filter.ts), so ids with whitespace or
// quotes — which Android would quote — are not offered on the Web.

/** Model / provider ids the Gateway admits in `/model … --session`. */
export const SLASH_MODEL_ID = /^[A-Za-z0-9._:/@+-]{1,128}$/;
export const SLASH_PROVIDER_ID = /^[A-Za-z0-9._-]{1,64}$/;

/** `/model <id> --provider <id> --session`, or null when the ids are not admissible. */
export function sessionModelCommand(provider: string, model: string): string | null {
  if (!SLASH_MODEL_ID.test(model) || !SLASH_PROVIDER_ID.test(provider)) return null;
  return `/model ${model} --provider ${provider} --session`;
}
