import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import type { ModelOptionsResponse } from "../api/gateway";
import { useInstanceModels } from "../app/instanceModels";
import { hiddenModel } from "../hermes/instanceModelPreferences";
import { useDefaultModel } from "../app/defaultModel";
import { toAppError } from "../app/failures";
import { modelKey as legacyModelKey, modelPrefs, recordModelUse, rememberReasoning, toggleFavoriteModel } from "../app/localPrefs";
import { legacyModelCandidates, modelTuple, projectedModelPrefs } from "../account/modelPreferences";
import { ModelSyncPanel } from "./ModelSyncPanel";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { HermesSocketError } from "../hermes/client";
import { REASONING_VALUES, type ReasoningValue } from "../hermes/params";
import { modelIdentifiers, ModelSwitchUnconfirmed, type ModelSwitchResult } from "../hermes/modelSelection";
import { sessionModelCommand } from "../hermes/slash";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon, RefreshIcon, StarIcon } from "./icons";
import { Sheet } from "./Sheet";

// Model selector (DESIGN §5.17, Android ModelSelector.kt / ModelsViewModel): the current model, a
// reasoning-effort control, quick switch (recents), favourites and every provider's models. A
// New Gateways admit config.set model in one session-only shape; older ones retain slash.exec.
// Default editing goes through the Connector's metadata-stripping main-model route.

export { REASONING_LABEL } from "./modelPreferenceLabels";
import { REASONING_LABEL } from "./modelPreferenceLabels";

export function isReasoning(value: string | null | undefined): value is ReasoningValue {
  return typeof value === "string" && (REASONING_VALUES as readonly string[]).includes(value);
}

/** Composer chip text: `model · effort`, 默认模型 without a known model. */
export function modelChipLabel(model: string | null, reasoning: string | null, language: "zh" | "en"): string {
  const name = model?.trim() || (language === "en" ? "Default model" : "默认模型");
  return isReasoning(reasoning) ? `${name} · ${REASONING_LABEL[reasoning][language === "en" ? 1 : 0]}` : name;
}

export interface ModelActions {
  switchModel: (provider: string, model: string, confirmed?: boolean) => Promise<ModelSwitchResult>;
  reasoning: () => Promise<string | null>;
  setReasoning: (value: ReasoningValue) => Promise<void>;
}

function switchError(error: unknown): AppError {
  if (error instanceof ModelSwitchUnconfirmed || (error instanceof HermesSocketError && ["timeout", "closed"].includes(error.kind))) return appError("HR-RPC-008", "Model switch outcome is unconfirmed");
  // A slash worker that never started cannot run ANY command: not retryable (Android HG-28).
  if (error instanceof HermesSocketError && error.kind === "rpc" && error.code === 5030) return appError("HR-RPC-007", error.message);
  return appError("HR-RPC-004", error instanceof Error ? error.message : String(error));
}

interface ModelSheetProps {
  current: { model: string | null; provider: string | null };
  profile: string | null;
  explicitOverride: boolean;
  scope?: "session" | "default";
  /** Set when the sheet is opened from the card page so it paints above the drawer (HG-177). */
  aboveDrawer?: boolean;
  actions: ModelActions;
  onSwitched: (provider: string, model: string, restored: boolean, warning?: boolean) => void;
  onReasoning: (value: string) => void;
  onClose: () => void;
}

// Changing Mac/profile creates fresh state, and pending old requests cannot update the new view.
export function ModelSheet(props: ModelSheetProps) {
  const { device, account } = useApp();
  return <ModelSheetContent key={JSON.stringify([account?.id, device?.deviceId, props.profile, props.scope])} {...props} />;
}

