import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { WorkspaceSubtitle } from "./WorkspaceSubtitle";

const hosts: HTMLElement[] = [];
const context = { t: (zh: string) => zh, language: "zh" } as unknown as AppContextValue;
afterEach(() => {
  for (const host of hosts.splice(0)) {
    render(null, host);
    host.remove();
  }
});

function mount(node: preact.ComponentChildren): HTMLElement {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  act(() => render(<AppContext.Provider value={context}>{node}</AppContext.Provider>, host));
  return host;
}

// HG-182: the Web chat subtitle showed the default project's folder basename (「bs」 for /Users/bs)
// where Android shows 「默认项目」. projects.ts now decides the label, and this subtitle must spell
// out 「默认项目」 (house-folder) when the label is null, the folder name only for a real project.
it("writes 「默认项目」 with the house-folder when the chat runs in the default project", () => {
  const host = mount(<WorkspaceSubtitle projectLabel={null} branch={null} canMove enabled onClick={() => {}} />);
  expect(host.querySelector(".chat-workspace-name")?.textContent).toBe("默认项目");
  // HomeFolderIcon is the folder silhouette plus a house — two strokes, not the plain folder's one.
  expect(host.querySelector(".chat-workspace svg")?.querySelectorAll("path").length).toBe(2);
});

it("writes the project's folder name and branch for a real project", () => {
  const host = mount(<WorkspaceSubtitle projectLabel="hermes-remote" branch="main" canMove enabled onClick={() => {}} />);
  expect([...host.querySelectorAll(".chat-workspace-name")].map((n) => n.textContent)).toEqual(["hermes-remote", "main"]);
  expect(host.querySelector(".chat-workspace svg")?.querySelectorAll("path").length).toBe(1);
});

it("refuses the move while a run is live: disabled, no chevron", () => {
  const host = mount(<WorkspaceSubtitle projectLabel={null} branch={null} canMove enabled={false} onClick={() => {}} />);
  const button = host.querySelector<HTMLButtonElement>(".chat-workspace")!;
  expect(button.disabled).toBe(true);
  expect(button.querySelectorAll("svg").length).toBe(1);
});
