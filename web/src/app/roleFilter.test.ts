import { expect, it } from "vitest";
import { roleIncluded, toggleRole } from "./roleFilter";
it("distinguishes all, multiple, none and hidden roles", () => {
  const visible = ["default", "work", "private"];
  let selected = toggleRole(null, visible, "private");
  expect(selected).toEqual(["default", "work"]);
  expect(roleIncluded(selected, visible, "work")).toBe(true);
  selected = toggleRole(toggleRole(selected, visible, "work"), visible, "default");
  expect(selected).toEqual([]);
  expect(roleIncluded(selected, visible, "default")).toBe(false);
  expect(roleIncluded(null, visible, "default")).toBe(true);
  expect(roleIncluded(["hidden"], visible, "hidden")).toBe(false);
});
