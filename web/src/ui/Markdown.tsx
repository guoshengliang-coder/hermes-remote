import { useEffect, useRef } from "preact/hooks";
import { renderMarkdownFragment } from "../markdown/render";

// Assistant Markdown. The ONLY route into the DOM is the sanitized fragment from
// renderMarkdownFragment, attached with replaceChildren — no innerHTML anywhere. While a reply
// streams, re-rendering is throttled so a long answer does not re-parse on every delta.

const STREAM_THROTTLE_MS = 90;

export function Markdown({ source, streaming }: { source: string; streaming?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const last = useRef<{ at: number; source: string | null; timer: ReturnType<typeof setTimeout> | null }>({ at: 0, source: null, timer: null });

  useEffect(() => {
    const state = last.current;
    const paint = () => {
      state.timer = null;
      state.at = Date.now();
      if (state.source === source || !ref.current) return;
      state.source = source;
      ref.current.replaceChildren(renderMarkdownFragment(source));
    };
    if (!streaming) {
      if (state.timer) clearTimeout(state.timer);
      paint();
      return;
    }
    const wait = STREAM_THROTTLE_MS - (Date.now() - state.at);
    if (wait <= 0) paint();
    else {
      if (state.timer) clearTimeout(state.timer);
      state.timer = setTimeout(paint, wait);
    }
  }, [source, streaming]);

  useEffect(() => () => {
    if (last.current.timer) clearTimeout(last.current.timer);
  }, []);

  return <div class="markdown" ref={ref} />;
}
