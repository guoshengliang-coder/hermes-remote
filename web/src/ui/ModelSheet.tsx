import { useEffect, useMemo, useState } from "preact/hooks";
import type { ModelOptionsResponse } from "../api/gateway";
import { useDefaultModel } from "../app/defaultModel";
import { toAppError } from "../app/failures";
import { modelKey, modelPrefs, recordModelUse, rememberReasoning, toggleFavoriteModel } from "../app/localPrefs";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { HermesSocketError } from "../hermes/client";
import { REASONING_VALUES, type ReasoningValue } from "../hermes/params";
import { sessionModelCommand } from "../hermes/slash";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon, RefreshIcon, StarIcon } from "./icons";
import { Sheet } from "./Sheet";

// Model selector (DESIGN §5.17, Android ModelSelector.kt / ModelsViewModel): the current model, a
// reasoning-effort control, quick switch (recents), favourites and every provider's models. A
// switch is `/model <m> --provider <p> --session` — this conversation only — and reasoning is the
// session's `reasoning` key; the Gateway admits exactly those two shapes (Web batch 4).

export const REASONING_LABEL: Record<ReasoningValue, [string, string]> = {
  none: ["关", "Off"],
  minimal: ["最小", "Min"],
  low: ["低", "Low"],
  medium: ["中", "Med"],
  high: ["高", "High"],
  xhigh: ["极高", "XHigh"],
  max: ["最高", "Max"],
  ultra: ["超高", "Ultra"],
};

export function isReasoning(value: string | null | undefined): value is ReasoningValue {
  return typeof value === "string" && (REASONING_VALUES as readonly string[]).includes(value);
}

/** Composer chip text: `model · effort`, 默认模型 without a known model. */
export function modelChipLabel(model: string | null, reasoning: string | null, language: "zh" | "en"): string {
  const name = model?.trim() || (language === "en" ? "Default model" : "默认模型");
  return isReasoning(reasoning) ? `${name} · ${REASONING_LABEL[reasoning][language === "en" ? 1 : 0]}` : name;
}

export interface ModelActions {
  switchModel: (provider: string, model: string) => Promise<void>;
  reasoning: () => Promise<string | null>;
  setReasoning: (value: ReasoningValue) => Promise<void>;
}

function switchError(error: unknown): AppError {
  // A slash worker that never started cannot run ANY command: not retryable (Android HG-28).
  if (error instanceof HermesSocketError && error.kind === "rpc" && error.code === 5030) return appError("HR-RPC-007", error.message);
  return appError("HR-RPC-004", error instanceof Error ? error.message : String(error));
}

