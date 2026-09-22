/**
 * Prompt prefixes that pin what one mock run asks, so a UI flow can be driven deterministically
 * instead of waiting for the rotation (single → multi-select → batch) to come round:
 *
 *   !clarify-single …   approval, then a single-choice clarify
 *   !clarify-multi …    approval, then a multi-select clarify
 *   !clarify-batch …    approval, then the three-question batch
 *   !quick …            stream only: no sudo, approval or clarify
 *   !media <abs path>…  stream only, ending with one `MEDIA:<abs path>` per path (files the
 *                       Connector serves; several paths give one message several images)
 *   !table …            stream only, ending with a Markdown table (the Web table card)
 *   !proc …             stream only; process.list reports one running background task meanwhile
 *
 * Handled before a run starts (in the mock's prompt.submit): !fail (5000), !slow (acknowledged after
 * 6 s), !owned (4090, another surface owns the session), !gone (4007, the session no longer exists).
 *
 * Anything else keeps the historical behaviour: the next form in the rotation.
 */
const TABLE = "| 端口 | 服务 | 状态 |\n|---|---|:-:|\n| 443 | nginx | 正常 |\n| 3478 | DERP STUN | 正常 |\n| 18443 | Gateway (dev) | 未启用 |\n";

const FORMS = { "!clarify-single": 0, "!clarify-multi": 1, "!clarify-batch": 2 };

export function mockRunOptions(text) {
  const [head = "", ...rest] = String(text).trim().split(/\s+/);
  if (head in FORMS) return { form: FORMS[head], quick: false, suffix: "" };
  if (head === "!quick" || head === "!proc") return { form: undefined, quick: true, suffix: "" };
  if (head === "!media" && rest[0]?.startsWith("/")) {
    const tags = rest.filter((path) => path.startsWith("/")).map((path) => `MEDIA:${path}\n`).join("");
    return { form: undefined, quick: true, suffix: `\n\n产物已生成：\n\n${tags}` };
  }
  if (head === "!table") return { form: undefined, quick: true, suffix: `\n\n${TABLE}` };
  return { form: undefined, quick: false, suffix: "" };
}
