/**
 * Prompt prefixes that pin what one mock run asks, so a UI flow can be driven deterministically
 * instead of waiting for the rotation (single → multi-select → batch) to come round:
 *
 *   !clarify-single …   approval, then a single-choice clarify
 *   !clarify-multi …    approval, then a multi-select clarify
 *   !clarify-batch …    approval, then the three-question batch
 *   !quick …            stream only: no sudo, approval or clarify
 *   !media <abs path>   stream only, ending with `MEDIA:<abs path>` (a file the Connector serves)
 *
 * Anything else keeps the historical behaviour: the next form in the rotation.
 */
const FORMS = { "!clarify-single": 0, "!clarify-multi": 1, "!clarify-batch": 2 };

export function mockRunOptions(text) {
  const [head = "", ...rest] = String(text).trim().split(/\s+/);
  if (head in FORMS) return { form: FORMS[head], quick: false, suffix: "" };
  if (head === "!quick") return { form: undefined, quick: true, suffix: "" };
  if (head === "!media" && rest[0]?.startsWith("/")) {
    return { form: undefined, quick: true, suffix: `\n\n产物已生成：\n\nMEDIA:${rest[0]}\n` };
  }
  return { form: undefined, quick: false, suffix: "" };
}
