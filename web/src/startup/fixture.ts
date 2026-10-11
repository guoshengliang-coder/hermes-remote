import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/** Exercise the actual initial document and classic script, not a second copy of its controller. */
export function installStartup(app?: HTMLElement) {
  window.hermesStartup?.dispose();
  const source = readFileSync(resolve(process.cwd(), "index.html"), "utf8");
  // Never ask the DOM harness to load the document's CSS or scripts over HTTP.
  const body = source.match(/<body>([\s\S]*)<\/body>/)![1]!.replace(/<script[\s\S]*?<\/script>/g, "");
  const parsed = new DOMParser().parseFromString(body, "text/html");
  const gate = document.importNode(parsed.getElementById("startup")!, true);
  const root = app ?? document.createElement("div");
  root.id = "app";
  root.inert = true;
  root.setAttribute("aria-hidden", "true");
  document.body.append(gate);
  if (!app) document.body.append(root);
  const script = readFileSync(resolve(process.cwd(), "src/startup/early.js"), "utf8");
  new Function(script)();
  return {
    gate, root, controller: window.hermesStartup!,
    cleanup() {
      window.hermesStartup?.dispose();
      delete window.hermesStartup;
      gate.remove();
      if (!app) root.remove();
      delete document.documentElement.dataset.startup;
      delete document.documentElement.dataset.fontSize;
    },
  };
}
