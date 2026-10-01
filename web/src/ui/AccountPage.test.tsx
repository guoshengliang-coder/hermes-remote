import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import type { GatewayClient, PublicAccount } from "../api/gateway";
import { matchRoute, routePath } from "../app/router";
import { AppContext, type AppContextValue } from "../app/store";
import { AccountPage } from "./AccountPage";

const hosts: HTMLElement[] = [];
afterEach(() => { for (const host of hosts.splice(0)) { render(null, host); host.remove(); } });

const account: PublicAccount = { id: "account-1", displayName: "芯芯", email: "person@example.com" };

function mount(value: Partial<AppContextValue>) {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  const context = { t: (zh: string) => zh, language: "zh", account, ...value } as unknown as AppContextValue;
  act(() => render(<AppContext.Provider value={context}><AccountPage /></AppContext.Provider>, host));
  return host;
}

it("routes /app/account to the account page", () => {
  expect(matchRoute("/app/account")).toEqual({ name: "account" });
  expect(routePath({ name: "account" })).toBe("/app/account");
});

it("saves a trimmed name and updates the signed-in account", async () => {
  const updated: PublicAccount[] = [];
  const updateAccountProfile = vi.fn(async (displayName: string) => ({
    account: { ...account, displayName },
  }));
  const client = { updateAccountProfile } as unknown as GatewayClient;
  const host = mount({ client, updateAccount: (next) => updated.push(next) });

  const input = host.querySelector<HTMLInputElement>("#account-name")!;
  const save = [...host.querySelectorAll("button")].find((b) => b.textContent?.includes("保存"))!;
  expect(save.hasAttribute("disabled")).toBe(true);

  act(() => {
    input.value = "  新名字  ";
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  expect(save.hasAttribute("disabled")).toBe(false);
  await act(async () => { save.click(); });

  expect(updateAccountProfile).toHaveBeenCalledWith("新名字");
  expect(updated).toEqual([{ ...account, displayName: "新名字" }]);
});

it("refuses a non-image avatar before opening the editor", () => {
  const client = { updateAccountProfile: vi.fn(), uploadAccountAvatar: vi.fn() } as unknown as GatewayClient;
  const host = mount({ client });
  const fileInput = host.querySelector<HTMLInputElement>('input[type="file"]')!;
  const file = new File(["not an image"], "notes.txt", { type: "text/plain" });
  Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
  act(() => { fileInput.dispatchEvent(new Event("change", { bubbles: true })); });

  expect(host.querySelector(".error-notice")?.textContent).toContain("头像不符合要求");
  expect(client.uploadAccountAvatar).not.toHaveBeenCalled();
});