export function ModelSheet({
  current,
  profile,
  explicitOverride,
  actions,
  onSwitched,
  onReasoning,
  onClose,
}: {
  current: { model: string | null; provider: string | null };
  profile: string | null;
  explicitOverride: boolean;
  actions: ModelActions;
  onSwitched: (provider: string, model: string, restored: boolean) => void;
  onReasoning: (value: string) => void;
  onClose: () => void;
}) {
  const { t, language, client, device, features } = useApp();
  const deviceId = device?.deviceId ?? "";
  const defaultModel = useDefaultModel(client, deviceId, profile, features.has("default-model"));
  const [providers, setProviders] = useState<NonNullable<ModelOptionsResponse["providers"]> | null>(null);
  const [listError, setListError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [reasoning, setReasoningState] = useState<string | null>(null);
  const [prefs, setPrefs] = useState(() => modelPrefs(deviceId));
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => {
    let live = true;
    setListError(null);
    client.modelOptions(deviceId, profile).then(
      (body) => {
        if (!live) return;
        const list = Array.isArray(body?.providers) ? body.providers : [];
        setProviders(list);
        const expanded = list.find((p) => p.slug === current.provider || p.models?.includes(current.model ?? ""))?.slug
          ?? list.find((p) => p.is_current)?.slug;
        if (expanded) setOpen(new Set([expanded]));
      },
      (e: unknown) => live && setListError({ ...appError("HR-RPC-003", toAppError(e, "device").details ?? null) }),
    );
    return () => {
      live = false;
    };
  }, [attempt]);

  useEffect(() => {
    let live = true;
    actions.reasoning().then((value) => live && setReasoningState(value), () => undefined);
    return () => {
      live = false;
    };
  }, []);

  const currentKey = current.model ? modelKey(current.provider ?? providers?.find((p) => p.models?.includes(current.model!))?.slug ?? "", current.model) : null;
  const all = useMemo(() => (providers ?? []).flatMap((p) => (p.models ?? []).map((m) => ({ provider: p.slug, providerName: p.name || p.slug, model: m, key: modelKey(p.slug, m) }))), [providers]);
  const byKey = useMemo(() => new Map(all.map((o) => [o.key, o])), [all]);

  async function choose(provider: string, model: string, restored = false) {
    if (pending) return;
    const key = modelKey(provider, model);
    setPending(key);
    setError(null);
    try {
      await actions.switchModel(provider, model);
      if (!restored) recordModelUse(deviceId, key);
      // The effort last used with this model comes back with it (Android applyReasoningPresetFor).
      const preset = restored ? null : modelPrefs(deviceId).presets[key];
      if (isReasoning(preset)) {
        await actions.setReasoning(preset).then(() => onReasoning(preset), () => undefined);
      }
      onSwitched(provider, model, restored);
      onClose();
    } catch (e) {
      setError(switchError(e));
    } finally {
      setPending(null);
    }
  }

  async function pickReasoning(value: ReasoningValue) {
    const before = reasoning;
    setReasoningState(value); // optimistic, rolled back on failure (Android HR-RPC-006)
    setError(null);
    try {
      await actions.setReasoning(value);
      onReasoning(value);
      if (currentKey) rememberReasoning(deviceId, currentKey, value);
    } catch (e) {
      setReasoningState(before);
      setError(appError("HR-RPC-006", e instanceof Error ? e.message : String(e)));
    }
  }

  const row = (o: { provider: string; model: string; key: string }) => {
    const selected = o.key === currentKey || (!currentKey && o.model === current.model);
    const admissible = sessionModelCommand(o.provider, o.model) !== null;
    const fav = prefs.favorites.includes(o.key);
    return (
      <div class={`model-row${selected ? " selected" : ""}`} key={o.key}>
        <button type="button" class="model-pick" disabled={!admissible || pending !== null} onClick={() => void choose(o.provider, o.model)}>
          <span class="model-row-copy">
            <span class="model-name mono">{o.model}</span>
            <span class="model-provider">{byKey.get(o.key)?.providerName ?? o.provider}</span>
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
          onClick={() => setPrefs({ ...prefs, favorites: toggleFavoriteModel(deviceId, o.key) })}
        >
          <StarIcon size={16} filled={fav} />
        </button>
      </div>
    );
  };

  const recents = prefs.recents.map((k) => byKey.get(k)).filter((o): o is NonNullable<typeof o> => Boolean(o));
  const favorites = prefs.favorites.map((k) => byKey.get(k)).filter((o): o is NonNullable<typeof o> => Boolean(o));
  const configured = defaultModel.model;
  const overridden = Boolean(configured && (explicitOverride || (current.model !== null && current.model !== configured.model) || (current.provider !== null && current.provider !== configured.provider)));
  const canRestore = Boolean(overridden && configured && sessionModelCommand(configured.provider, configured.model));

  return (
    <Sheet title={t("选择模型", "Select model")} closeLabel={t("关闭", "Close")} onClose={onClose} wide
      headerAction={<button type="button" class="icon-button" aria-label={t("刷新列表", "Refresh list")} onClick={() => setAttempt((n) => n + 1)}><RefreshIcon size={18} /></button>}>
      <div class="model-status">
        <span class="model-status-content">
          <span class="model-status-title">
            <span class="model-status-name mono">{current.model || t("默认模型", "Default model")}</span>
            <span class="model-status-label">{t("当前使用", "In use")}</span>
          </span>
          <span class="model-status-note">{configured ? (overridden
            ? t(`此对话覆盖 · 默认 ${configured.model}`, `Conversation override · default ${configured.model}`)
            : t(`跟随默认 · ${configured.model}`, `Following default · ${configured.model}`))
            : (current.provider ? `${current.provider} · ` : "") + t("当前会话", "Current conversation")}</span>
          {canRestore && configured ? <button type="button" class="model-restore text-button" disabled={pending !== null}
            onClick={() => void choose(configured.provider, configured.model, true)}>{t("恢复默认模型", "Restore default model")}</button> : null}
          <span class="reasoning-row">
            <label class="reasoning-label" for="reasoning-select">{t("推理强度", "Reasoning effort")}</label>
            <select id="reasoning-select" class="reasoning-select" value={isReasoning(reasoning) ? reasoning : ""}
              onChange={(e) => { const value = (e.target as HTMLSelectElement).value; if (isReasoning(value)) void pickReasoning(value); }}>
              {!isReasoning(reasoning) ? <option value="">{t("默认", "Default")}</option> : null}
              {REASONING_VALUES.map((v) => <option value={v} key={v}>{REASONING_LABEL[v][language === "en" ? 1 : 0]}</option>)}
            </select>
          </span>
        </span>
      </div>
      {error ? <ErrorNotice error={error} language={language} variant="inline" /> : null}
      {defaultModel.error ? <ErrorNotice error={defaultModel.error} language={language} onRetry={defaultModel.retry} variant="inline" /> : null}
      {listError ? <ErrorNotice error={listError} language={language} onRetry={() => setAttempt(attempt + 1)} /> : null}
      {!providers && !listError ? <div class="center-spinner"><span class="spinner" /></div> : null}
      {providers && all.length === 0 ? <p class="picker-note">{t("暂无可选模型", "No models available")}</p> : null}
      {recents.length ? (
        <section class="model-recents">
          <div class="search-section-head">{t("快捷切换", "Quick switch")}</div>
          <div class="model-recents-scroll">{recents.slice(0, 5).map((o) => (
            <button type="button" class="model-recent-chip mono" key={o.key} disabled={pending !== null || sessionModelCommand(o.provider, o.model) === null}
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
      {providers?.map((p) => {
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
