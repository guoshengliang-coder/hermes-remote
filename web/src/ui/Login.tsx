import { useEffect, useRef, useState } from "preact/hooks";
import type { GatewayClient, WebSignInResponse } from "../api/gateway";
import { toAppError } from "../app/failures";
import type { Translate } from "../app/i18n";
import type { AppError, Language } from "../errors";
import { ErrorNotice } from "./ErrorNotice";

// Email one-time-code sign-in (DESIGN §5.19 look, compact brand group, bottom primary button).
// Every step is a user gesture; nothing here is driven by the URL.

interface Challenge {
  challengeId: string;
  email: string;
  resendAfterMs: number;
}

export interface LoginProps {
  client: GatewayClient;
  language: Language;
  t: Translate;
  /** Why the previous session ended (not shown after a user's own sign-out). */
  reason?: AppError | null;
  onSignedIn: (result: WebSignInResponse) => void;
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function Login({ client, language, t, reason, onSignedIn }: LoginProps) {
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [now, setNow] = useState(Date.now());
  const codeRef = useRef<HTMLInputElement>(null);
  const idempotency = useRef<string | null>(null);

  useEffect(() => {
    if (!challenge) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [challenge]);

  useEffect(() => {
    if (challenge) codeRef.current?.focus();
  }, [challenge?.challengeId]);

  const cooldown = challenge ? Math.max(0, Math.ceil((challenge.resendAfterMs - now) / 1000)) : 0;

  async function requestCode(target: string) {
    setBusy(true);
    setError(null);
    try {
      const { challenge: c } = await client.requestEmailChallenge(target);
      const resendAfter = Date.parse(c.resendAfter);
      setChallenge({ challengeId: c.challengeId, email: target, resendAfterMs: Number.isNaN(resendAfter) ? Date.now() + 60_000 : resendAfter });
      setCode("");
      idempotency.current = null;
      setNow(Date.now());
    } catch (e) {
      setError(toAppError(e, "account"));
    } finally {
      setBusy(false);
    }
  }

  async function verify(value: string) {
    if (!challenge || value.length !== 6 || busy) return;
    setBusy(true);
    setError(null);
    idempotency.current ??= crypto.randomUUID();
    try {
      const result = await client.exchangeEmail({ challengeId: challenge.challengeId, email: challenge.email, code: value }, idempotency.current);
      onSignedIn(result);
    } catch (e) {
      idempotency.current = null;
      setError(toAppError(e, "account"));
      setBusy(false);
    }
  }

  function onSubmit(e: Event) {
    e.preventDefault();
    if (challenge) void verify(code);
    else if (EMAIL.test(email.trim())) void requestCode(email.trim());
  }

  const canSend = challenge ? code.length === 6 && !busy : EMAIL.test(email.trim()) && !busy;

  return (
    <form class="login-page" onSubmit={onSubmit} noValidate>
      <div class="login-scroll">
        <div class="brand">
          <img class="brand-icon" src={`${import.meta.env.BASE_URL}icon-192.png`} alt="" width={72} height={72} />
          <div class="brand-word">HERMES GO</div>
          <p class="brand-line">{t("用 Hermes GO 账号登录，连接你的 Mac", "Sign in with your Hermes GO account to reach your Mac")}</p>
        </div>
        {reason ? (
          <div class="reason-banner" role="status">
            <p class="reason-text">{language === "en" ? reason.en : reason.zh}</p>
            <p class="error-code mono">{language === "en" ? `Error code: ${reason.code}` : `错误码：${reason.code}`}</p>
          </div>
        ) : null}
        {!challenge ? (
          <label class="field">
            <span class="field-label">{t("邮箱", "Email")}</span>
            <input
              type="email"
              inputMode="email"
              autoComplete="email"
              autoCapitalize="off"
              spellcheck={false}
              value={email}
              onInput={(e) => setEmail((e.target as HTMLInputElement).value)}
              placeholder="you@example.com"
              disabled={busy}
            />
          </label>
        ) : (
          <>
            <p class="field-hint">
              {t(`验证码已发送到 ${challenge.email}`, `We sent a code to ${challenge.email}`)}
            </p>
            <label class="field">
              <span class="field-label">{t("6 位验证码", "6-digit code")}</span>
              <input
                ref={codeRef}
                class="code-input mono"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]*"
                maxLength={6}
                value={code}
                disabled={busy}
                onInput={(e) => {
                  const digits = (e.target as HTMLInputElement).value.replace(/\D/g, "").slice(0, 6);
                  setCode(digits);
                  // Typing the sixth digit is the user's gesture: submit it.
                  if (digits.length === 6) void verify(digits);
                }}
              />
            </label>
            <div class="login-links">
              <button
                type="button"
                class="text-button"
                onClick={() => {
                  setChallenge(null);
                  setCode("");
                  setError(null);
                }}
                disabled={busy}
              >
                {t("更换邮箱", "Change email")}
              </button>
              <button type="button" class="text-button" disabled={busy || cooldown > 0} onClick={() => void requestCode(challenge.email)}>
                {cooldown > 0 ? t(`重新发送（${cooldown}s）`, `Resend (${cooldown}s)`) : t("重新发送", "Resend")}
              </button>
            </div>
          </>
        )}
        {error ? <ErrorNotice error={error} language={language} onRetry={() => (challenge ? void verify(code) : void requestCode(email.trim()))} /> : null}
      </div>
      <div class="bottom-bar">
        <button type="submit" class="primary-button" disabled={!canSend}>
          {busy ? <span class="spinner small" aria-hidden="true" /> : null}
          {challenge ? t("验证并登录", "Verify and sign in") : t("发送验证码", "Send code")}
        </button>
      </div>
    </form>
  );
}
