import { expect, it } from "vitest";
import { catalogLabels } from "./profilePreferences";

it("uses Mac display names without changing route keys and accepts legacy profiles", () => {
  expect(catalogLabels([{name:"primary",is_default:true,display_name:" 小云 ",bot_title:"机器人"},
    {name:"writer",display_name:" ",bot_title:"作者"}, {name:"old"}])).toEqual({
    default:"小云",writer:"作者",old:"old"
  });
});
