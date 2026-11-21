import { useState } from "preact/hooks";
import { useProfileNames, useProfilePreferences } from "../app/profilePreferences";
import { useApp } from "../app/store";
import { useBackClose } from "../app/useBackClose";

export function ProfileDisplaySheet({onClose}: {onClose: () => void}) {
  const {t} = useApp();
  const names = useProfileNames();
  const prefs = useProfilePreferences();
  const [editing, edit] = useState<string | null>(null);
  const [alias, setAlias] = useState("");
  useBackClose(onClose);
  return <>
    <div class="drawer-theme-scrim" onClick={onClose}/>
    <div class="drawer-theme-sheet" role="dialog" aria-modal="true">
      <h2>{editing ? t("编辑显示名","Edit display name") : t("Profile 显示设置","Profile visibility")}</h2>
      {editing ? <>
        <label>{editing}<input value={alias} maxLength={24} onInput={e => setAlias(e.currentTarget.value)}/></label>
        <button onClick={() => { prefs.save(editing, {alias: alias.trim()}); edit(null); }}>{t("保存","Save")}</button>
      </> : names.map(name => <div class="drawer-shortcut" key={name}>
        <button class="drawer-shortcut-title" onClick={() => {edit(name); setAlias(prefs.label(name));}}>
          {prefs.label(name)} {prefs.label(name) !== name && <small>{name}</small>}
        </button>
        <input type="checkbox" role="switch" aria-label={t("显示 ","Show ") + name} checked={!prefs.hidden(name)}
          onChange={e => prefs.save(name,{hidden: !e.currentTarget.checked})}/>
      </div>)}
      <button class="drawer-theme-save" onClick={onClose}>{t("关闭","Close")}</button>
    </div>
  </>;
}
