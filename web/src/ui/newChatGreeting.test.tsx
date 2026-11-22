import { render } from "preact";
import { act } from "preact/test-utils";
import { expect, it } from "vitest";
import { RoleSheet } from "./RoleSheet";
import { resetOverlays } from "../app/overlayHistory";

it("the greeting switch presents an explicit single-role selection", () => {
  const host = document.createElement("div"); document.body.append(host);
  let chosen = "";
  try {
    act(() => render(<RoleSheet names={["default", "work"]} label={name => name} title="切换角色" closeLabel="关闭"
      selected={name => name === "default"} onToggle={name => { chosen = name; }} onClose={() => {}}/>, host));
    expect(host.querySelectorAll('[aria-pressed="true"]')).toHaveLength(1);
    expect(host.querySelector('[role="checkbox"]')).toBeNull();
    act(() => { host.querySelectorAll<HTMLButtonElement>(".role-option")[1]!.click(); });
    expect(chosen).toBe("work");
  } finally { act(() => render(null, host)); host.remove(); resetOverlays(); }
});