function ModelSheetContent({ current: sessionCurrent, profile, explicitOverride, scope = "session", aboveDrawer, actions, onSwitched, onReasoning, onClose }: ModelSheetProps) {
  const { t, language, client, device, features, modelSync } = useApp();
  const deviceId = device?.deviceId ?? "";
  const instance = useInstanceModels(client, deviceId, features.has("instance-model-preferences"), device?.access === "owner");
  const instanceMode = instance.status !== "legacy";
  const [showHidden, setShowHidden] = useState(false);
  const [confirmImport, setConfirmImport] = useState(false);
  const [importing, setImporting] = useState(false);
  const defaultModel = useDefaultModel(client, deviceId, profile, features.has("default-model"));
  const editingDefault = scope === "default";
  const current = editingDefault ? { model: defaultModel.model?.model ?? null, provider: defaultModel.model?.provider ?? null } : sessionCurrent;
  const live = useRef(true);
  const locked = useRef(false);
  const retry = useRef<(() => void) | undefined>(undefined);
  useEffect(() => () => { live.current = false; }, []);
  const [confirmation, setConfirmation] = useState<{ provider: string; model: string; restored: boolean } | null>(null);
  const [notice, setNotice] = useState<"deferred" | "warning" | null>(null);
  const [providers, setProviders] = useState<NonNullable<ModelOptionsResponse["providers"]> | null>(null);
  const [listError, setListError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [reasoning, setReasoningState] = useState<string | null>(null);
  const [prefs, setPrefs] = useState(() => modelPrefs(deviceId));
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const modelKey=modelSync?modelTuple:legacyModelKey;
  const synced=modelSync?projectedModelPrefs(modelSync.view):[];
  const oldFavorites=modelSync?synced.filter((r)=>r.favorite).map((r)=>modelTuple(r.provider,r.model)):prefs.favorites;
  const effectiveFavorites = instanceMode ? (instance.snapshot?.rows.filter(r => r.favorite).map(r => modelKey(r.provider,r.model)) ?? []) : oldFavorites;
  const effectivePresets=modelSync?Object.fromEntries(synced.filter((r)=>r.reasoningPreset!==null).map((r)=>[modelTuple(r.provider,r.model),r.reasoningPreset!])):prefs.presets;
  const preferencePending=(provider:string,model:string,field:"favorite"|"reasoningPreset")=>Boolean(modelSync&&(!modelSync.view.ready||!modelSync.view.enabled||modelSync.view.pending.some((p)=>p.operation.provider===provider&&p.operation.model===model&&p.operation.field===field)));

  useEffect(() => {
    let active = true;
    setListError(null);
    client.modelOptions(deviceId, profile).then(
      (body) => {
        if (!active) return;
        const list = Array.isArray(body?.providers) ? body.providers : [];
        setProviders(list);
        const expanded = list.find((p) => p.slug === current.provider || p.models?.includes(current.model ?? ""))?.slug
          ?? list.find((p) => p.is_current)?.slug;
        if (expanded) setOpen(new Set([expanded]));
      },
      (e: unknown) => active && setListError({ ...appError("HR-RPC-003", toAppError(e, "device").details ?? null) }),
    );
    return () => {
      active = false;
    };
  }, [attempt, current.provider]);

  useEffect(() => {
    let active = true;
    if (editingDefault) return;
    actions.reasoning().then((value) => active && setReasoningState(value), () => undefined);
    return () => {
      active = false;
    };
  }, []);

  const currentKey = current.model ? modelKey(current.provider ?? providers?.find((p) => p.models?.includes(current.model!))?.slug ?? "", current.model) : null;
  const all = useMemo(() => (providers ?? []).flatMap((p) => (p.models ?? []).map((m) => ({ provider: p.slug, providerName: p.name || p.slug, model: m, key: modelKey(p.slug, m) }))), [providers, modelKey]);
  const byKey = useMemo(() => new Map(all.map((o) => [o.key, o])), [all]);

  function admissible(provider: string, model: string) {
    return editingDefault || features.has("session-model-config") ? modelIdentifiers(provider, model) : sessionModelCommand(provider, model) !== null;
  }

  async function choose(provider: string, model: string, restored = false, confirmed = false) {
    if (locked.current || (confirmation && !confirmed)) return;
    locked.current = true;
    retry.current = () => { void choose(provider, model, restored, confirmed); };
    const key = modelKey(provider, model);
    setPending(key);
    setConfirmation(null);
    setError(null);
    setNotice(null);
    try {
      let result: ModelSwitchResult;
      let appliedProvider = provider;
      if (editingDefault) {
        const response = await client.setDefaultModel(deviceId, provider, model, profile, confirmed);
        if ("confirm_required" in response && response.confirm_required === true) result = { kind: "confirmation" };
        else if ("model" in response && modelIdentifiers(response.provider, response.model)) {
          appliedProvider = response.provider;
          result = { kind: "applied", model: response.model, warning: false };
        } else throw new Error("invalid default model result");
      } else result = await actions.switchModel(provider, model, confirmed);
      if (!live.current) return;
      if (result.kind === "confirmation") {
        setConfirmation({ provider, model, restored });
        return;
      }
      if (result.kind === "deferred") { setNotice("deferred"); return; }

      let presetFailed = false;
      if (!editingDefault) {
        // Storage is best effort and cannot turn an applied model into a failed switch.
        try { recordModelUse(deviceId, legacyModelKey(provider, result.model)); setPrefs(modelPrefs(deviceId)); } catch { /* local preference only */ }
        const preset = modelSync?effectivePresets[modelKey(provider,result.model)]:modelPrefs(deviceId).presets[modelKey(provider, result.model)];
        if (isReasoning(preset)) {
          try { await actions.setReasoning(preset); if (live.current) { onReasoning(preset); setReasoningState(preset); } }
          catch { presetFailed = true; if (live.current) setError(appError("HR-RPC-006")); }
        }
      }
      if (!live.current) return;
      onSwitched(appliedProvider, result.model, restored, result.warning || presetFailed);
      if (result.warning) setNotice("warning");
      else if (!presetFailed) onClose();
    } catch (e) {
      if (live.current) setError(editingDefault ? appError("HR-RPC-005") : switchError(e));
    } finally {
      locked.current = false;
      if (live.current) setPending(null);
    }
  }

  async function pickReasoning(value: ReasoningValue) {
    if (locked.current || confirmation) return;
    locked.current = true;
    setPending("reasoning");
    retry.current = () => { void pickReasoning(value); };
    const before = reasoning;
    setReasoningState(value);
    setError(null);
    try {
      await actions.setReasoning(value);
      if (!live.current) return;
      onReasoning(value);
      if(modelSync){const pair=all.find((r)=>r.key===currentKey);if(pair)await modelSync.client?.set(pair.provider,pair.model,"reasoningPreset",value);}
      else try { if (currentKey) rememberReasoning(deviceId, currentKey, value); setPrefs(modelPrefs(deviceId)); } catch { /* local preference only */ }
    } catch (e) {
      if (live.current) { setReasoningState(before); setError(appError("HR-RPC-006")); }
    } finally { locked.current = false; if (live.current) setPending(null); }
  }

  const row = (o: { provider: string; model: string; key: string; unavailable?:boolean }) => {
    const selected = o.key === currentKey || (!currentKey && o.model === current.model);
    const hidden = instanceMode && hiddenModel(instance.snapshot, o.provider, o.model);
    if (hidden && !showHidden) return null;
    const allowed = !hidden && !o.unavailable&&admissible(o.provider, o.model);
    const fav = effectiveFavorites.includes(o.key);
    return (
      <div class={`model-row${selected ? " selected" : ""}`} key={o.key}>
        <button type="button" class="model-pick" disabled={!allowed || pending !== null || confirmation !== null} onClick={() => void choose(o.provider, o.model)}>
          <span class="model-row-copy">
            <span class="model-name mono">{o.model}</span>
            <span class="model-provider">{byKey.get(o.key)?.providerName ?? o.provider}</span>
            {o.unavailable?<span class="model-state">{t("此 Mac 不可用","Unavailable on this Mac")}</span>:null}
            {!editingDefault && isReasoning(effectivePresets[o.key]) ? <span class="model-state">{REASONING_LABEL[effectivePresets[o.key] as ReasoningValue][language === "en" ? 1 : 0]}</span> : null}
          </span>
          <span class="model-state">
            {pending === o.key ? t("切换中…", "Switching…") : null}
          </span>
        </button>
        <button
          type="button"
          class={`icon-button model-star${fav ? " on" : ""}`}
          aria-pressed={fav}
          aria-label={fav ? t("取消收藏", "Remove from favorites") : t("收藏", "Add to favorites")}
          disabled={instanceMode ? !instance.snapshot || instance.pending || importing || !instance.writable : preferencePending(o.provider,o.model,"favorite")}
          onClick={() => {if(instanceMode)void instance.set(o.provider,o.model,"favorite",!fav);else if(modelSync)void modelSync.client?.set(o.provider,o.model,"favorite",!fav);else setPrefs({ ...prefs, favorites: toggleFavoriteModel(deviceId, o.key) });}}
        >
          <StarIcon size={16} filled={fav} />
        </button>
        {instanceMode ? <button type="button" class="text-button" disabled={!instance.snapshot || instance.pending || importing || !instance.writable}
          aria-label={t(hidden ? `恢复显示 ${o.model}` : `隐藏 ${o.model}`, hidden ? `Show ${o.model}` : `Hide ${o.model}`)}
          onClick={() => void instance.set(o.provider,o.model,"hidden",!hidden)}>{t(hidden ? "显示" : "隐藏", hidden ? "Show" : "Hide")}</button> : null}
      </div>
    );
  };

  const recents = prefs.recents.map((k) => {const matches=all.filter((o)=>legacyModelKey(o.provider,o.model)===k);return matches.length===1?matches[0]:undefined;}).filter((o): o is NonNullable<typeof o> => Boolean(o) && !(instanceMode && hiddenModel(instance.snapshot, o!.provider, o!.model)));
  const favorites = instanceMode ? (instance.snapshot?.rows.filter(r=>r.favorite).map(r=>({...r,key:modelKey(r.provider,r.model),unavailable:!byKey.has(modelKey(r.provider,r.model))})) ?? []) : modelSync?synced.filter((r)=>r.favorite).map((r)=>({...r,key:modelTuple(r.provider,r.model),unavailable:!byKey.has(modelTuple(r.provider,r.model))})):
    effectiveFavorites.map((k) => byKey.get(k)).filter((o): o is NonNullable<typeof o> => Boolean(o));
  const legacy=legacyModelCandidates(prefs,all);
  const importCandidates = [...(modelSync ? synced.filter(r=>r.favorite) : []), ...legacy.candidates.filter(r=>r.favorite)].filter((r,i,all) => all.findIndex(x=>x.provider===r.provider&&x.model===r.model)===i);
  const importable = importCandidates.filter(r => !(instance.snapshot?.rows.some(x=>x.provider===r.provider&&x.model===r.model&&x.favoriteRevision!=="0")));
  async function importFavorites() {
    setConfirmImport(false); setImporting(true);
    try { for (const candidate of importable) { if (!live.current || !await instance.set(candidate.provider,candidate.model,"favorite",true,true)) break; } }
    finally { if(live.current)setImporting(false); }
  }
  const configured = defaultModel.model;
  const overridden = Boolean(configured && (explicitOverride || (current.model !== null && current.model !== configured.model) || (current.provider !== null && current.provider !== configured.provider)));
  const canRestore = Boolean(!editingDefault && overridden && configured && admissible(configured.provider, configured.model));

  return (
    <Sheet title={editingDefault ? t("默认模型", "Default model") : t("选择模型", "Select model")} closeLabel={t("关闭", "Close")} onClose={onClose} wide aboveDrawer={aboveDrawer}
      headerAction={<button type="button" class="icon-button" aria-label={t("刷新列表", "Refresh list")} disabled={pending !== null || confirmation !== null} onClick={() => { setAttempt((n) => n + 1); defaultModel.retry(); }}><RefreshIcon size={18} /></button>}>
      <div class="model-status">
        <span class="model-status-content">
          <span class="model-status-title">
            <span class="model-status-name mono">{current.model || t("默认模型", "Default model")}</span>
            <span class="model-status-label">{editingDefault ? t("当前默认", "Current default") : t("当前使用", "In use")}</span>
          </span>
          <span class="model-status-note">{editingDefault ? (current.provider || "") : configured ? (overridden
            ? t(`此对话覆盖 · 默认 ${configured.model}`, `Conversation override · default ${configured.model}`)
            : t(`跟随默认 · ${configured.model}`, `Following default · ${configured.model}`))
            : (current.provider ? `${current.provider} · ` : "") + t("当前会话", "Current conversation")}</span>
          {canRestore && configured ? <button type="button" class="model-restore text-button" disabled={pending !== null || confirmation !== null}
            onClick={() => void choose(configured.provider, configured.model, true)}>{t("恢复默认模型", "Restore default model")}</button> : null}
          {!editingDefault ? <span class="reasoning-row">
            <label class="reasoning-label" for="reasoning-select">{t("推理强度", "Reasoning effort")}</label>
            <select id="reasoning-select" class="reasoning-select" disabled={pending !== null || confirmation !== null || Boolean(currentKey&&all.find((r)=>r.key===currentKey)&&preferencePending(all.find((r)=>r.key===currentKey)!.provider,current.model!,"reasoningPreset"))} value={isReasoning(reasoning) ? reasoning : ""}
              onChange={(e) => { const value = (e.target as HTMLSelectElement).value; if (isReasoning(value)) void pickReasoning(value); }}>
              {!isReasoning(reasoning) ? <option value="">{t("默认", "Default")}</option> : null}
              {REASONING_VALUES.map((v) => <option value={v} key={v}>{REASONING_LABEL[v][language === "en" ? 1 : 0]}</option>)}
            </select>
          </span> : null}
        </span>
      </div>
      {instanceMode ? <section class="model-sync-panel" aria-label={t("此 Mac 的模型偏好", "This Mac’s model preferences")}>
        <p role="status" class="picker-note">{t(instance.snapshot ? "收藏和隐藏与此 Hermes 实例同步" : "正在读取此 Mac 的模型偏好…", instance.snapshot ? "Favorites and visibility sync with this Hermes instance" : "Loading this Mac’s model preferences…")}</p>
        {!instance.writable ? <p class="picker-note">{t("共享访客只能查看，不能修改主人偏好。", "Shared guests can view, but cannot edit the owner’s preferences.")}</p> : null}
        {instance.error ? <ErrorNotice error={instance.error} language={language} onRetry={() => void instance.refresh()} /> : null}
        <button type="button" class="text-button" onClick={()=>setShowHidden(v=>!v)}>{t(showHidden ? "收起已隐藏模型" : "管理隐藏模型", showHidden ? "Hide hidden models" : "Manage hidden models")}</button>
        {instance.writable && instance.snapshot && importable.length ? <button type="button" class="text-button" disabled={instance.pending||importing} onClick={()=>setConfirmImport(true)}>{t(`导入旧收藏 (${importable.length})`, `Import previous favorites (${importable.length})`)}</button> : null}
        {confirmImport ? <div role="alertdialog" aria-label={t("确认导入", "Confirm import")}>
          <p>{t(`将 ${importable.length} 项旧收藏导入 ${device?.desktopDisplayName ?? "此 Mac"}？原数据保留，不覆盖此 Mac 已有的选择或取消。`, `Import ${importable.length} previous favorites into ${device?.desktopDisplayName ?? "this Mac"}? Originals remain; existing choices and cancellations are preserved.`)}</p>
          <button type="button" onClick={()=>void importFavorites()}>{t("确认导入", "Import")}</button>
          <button type="button" onClick={()=>setConfirmImport(false)}>{t("取消", "Cancel")}</button>
        </div> : null}
      </section> : <><p class="picker-note">{t("此 Hermes 或 Gateway 不支持实例偏好同步，收藏仍独立管理。", "This Hermes or Gateway does not support instance preference sync. Favorites remain independent.")}</p><ModelSyncPanel legacy={legacy}/></>}
      {instanceMode ? <ModelSyncPanel legacy={legacy} reasoningOnly /> : null}
      {modelSync&&current.model&&current.provider&&effectivePresets[modelKey(current.provider,current.model)]?<button type="button" class="text-button" disabled={preferencePending(current.provider,current.model,"reasoningPreset")} onClick={()=>void modelSync.client?.set(current.provider!,current.model!,"reasoningPreset",null)}>{t("清除该模型记忆的推理偏好（不改变当前对话）","Clear this model's saved reasoning preference (keeps this conversation unchanged)")}</button>:null}
      {confirmation ? <div class="model-confirmation" role="alertdialog" aria-label={t("确认模型选择", "Confirm model selection")}>
        <p>{t("所选模型需要确认，可能涉及较高费用或能力限制。是否继续？", "This model needs confirmation and may involve higher costs or capability limits. Continue?")}</p>
        <p class="mono">{confirmation.model} · {confirmation.provider}</p>
        <button type="button" class="text-button" onClick={() => setConfirmation(null)}>{t("取消", "Cancel")}</button>
        <button type="button" class="text-button" onClick={() => void choose(confirmation.provider, confirmation.model, confirmation.restored, true)}>{t("确认切换", "Confirm switch")}</button>
      </div> : null}
      {notice ? <p class="picker-note" role="status">{notice === "deferred"
        ? t("当前回复正在运行，将在下一次发送消息时尝试切换模型。", "A reply is running. The model switch will be attempted on the next message.")
        : t("模型已切换，但可用性仍需检查。请核对模型后再继续。", "The model changed with a warning. Check its availability before continuing.")}</p> : null}
      {error ? <ErrorNotice error={error} language={language} onRetry={error.retryable ? () => retry.current?.() : undefined} variant="inline" /> : null}
      {defaultModel.error ? <ErrorNotice error={defaultModel.error} language={language} onRetry={defaultModel.retry} variant="inline" /> : null}
      {listError ? <ErrorNotice error={listError} language={language} onRetry={() => setAttempt(attempt + 1)} /> : null}
      {!providers && !listError ? <div class="center-spinner"><span class="spinner" /></div> : null}
      {providers && all.length === 0 ? <p class="picker-note">{t("暂无可选模型", "No models available")}</p> : null}
      {!editingDefault && recents.length ? (
        <section class="model-recents">
          <div class="search-section-head">{t("快捷切换", "Quick switch")}</div>
          <div class="model-recents-scroll">{recents.slice(0, 5).map((o) => (
            <button type="button" class="model-recent-chip mono" key={o.key} disabled={pending !== null || confirmation !== null || !admissible(o.provider, o.model)}
              onClick={() => void choose(o.provider, o.model)}>{o.model}</button>
          ))}</div>
        </section>
      ) : null}
      {favorites.length ? (
        <section class="model-provider-group model-favorites">
          <div class="model-provider-head"><StarIcon size={17} filled />{t(`收藏模型 (${favorites.length})`, `Favorites (${favorites.length})`)}</div>
          {favorites.map((o) => row(o))}
        </section>
      ) : null}
      {providers?.filter((p) => p.models?.length).map((p) => {
        const expanded = open.has(p.slug);
        return (
          <section class="model-provider-group" key={p.slug}>
            <button
              type="button"
              class="model-provider-head"
              aria-expanded={expanded}
              onClick={() =>
                setOpen((prev) => {
                  const next = new Set(prev);
                  if (!next.delete(p.slug)) next.add(p.slug);
                  return next;
                })
              }
            >
              <span>{p.name || p.slug}</span>
              <span class="group-count mono">{t(`${p.models?.length ?? 0} 项`, `${p.models?.length ?? 0} items`)}</span>
              <ChevronIcon open={expanded} />
            </button>
            {expanded ? (p.models ?? []).map((m) => row({ provider: p.slug, model: m, key: modelKey(p.slug, m) })) : null}
          </section>
        );
      })}
    </Sheet>
  );
}
