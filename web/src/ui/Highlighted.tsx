import { highlightRanges } from "../app/searchText";

/** Text with every occurrence of `query` wrapped in <mark>; text nodes only, never HTML. */
export function Highlighted({ text, query }: { text: string; query: string }) {
  const ranges = highlightRanges(text, query);
  if (!ranges.length) return <>{text}</>;
  const parts: preact.ComponentChildren[] = [];
  let cursor = 0;
  ranges.forEach(([start, end], i) => {
    if (start > cursor) parts.push(text.slice(cursor, start));
    parts.push(<mark key={i}>{text.slice(start, end)}</mark>);
    cursor = end;
  });
  if (cursor < text.length) parts.push(text.slice(cursor));
  return <>{parts}</>;
}
