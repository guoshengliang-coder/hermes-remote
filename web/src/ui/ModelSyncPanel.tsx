import { useEffect, useState } from "preact/hooks";
import { useApp } from "../app/store";
import { ErrorNotice } from "./ErrorNotice";
import { REASONING_LABEL } from "./modelPreferenceLabels";
import type { ReasoningValue } from "../hermes/params";
import type {
  AccountModelPreferences,
  legacyModelCandidates,
} from "../account/modelPreferences";

/** Same scope/status controls in the session and default-model picker; no new settings route. */
export function ModelSyncPanel({
  legacy,
}: {
  legacy: ReturnType<typeof legacyModelCandidates>;
}) {
  const { modelSync, language, t, account, device } = useApp();
  const [confirm, setConfirm] = useState<AccountModelPreferences | null>(null);
  const [page, setPage] = useState(0);
  useEffect(() => {
    setConfirm(null);
    setPage(0);
  }, [modelSync?.view.owner, modelSync?.client]);
  if (!modelSync) return null;
  const { view, client } = modelSync;
  const currentPage = Math.min(
    page,
    Math.max(0, Math.ceil(view.pending.length / 5) - 1),
  );
  const pending = view.pending.slice(currentPage * 5, currentPage * 5 + 5);
  const label = (value: boolean | ReasoningValue | null) =>
    value === null
      ? t("未记住", "Not saved")
      : typeof value === "boolean"
        ? value
          ? t("收藏", "Favorite")
          : t("取消收藏", "Unfavorite")
        : REASONING_LABEL[value][language === "en" ? 1 : 0];
  return (
    <section
      class="model-sync-panel"
      aria-label={t("账号模型偏好", "Account model preferences")}
    >
      <p class="picker-note" role="status">
        {!view.enabled
          ? t(
              "同步暂不可用，已保存的账号偏好仍保留。",
              "Sync is paused. Saved account preferences are retained.",
            )
          : !view.ready
            ? t("正在读取账号模型偏好…", "Loading account model preferences…")
            : view.pending.length
              ? t(
                  `${view.pending.length} 项模型偏好等待同步或确认`,
                  `${view.pending.length} model preferences waiting to sync or review`,
                )
              : t(
                  "收藏与推理偏好随账号同步",
                  "Favorites and reasoning preferences sync with your account",
                )}
      </p>
      {view.error ? (
        <ErrorNotice
          error={view.error}
          language={language}
          variant="inline"
          onRetry={
            view.error.retryable ? () => void client?.refresh() : undefined
          }
        />
      ) : null}
      {view.enabled && view.ready ? (
        <button
          type="button"
          class="text-button"
          onClick={() => void client?.refresh()}
        >
          {t("刷新模型偏好", "Refresh model preferences")}
        </button>
      ) : null}
      {view.enabled && view.ready && legacy.candidates.length > 0 ? (
        <button
          type="button"
          class="text-button"
          onClick={() => setConfirm(client)}
        >
          {t("导入此设备的旧偏好", "Import this device's old preferences")}
        </button>
      ) : null}
      {legacy.unresolved > 0 ? (
        <p class="picker-note">
          {t(
            `${legacy.unresolved} 项旧偏好无法唯一识别，仍保留在此设备。`,
            `${legacy.unresolved} old preferences could not be identified uniquely and remain on this device.`,
          )}
        </p>
      ) : null}
      {confirm && confirm === client ? (
        <div
          class="model-confirmation"
          role="alertdialog"
          aria-label={t("确认导入模型偏好", "Confirm model preference import")}
        >
          <p>
            {t(
              `将 ${legacy.candidates.length} 组旧偏好导入当前账号 ${account?.email ?? account?.displayName ?? ""}？本机原数据保留；云端已有设置或取消记录不会自动覆盖。`,
              `Import ${legacy.candidates.length} old model preferences into ${account?.email ?? account?.displayName ?? "this account"}? Originals stay on this device. Existing cloud settings or cancellations are not overwritten automatically.`,
            )}
          </p>
          <button
            type="button"
            class="text-button"
            onClick={() => setConfirm(null)}
          >
            {t("取消", "Cancel")}
          </button>
          <button
            type="button"
            class="text-button"
            onClick={() => {
              setConfirm(null);
              void confirm?.importLegacy(
                legacy.candidates,
                `web-local-models:${device?.deviceId ?? "unknown-mac"}`,
              );
            }}
          >
            {t("确认导入", "Confirm import")}
          </button>
        </div>
      ) : null}
      {pending.map((p) => (
        <div class="model-sync-operation" key={p.operation.opId}>
          <p class="picker-note">
            {p.operation.model} · {p.operation.provider} ·{" "}
            {p.operation.field === "favorite"
              ? t("收藏", "Favorite")
              : t("推理偏好", "Reasoning preference")}
          </p>
          <p class="picker-note">
            {t("本机选择：", "Local choice: ")}
            {label(p.operation.value as boolean | ReasoningValue | null)} ·{" "}
            {t("云端：", "Cloud: ")}
            {label(
              view.rows.find(
                (r) =>
                  r.provider === p.operation.provider &&
                  r.model === p.operation.model,
              )?.[p.operation.field] ??
                (p.operation.field === "favorite" ? false : null),
            )}
          </p>
          {p.error ? (
            <>
              <ErrorNotice
                error={p.error}
                language={language}
                variant="inline"
              />
              <button
                type="button"
                class="text-button"
                onClick={() => void client?.discard(p.operation.opId)}
              >
                {t("保留云端值", "Keep cloud value")}
              </button>
              <button
                type="button"
                class="text-button"
                disabled={!view.enabled}
                onClick={() => void client?.reapply(p.operation.opId)}
              >
                {t("重新应用本机选择", "Apply local choice again")}
              </button>
            </>
          ) : (
            <p class="picker-note">{t("等待同步", "Waiting to sync")}</p>
          )}
        </div>
      ))}
      {view.pending.length > 5 ? (
        <div>
          <button
            class="text-button"
            type="button"
            disabled={currentPage === 0}
            onClick={() => setPage(currentPage - 1)}
          >
            {t("上一页", "Previous")}
          </button>
          <button
            class="text-button"
            type="button"
            disabled={(currentPage + 1) * 5 >= view.pending.length}
            onClick={() => setPage(currentPage + 1)}
          >
            {t("下一页", "Next")}
          </button>
        </div>
      ) : null}
    </section>
  );
}
