import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { SessionRow, type SessionRowProps } from "./SessionRow";
const hosts: HTMLElement[] = [];
afterEach(() => { for (const host of hosts.splice(0)) { render(null, host); host.remove(); } });
function row(props: Partial<SessionRowProps> = {}) {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const context = { t: (zh: string) => zh, language: "zh", device: null, profileCatalog: { work: "工作角色" } } as unknown as AppContextValue;
  act(() => render(<AppContext.Provider value={context}><SessionRow session={{ id: "s", profile: "work", model: "gpt-6.2-sol" }} now={0} onOpen={() => {}} {...props}/></AppContext.Provider>, host));
  return host;
}
const dots = (host: HTMLElement) => [...host.querySelectorAll(".row-subline > span")].filter(n => n.textContent === "·");
it("HG-262 separates role and model with one decorative dot", () => {
  const host = row(); expect(dots(host)).toHaveLength(1);
  expect(host.querySelector(".row-role")!.nextElementSibling?.textContent).toBe("·");
  expect(dots(host)[0]!.getAttribute("aria-hidden")).toBe("true");
});
it("keeps the project/model dot and places the pin before the role", () => {
  const host = row({ pinned: true, session: { id: "s", profile: "work", cwd: "/qa/project", model: "model" } });
  expect(dots(host)).toHaveLength(2); expect(host.querySelector(".row-subline")!.firstElementChild?.classList.contains("pin-mark")).toBe(true);
});
it.each([{draft:true},{inProject:true,session:{id:"s",profile:"work",git_branch:"main"}},{archived:true}])("separates the role from actual trailing content: %j", props => {
  const host = row({session:{id:"s",profile:"work"},...props}); expect(dots(host)).toHaveLength(1);
});
it("never adds a trailing dot for only a role or a pin", () => {
  expect(dots(row({pinned:true,session:{id:"s",profile:"work"}}))).toHaveLength(0);
});
it("never adds a leading dot without a role, including bot rows", () => {
  expect(dots(row({session:{id:"s",model:"model"}}))).toHaveLength(0);
  const host = row({bot:{statusLine:null}});expect(host.querySelector(".row-role")).toBeNull();expect(dots(host)).toHaveLength(0);
});
