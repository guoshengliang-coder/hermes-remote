// Make `**强调**` survive CommonMark's delimiter rules when it sits against Chinese punctuation.
//
// CommonMark decides whether a `**` run may open or close emphasis from the two characters around
// it. A closing run must be *right-flanking*: not preceded by whitespace, and — if it IS preceded
// by punctuation — followed by whitespace or punctuation. English prose almost never trips this,
// because a bold phrase is followed by a space. Chinese prose trips it constantly, because the
// sentence-ending mark goes INSIDE the emphasis and the next word follows immediately:
//
//     **事实｜来源：**2026-08《小迈科技薪酬表》薪酬档案快照
//
// The closing run is preceded by `：` (punctuation) and followed by `2` (a digit), so it cannot
// close, the opening run has no partner, and the reader sees four literal asterisks (HG-106).
// The mirrored case exists too: an opening run followed by punctuation (`中文**「引用」**`) is not
// left-flanking and cannot open.
//
// The repair is to give the delimiter a neighbour it is allowed to have. A ZERO WIDTH SPACE is in
// Unicode category Cf, which CommonMark counts as neither whitespace nor punctuation, so placing
// one just inside the delimiter satisfies the flanking rule while adding nothing visible. It is
// inserted only where the pair would otherwise fail to parse.
//
// This is a port of the Android fix (HG-24, `ui/chat/CjkEmphasis.kt`), kept deliberately identical
// so the two clients cannot drift on what renders bold. Its constraint carries over: this runs on
// DISPLAY only. `readableText`, copy, share and read-aloud all read the original message text, so
// no zero-width character ever reaches the clipboard or a file — which is why the call site is
// `Markdown.tsx`, not `render.ts`.

const ZERO_WIDTH_SPACE = "​";

/**
 * `**…**` on a single line, with no whitespace immediately inside either delimiter and no nested
 * `**`. Matching pairs rather than individual runs keeps a stray `**` (a footnote marker, a glob)
 * from being rewritten.
 */
const STRONG_PAIR = /\*\*(?![\s*])((?:[^*\n]|\*(?!\*))+?)(?<![\s*])\*\*/g;

/** Fenced code blocks and inline code spans, which must be passed through character for character. */
const CODE_SPAN = /```[\s\S]*?```|`[^`\n]+`/g;

/**
 * CommonMark's "Unicode punctuation": the Unicode P* categories plus ASCII punctuation. Chinese
 * marks land in Po (？。，：), Ps (「（) and Pe (」）), which is exactly the set that breaks the
 * flanking rules above. The ASCII half is listed separately because `$+<=>^`|~` are Unicode
 * symbols, not punctuation, yet CommonMark counts them.
 */
const UNICODE_PUNCTUATION = /\p{P}/u;
const ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
const LETTER_OR_DIGIT = /[\p{L}\p{N}]/u;

function isMarkdownPunctuation(char: string): boolean {
  return UNICODE_PUNCTUATION.test(char) || ASCII_PUNCTUATION.includes(char);
}

/** A run is blocked by the character on its outer side only when that character is a word char. */
function blocksFlanking(char: string | undefined): boolean {
  return char !== undefined && LETTER_OR_DIGIT.test(char);
}

function codeRanges(text: string): Array<[number, number]> {
  const ranges: Array<[number, number]> = [];
  for (const match of text.matchAll(CODE_SPAN)) {
    ranges.push([match.index, match.index + match[0].length - 1]);
  }
  return ranges;
}

/**
 * [text] with zero-width spaces inserted inside any `**…**` pair that Chinese punctuation would
 * otherwise stop CommonMark from parsing. Text that already parses is returned unchanged.
 */
export function withCjkEmphasisRepaired(text: string): string {
  if (!text.includes("**")) return text;
  const protectedRanges = codeRanges(text);

  return text.replace(STRONG_PAIR, (whole: string, body: string, offset: number) => {
    if (protectedRanges.some(([from, to]) => offset >= from && offset <= to)) return whole;

    // The opening run cannot open when the character it introduces is punctuation and the one
    // before it is a word character; the closing run cannot close in the mirror image.
    const openBlocked = isMarkdownPunctuation(body[0]!) && blocksFlanking(text[offset - 1]);
    const closeBlocked =
      isMarkdownPunctuation(body[body.length - 1]!) && blocksFlanking(text[offset + whole.length]);

    if (!openBlocked && !closeBlocked) return whole;
    return `**${openBlocked ? ZERO_WIDTH_SPACE : ""}${body}${closeBlocked ? ZERO_WIDTH_SPACE : ""}**`;
  });
}
