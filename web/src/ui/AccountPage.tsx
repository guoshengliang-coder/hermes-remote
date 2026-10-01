import { useEffect, useRef, useState } from "preact/hooks";
import type { PendingAttachment } from "../chat/attachments";
import { toAppError } from "../app/failures";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { AccountAvatar } from "./AccountAvatar";
import { ErrorNotice } from "./ErrorNotice";
import { ImageEditor } from "./ImageEditor";
import { BackIcon } from "./icons";

// Account detail page (HG-181): the card page's account card opens this. Rename, upload an avatar,
// sign out, and the existing delete-account link to the Gateway account center. Only the Web app
// reaches it; Android and Desktop keep their own account screens.

/** The Gateway accepts 1-40 characters (gateway/src/account/account-http-controller.ts). */
const MAX_NAME = 40;
/** Square side the avatar is downscaled to before upload, matching Android's 512px photo. */
const AVATAR_SIDE = 512;

export function AccountPage() {
  const { t, language, client, account, updateAccount, signOut, flash } = useApp();
  const [name, setName] = useState(account?.displayName ?? "");
  const [savingName, setSavingName] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [pending, setPending] = useState<PendingAttachment | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // A save/upload answers with the resolved account; keep the field in step with it.
  useEffect(() => {
    setName(account?.displayName ?? "");
  }, [account?.displayName]);

  const trimmed = name.trim();
  const nameChanged = trimmed.length > 0 && trimmed !== (account?.displayName ?? "");

  async function saveName() {
    if (!nameChanged || savingName) return;
    setSavingName(true);
    setError(null);
    try {
      const { account: next } = await client.updateAccountProfile(trimmed);
      updateAccount(next);
      flash(t("已保存", "Saved"));
    } catch (e) {
      setError(toAppError(e, "account"));
    } finally {
      setSavingName(false);
    }
  }

  function chooseAvatar(file: File | undefined) {
    if (!file) return;
    if (!["image/png", "image/jpeg", "image/webp"].includes(file.type.toLowerCase())) {
      setError(appError("HR-WEB-011", `type=${file.type || "unknown"}`));
      return;
    }
    setError(null);
    setPending({
      id: `avatar-${Date.now()}`,
      file,
      name: file.name || "avatar",
      mimeType: file.type,
      kind: "image",
      previewUrl: URL.createObjectURL(file),
    });
  }

  async function uploadAvatar(blob: Blob) {
    setUploading(true);
    setError(null);
    try {
      const { account: next } = await client.uploadAccountAvatar(await squareAvatar(blob));
      updateAccount(next);
      flash(t("头像已更新", "Avatar updated"));
    } catch (e) {
      setError(toAppError(e, "account"));
    } finally {
      setUploading(false);
      setPending(null);
    }
  }

  return (
    <div class="page list-page">
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("返回", "Back")} onClick={() => navigate({ name: "list" })}>
            <BackIcon />
          </button>
          <h1 class="topbar-title left">{t("账号", "Account")}</h1>
          <span class="topbar-spacer" />
        </div>
      </header>
      <div class="page-scroll">
        <main class="content account-page">
          {error ? <ErrorNotice error={error} language={language} onDismiss={() => setError(null)} /> : null}
          <section class="account-profile" aria-label={t("Hermes GO 账号", "Hermes GO account")}>
            <button
              type="button"
              class="account-avatar-button"
              aria-label={t("更换头像", "Change avatar")}
              disabled={uploading}
              onClick={() => fileInput.current?.click()}
            >
              <AccountAvatar account={account} large />
              <span class="account-avatar-badge" aria-hidden="true">
                {uploading ? <span class="spinner tiny" /> : t("更换", "Change")}
              </span>
            </button>
            <input
              ref={fileInput}
              type="file"
              accept="image/png,image/jpeg,image/webp"
              hidden
              onChange={(event) => {
                chooseAvatar(event.currentTarget.files?.[0]);
                event.currentTarget.value = "";
              }}
            />
            <p class="account-email">{account?.email ?? "—"}</p>
          </section>

          <section class="account-field">
            <label class="account-label" for="account-name">{t("名字", "Name")}</label>
            <div class="account-name-row">
              <input
                id="account-name"
                class="account-input"
                type="text"
                autocomplete="nickname"
                maxLength={MAX_NAME}
                value={name}
                onInput={(event) => setName(event.currentTarget.value)}
              />
              <button
                type="button"
                class="primary-button inline"
                disabled={!nameChanged || savingName}
                onClick={() => void saveName()}
              >
                {savingName ? <span class="spinner tiny" aria-hidden="true" /> : null}
                {t("保存", "Save")}
              </button>
            </div>
          </section>

          <section class="account-actions">
            <button type="button" class="account-action" onClick={() => void signOut()}>{t("退出登录", "Sign out")}</button>
            <a class="account-action danger" href="/account">{t("删除账号", "Delete account")}</a>
          </section>
        </main>
      </div>
      {pending ? (
        <ImageEditor
          cropSquare
          attachment={pending}
          onDone={(next) => void uploadAvatar(next.file)}
          onClose={() => {
            if (pending.previewUrl) URL.revokeObjectURL(pending.previewUrl);
            setPending(null);
          }}
        />
      ) : null}
    </div>
  );
}

/** Center-crop to a square and downscale to AVATAR_SIDE, as JPEG. Falls back to the input blob. */
async function squareAvatar(blob: Blob): Promise<Blob> {
  if (typeof createImageBitmap !== "function") return blob;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(blob);
  } catch {
    return blob;
  }
  try {
    const canvas = document.createElement("canvas");
    canvas.width = AVATAR_SIDE;
    canvas.height = AVATAR_SIDE;
    const ctx = canvas.getContext("2d");
    if (!ctx) return blob;
    const side = Math.min(bitmap.width, bitmap.height);
    const sx = (bitmap.width - side) / 2;
    const sy = (bitmap.height - side) / 2;
    ctx.drawImage(bitmap, sx, sy, side, side, 0, 0, AVATAR_SIDE, AVATAR_SIDE);
    const scaled = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.9));
    return scaled ?? blob;
  } finally {
    bitmap.close();
  }
}
