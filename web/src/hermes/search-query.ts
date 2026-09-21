// Port of android data/network/SearchQuery.kt. The Hermes search appends a prefix wildcard to every
// unquoted token, which breaks CJK (`的历史记录*` → 0 hits), so CJK tokens are quoted; Latin tokens
// stay unquoted so partial words keep matching.

/** Java `\S` is ASCII-only: an ideographic space (U+3000) is part of a token, as on Android. */
const TOKEN = /"[^"]*"|[^ \t\n\x0B\f\r]+/g;

/** CJK unified ideographs (+Ext A, compatibility), kana and Hangul syllables. Per UTF-16 unit. */
export function isCjk(code: number): boolean {
  return (
    (code >= 0x4e00 && code <= 0x9fff) ||
    (code >= 0x3400 && code <= 0x4dbf) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0x3040 && code <= 0x30ff) ||
    (code >= 0xac00 && code <= 0xd7af)
  );
}

export function containsCjk(text: string): boolean {
  for (let i = 0; i < text.length; i++) if (isCjk(text.charCodeAt(i))) return true;
  return false;
}

export function buildSearchQuery(raw: string): string {
  // Kotlin trim() strips Unicode whitespace, like String.prototype.trim.
  const tokens = raw.trim().match(TOKEN) ?? [];
  return tokens
    .map((token) => {
      if (token.startsWith('"')) return token;
      if (token.endsWith("*")) return token;
      if (containsCjk(token)) return `"${token.replace(/"/g, "")}"`;
      return token;
    })
    .join(" ");
}
