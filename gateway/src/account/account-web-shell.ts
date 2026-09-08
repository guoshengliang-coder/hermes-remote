export const ACCOUNT_WEB_SHELL = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>Hermes GO 账号中心</title>
  <link rel="stylesheet" href="/account/assets/account.css">
  <script src="/account/assets/account.js" defer></script>
</head>
<body>
  <header class="topbar">
    <a class="brand" href="/account" aria-label="Hermes GO 账号中心">
      <span class="brand-mark" aria-hidden="true">H</span>
      <span>Hermes GO</span>
    </a>
    <span id="connection-state" class="status">正在建立安全会话…</span>
  </header>
  <main class="page">
    <noscript><p class="notice error">HR-ACCOUNT-004：此账号中心需要启用 JavaScript。 / JavaScript is required.</p></noscript>
    <div id="notice" class="notice" role="status" aria-live="polite" hidden></div>

    <section id="loading-view" class="hero" aria-labelledby="loading-heading">
      <p class="eyebrow">ACCOUNT CENTER</p>
      <h1 id="loading-heading">正在载入账号中心</h1>
      <p id="loading-detail">验证浏览器会话与安全设置…</p>
      <button id="deletion-new-account" type="button" hidden>使用其他邮箱账号 / Use another email account</button>
    </section>

    <section id="signin-view" class="auth-layout" hidden aria-labelledby="signin-heading">
      <div class="hero auth-copy">
        <p class="eyebrow">SECURE SIGN-IN</p>
        <h1 id="signin-heading">连接你的每一台 Hermes</h1>
        <p>一个账号可以管理公司、家中以及共享给你的 Mac。验证码只用于登录，不会接触本地 Hermes 凭据或模型数据。</p>
      </div>
      <div class="panel auth-panel">
        <h2>邮箱登录</h2>
        <p class="muted">Email one-time code</p>
        <form id="email-form">
          <label for="email">邮箱地址</label>
          <input id="email" name="email" type="email" autocomplete="email" maxlength="254" required>
          <button type="submit">发送验证码</button>
        </form>
        <form id="code-form" hidden>
          <label for="code">6 位验证码</label>
          <input id="code" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
          <button type="submit">安全登录</button>
          <button id="change-email" class="button-secondary" type="button">更换邮箱</button>
        </form>
        <div id="google-signin-options" hidden>
          <p class="divider"><span>其他方式</span></p>
          <div id="google-signin" class="google-signin" aria-live="polite"></div>
          <p id="google-status" class="muted small">正在准备 Google 登录… / Preparing Google sign-in…</p>
        </div>
      </div>
    </section>

    <section id="app-view" hidden>
      <div class="dashboard-heading">
        <div>
          <p class="eyebrow">ACCOUNT CENTER</p>
          <h1>账号与设备</h1>
          <p id="account-summary" class="muted"></p>
        </div>
        <button id="signout" class="button-secondary" type="button">退出此浏览器</button>
      </div>

      <section id="invite-banner" class="panel invitation" hidden aria-labelledby="invite-heading">
        <div>
          <p class="eyebrow">DEVICE INVITATION</p>
          <h2 id="invite-heading">接受设备共享邀请</h2>
          <p>这是整台 Hermes 的操作权限，可能包含已有会话、文件与配置元数据，并非单个项目权限。</p>
        </div>
        <label class="check"><input id="invite-ack" type="checkbox"> 我理解并接受整台设备访问范围</label>
        <button id="accept-invite" type="button">接受邀请</button>
      </section>

      <div class="dashboard-grid">
        <section class="panel" aria-labelledby="devices-heading">
          <div class="section-heading">
            <div><h2 id="devices-heading">Hermes 设备</h2><p class="muted">Owned &amp; shared Macs</p></div>
            <button id="refresh" class="button-quiet" type="button">刷新</button>
          </div>
          <div id="devices" class="stack"></div>
        </section>

        <aside class="side-stack">
          <section class="panel" aria-labelledby="identity-heading">
            <div class="section-heading">
              <div><h2 id="identity-heading">登录方式</h2><p class="muted">Sign-in identities</p></div>
              <div class="inline-actions">
                <button id="add-identity" class="button-quiet" type="button">绑定邮箱</button>
                <button id="add-google-identity" class="button-quiet" type="button" hidden>绑定 Google</button>
              </div>
            </div>
            <div id="identities" class="stack compact"></div>
          </section>
          <section class="panel" aria-labelledby="session-heading">
            <h2 id="session-heading">当前浏览器</h2>
            <p id="browser-session" class="muted"></p>
            <p class="security-note">Bearer 凭据只保存在 HttpOnly Cookie 中，页面脚本无法读取。</p>
          </section>
        </aside>
      </div>

      <div class="security-grid">
        <section class="panel" aria-labelledby="installations-heading">
          <div class="section-heading">
            <div><h2 id="installations-heading">登录设备</h2><p class="muted">Account installations &amp; sessions</p></div>
          </div>
          <p class="security-note">撤销只会让该设备上的账号会话失效，不会解除 Mac 的 Connector 绑定或删除 Hermes 数据。</p>
          <div id="installations" class="stack compact"></div>
        </section>
        <section class="panel" aria-labelledby="audit-heading">
          <div class="section-heading">
            <div><h2 id="audit-heading">安全记录</h2><p class="muted">Recent account activity</p></div>
          </div>
          <div id="audit-events" class="stack compact"></div>
        </section>
      </div>

      <section id="account-deletion-section" class="panel danger-panel" hidden aria-labelledby="account-deletion-heading">
        <div>
          <p class="eyebrow danger-text">DANGER ZONE</p>
          <h2 id="account-deletion-heading">永久删除云端账号</h2>
          <p class="muted">立即撤销全部登录、设备绑定与共享权限；云端个人信息会在 30 天后清理。此操作不会删除任何 Mac 上的本地 Hermes 数据，也不能恢复原账号。</p>
        </div>
        <button id="delete-account" class="button-secondary danger" type="button">永久删除云端账号</button>
      </section>
    </section>
  </main>

  <dialog id="share-dialog" aria-labelledby="share-heading">
    <form method="dialog" class="dialog-close"><button class="button-quiet" value="cancel" aria-label="关闭">关闭</button></form>
    <p class="eyebrow">WHOLE-DEVICE ACCESS</p>
    <h2 id="share-heading">共享设备</h2>
    <p id="share-device-name" class="muted"></p>
    <div id="share-list" class="stack compact"></div>
    <hr>
    <form id="share-form">
      <label for="share-email">接收人的邮箱</label>
      <input id="share-email" name="shareEmail" type="email" autocomplete="email" maxlength="254" required>
      <label class="check"><input id="share-ack" type="checkbox" required> 我确认这是整台 Hermes 的访问权限</label>
      <button type="submit">验证身份并发送邀请</button>
    </form>
    <form id="reauth-form" hidden>
      <p>验证码已发送到当前账号邮箱。验证后才会创建邀请。</p>
      <label for="reauth-code">6 位验证码</label>
      <input id="reauth-code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
      <button type="submit">确认并发送</button>
    </form>
    <div id="share-google-reauth" hidden>
      <p class="muted">使用当前账号已绑定的 Google 登录方式确认后发送邀请。</p>
      <div id="share-google-button" class="google-signin" aria-live="polite"></div>
      <p id="share-google-status" class="muted small">正在准备 Google 验证… / Preparing Google verification…</p>
    </div>
  </dialog>

  <dialog id="identity-dialog" aria-labelledby="identity-dialog-heading">
    <form method="dialog" class="dialog-close"><button class="button-quiet" value="cancel" aria-label="关闭">关闭</button></form>
    <p class="eyebrow">SIGN-IN IDENTITY</p>
    <h2 id="identity-dialog-heading">管理登录方式</h2>
    <p id="identity-dialog-copy" class="muted"></p>
    <form id="identity-target-form">
      <label for="identity-email">要绑定的新邮箱</label>
      <input id="identity-email" type="email" autocomplete="email" maxlength="254" required>
      <button type="submit">验证当前身份</button>
    </form>
    <form id="identity-reauth-form" hidden>
      <label for="identity-reauth-code">当前账号邮箱验证码</label>
      <input id="identity-reauth-code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
      <button type="submit">确认是我本人</button>
    </form>
    <form id="identity-link-form" hidden>
      <label for="identity-link-code">新邮箱验证码</label>
      <input id="identity-link-code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
      <button type="submit">完成绑定</button>
    </form>
    <div id="identity-google-form" hidden>
      <p id="identity-google-copy" class="muted"></p>
      <div id="identity-google-button" class="google-signin" aria-live="polite"></div>
      <p id="identity-google-status" class="muted small">正在准备 Google 验证… / Preparing Google verification…</p>
    </div>
  </dialog>

  <dialog id="installation-dialog" aria-labelledby="installation-dialog-heading">
    <form method="dialog" class="dialog-close"><button class="button-quiet" value="cancel" aria-label="关闭">关闭</button></form>
    <p class="eyebrow">SESSION SECURITY</p>
    <h2 id="installation-dialog-heading">撤销设备登录</h2>
    <p id="installation-dialog-copy" class="muted"></p>
    <form id="installation-reauth-form">
      <label for="installation-reauth-code">当前账号邮箱验证码</label>
      <input id="installation-reauth-code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
      <button type="submit">验证并撤销登录</button>
    </form>
    <div id="installation-google-reauth" hidden>
      <p class="muted">使用当前账号已绑定的 Google 登录方式确认撤销。</p>
      <div id="installation-google-button" class="google-signin" aria-live="polite"></div>
      <p id="installation-google-status" class="muted small">正在准备 Google 验证… / Preparing Google verification…</p>
    </div>
  </dialog>

  <dialog id="account-deletion-dialog" aria-labelledby="account-deletion-dialog-heading">
    <form method="dialog" class="dialog-close"><button class="button-quiet" value="cancel" aria-label="关闭">关闭</button></form>
    <p class="eyebrow danger-text">PERMANENT CLOUD DELETION</p>
    <h2 id="account-deletion-dialog-heading">确认永久删除云端账号</h2>
    <p class="muted">提交后会立即退出所有设备、撤销 Connector 和共享权限。30 天后删除云端身份与账号资料；Mac 上的 Hermes 会保留在本机。</p>
    <form id="account-deletion-confirm-form">
      <label for="account-deletion-confirmation">请输入 DELETE 继续</label>
      <input id="account-deletion-confirmation" autocomplete="off" spellcheck="false" required>
      <label class="check" for="account-deletion-acknowledgement">
        <input id="account-deletion-acknowledgement" type="checkbox" required>
        <span>我理解这是永久操作，无法撤销。</span>
      </label>
      <button class="danger-action" type="submit">继续并验证身份</button>
    </form>
    <form id="account-deletion-reauth-form" hidden>
      <label for="account-deletion-code">当前账号邮箱验证码</label>
      <input id="account-deletion-code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
      <button class="danger-action" type="submit">永久删除云端账号</button>
    </form>
    <div id="account-deletion-google-reauth" hidden>
      <p class="muted">使用当前账号已绑定的 Google 登录方式完成最后确认。</p>
      <div id="account-deletion-google-button" class="google-signin" aria-live="polite"></div>
      <p id="account-deletion-google-status" class="muted small">正在准备 Google 验证… / Preparing Google verification…</p>
    </div>
  </dialog>
</body>
</html>`;

export const ACCOUNT_WEB_APP_CSS = `:root {
  color-scheme: light dark;
  font-family: Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  --bg: #f4f4ef;
  --surface: rgba(255, 255, 252, .86);
  --text: #19201c;
  --muted: #66706a;
  --line: rgba(25, 32, 28, .13);
  --accent: #176b4d;
  --accent-hover: #0e573d;
  --danger: #a43131;
  --shadow: 0 22px 70px rgba(27, 38, 31, .09);
}
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; color: var(--text); background: radial-gradient(circle at 85% 8%, rgba(90, 174, 129, .16), transparent 32rem), var(--bg); }
button, input { font: inherit; }
button { border: 0; border-radius: 999px; padding: .72rem 1.15rem; color: white; background: var(--accent); cursor: pointer; font-weight: 650; }
button:hover { background: var(--accent-hover); }
button:focus-visible, input:focus-visible { outline: 3px solid rgba(23, 107, 77, .3); outline-offset: 2px; }
button:disabled { cursor: wait; opacity: .55; }
input { width: 100%; margin: .45rem 0 1rem; padding: .78rem .9rem; border: 1px solid var(--line); border-radius: 12px; background: rgba(255,255,255,.66); color: var(--text); }
label { display: block; font-size: .91rem; font-weight: 650; }
h1 { margin: .25rem 0 .65rem; font-size: clamp(2rem, 5vw, 4.6rem); line-height: .98; letter-spacing: -.045em; }
h2 { margin: 0; font-size: 1.22rem; }
p { line-height: 1.55; }
.topbar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; max-width: 1180px; margin: auto; padding: 1.3rem 1.5rem; }
.brand { display: inline-flex; align-items: center; gap: .7rem; color: inherit; text-decoration: none; font-weight: 760; letter-spacing: -.02em; }
.brand-mark { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: 9px; background: var(--text); color: var(--bg); }
.status { color: var(--muted); font-size: .85rem; }
.page { max-width: 1180px; margin: 0 auto; padding: 3.5rem 1.5rem 5rem; }
.hero { max-width: 720px; padding-top: 5vh; }
.hero > p:not(.eyebrow) { max-width: 620px; color: var(--muted); font-size: 1.08rem; }
.eyebrow { margin: 0 0 .7rem; color: var(--accent); font-size: .73rem; font-weight: 800; letter-spacing: .15em; }
.auth-layout { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(280px, .65fr); gap: clamp(2rem, 8vw, 8rem); align-items: center; min-height: 68vh; }
.auth-copy { padding-top: 0; }
.panel { padding: 1.4rem; border: 1px solid var(--line); border-radius: 22px; background: var(--surface); box-shadow: var(--shadow); backdrop-filter: blur(18px); }
.auth-panel { max-width: 420px; width: 100%; justify-self: end; }
.muted { margin: .3rem 0 1rem; color: var(--muted); }
.small { font-size: .82rem; }
.divider { display: flex; align-items: center; gap: .8rem; color: var(--muted); font-size: .75rem; }
.divider::before, .divider::after { content: ""; height: 1px; flex: 1; background: var(--line); }
.google-signin { display: flex; justify-content: center; min-height: 44px; }
.google-signin[aria-busy="true"] { opacity: .55; pointer-events: none; }
.button-secondary, .button-quiet { color: var(--text); background: transparent; border: 1px solid var(--line); }
.button-secondary:hover, .button-quiet:hover { background: rgba(25,32,28,.06); }
.button-quiet { padding: .45rem .75rem; font-size: .84rem; }
.dashboard-heading, .section-heading { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.dashboard-heading { margin-bottom: 2rem; }
.dashboard-heading h1 { font-size: clamp(2.2rem, 5vw, 4rem); }
.dashboard-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(260px, .75fr); gap: 1.2rem; align-items: start; }
.side-stack, .stack { display: grid; gap: .8rem; }
.side-stack { gap: 1.2rem; }
.security-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1.2rem; margin-top: 1.2rem; align-items: start; }
.stack:empty::after { content: "暂无内容 / Nothing here yet"; color: var(--muted); padding: 1rem 0; }
.compact { gap: .5rem; }
.device, .row { padding: 1rem; border: 1px solid var(--line); border-radius: 15px; background: rgba(255,255,255,.35); }
.device-head, .row { display: flex; align-items: center; justify-content: space-between; gap: .8rem; }
.device-title { font-weight: 760; }
.meta { color: var(--muted); font-size: .82rem; }
.badge { display: inline-flex; margin: .35rem .35rem 0 0; padding: .2rem .52rem; border-radius: 999px; color: var(--accent); background: rgba(23,107,77,.1); font-size: .72rem; font-weight: 700; }
.actions { display: flex; flex-wrap: wrap; gap: .45rem; margin-top: .8rem; }
.inline-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: .45rem; }
.danger { color: var(--danger); border-color: rgba(164,49,49,.25); }
.danger-text { color: var(--danger); }
.danger-panel { display: flex; justify-content: space-between; align-items: center; gap: 1.5rem; margin-top: 1.2rem; border-color: rgba(164,49,49,.3); }
.danger-panel p { margin-bottom: 0; max-width: 760px; }
.danger-action { background: var(--danger); }
.danger-action:hover { background: #842525; }
.notice { position: sticky; top: 1rem; z-index: 5; margin: 0 auto 1rem; max-width: 680px; padding: .85rem 1rem; border-radius: 14px; background: #173f31; color: white; box-shadow: var(--shadow); }
.notice.error { background: #7e2929; }
.invitation { display: grid; grid-template-columns: 1fr auto auto; gap: 1rem; align-items: center; margin-bottom: 1.2rem; border-color: rgba(23,107,77,.32); }
.check { display: flex; gap: .6rem; align-items: flex-start; font-weight: 500; line-height: 1.4; }
.check input { width: auto; margin: .2rem 0 0; }
.security-note { padding: .8rem; border-radius: 12px; color: var(--muted); background: rgba(23,107,77,.07); font-size: .8rem; }
dialog { width: min(620px, calc(100vw - 2rem)); max-height: calc(100vh - 2rem); overflow: auto; padding: 1.5rem; border: 1px solid var(--line); border-radius: 22px; color: var(--text); background: var(--bg); box-shadow: 0 40px 120px rgba(0,0,0,.28); }
dialog::backdrop { background: rgba(12,18,15,.58); backdrop-filter: blur(4px); }
.dialog-close { float: right; }
hr { margin: 1.4rem 0; border: 0; border-top: 1px solid var(--line); }
[hidden] { display: none !important; }
@media (max-width: 760px) {
  .page { padding-top: 1.5rem; }
  .auth-layout, .dashboard-grid, .security-grid { grid-template-columns: 1fr; }
  .auth-panel { justify-self: stretch; max-width: none; }
  .dashboard-heading { align-items: flex-start; }
  .invitation { grid-template-columns: 1fr; }
  .danger-panel { align-items: flex-start; flex-direction: column; }
  .status { max-width: 42vw; text-align: right; }
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #111713; --surface: rgba(24,32,27,.88); --text: #edf3ee; --muted: #a1aea6; --line: rgba(237,243,238,.13); --accent: #6dd4a3; --accent-hover: #45ba85; --danger: #ff9696; }
  button { color: #07120d; }
  input, .device, .row { background: rgba(255,255,255,.035); }
  .brand-mark { background: var(--text); color: var(--bg); }
}`;

export const ACCOUNT_WEB_APP_JS = `'use strict';
(function () {
  var state = { csrf: '', account: null, deviceId: '', challengeId: '', email: '', shareEmail: '', reauthChallengeId: '', invitationToken: '', reauthEmail: '', identityAction: '', identityId: '', identityEmail: '', identityGrant: '', identityReauthChallengeId: '', identityLinkChallengeId: '', identityTargetProvider: '', hasGoogleIdentity: false, installationId: '', installationChallengeId: '', accountDeletionEnabled: false, accountDeletionChallengeId: '', googleClientId: '', googleNonce: '', googleLoading: false, googleReady: false, googleAction: '', googleTargetId: '' };
  var byId = function (id) { return document.getElementById(id); };
  var noticeTimer;
  function show(id) { byId(id).hidden = false; }
  function hide(id) { byId(id).hidden = true; }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
  function textNode(tag, className, value) { var node = document.createElement(tag); if (className) node.className = className; node.textContent = value; return node; }
  function button(label, className, action) { var node = textNode('button', className || 'button-quiet', label); node.type = 'button'; node.addEventListener('click', action); return node; }
  function setBusy(form, busy) { Array.prototype.forEach.call(form.elements, function (node) { node.disabled = busy; }); }
  function flash(message, error) { var node = byId('notice'); window.clearTimeout(noticeTimer); node.textContent = message; node.className = error ? 'notice error' : 'notice'; node.hidden = false; noticeTimer = window.setTimeout(function () { node.hidden = true; }, 7000); }
  var messages = {
    'HR-AUTH-002': '无法验证 Google 登录，请重新登录。 / Could not verify Google sign-in. Try again.',
    'HR-AUTH-003': '登录已过期，请重新登录。 / Session expired. Sign in again.',
    'HR-AUTH-004': '此浏览器会话已撤销，请重新登录。 / This browser session was revoked.',
    'HR-AUTH-006': '需要再次验证当前账号。 / Verify your identity again.',
    'HR-AUTH-007': '请求过于频繁，请稍后重试。 / Too many requests. Try again later.',
    'HR-AUTH-009': '验证码无效或已过期。 / The code is invalid or expired.',
    'HR-AUTH-010': '验证邮件暂时无法发送。 / The email could not be sent.',
    'HR-AUTH-012': '浏览器安全校验失败，请刷新页面。 / Browser security check failed.',
    'HR-AUTH-013': 'Google 登录暂不可用，请使用邮箱或刷新页面重试。 / Google sign-in is unavailable. Use email or reload.',
    'HR-ACCOUNT-002': '账号服务暂时不可用。 / Account service is unavailable.',
    'HR-ACCOUNT-012': '此 Hermes GO 账号正在永久删除，已无法再次登录。 / This Hermes GO account is being permanently deleted and can no longer sign in.',
    'HR-ACCOUNT-006': '该内容不存在或无权访问。 / The resource was not found.',
    'HR-ACCOUNT-008': '该登录方式已属于另一个 Hermes GO 账号。 / This identity belongs to another Hermes GO account.',
    'HR-ACCOUNT-011': '账号必须至少保留一种登录方式。 / Keep at least one sign-in identity.',
    'HR-SHARE-001': '此 Relay 尚未启用设备共享。 / Device sharing is not enabled.',
    'HR-SHARE-002': '此 Mac 已达到五个共享账号的上限。 / This Mac has reached its sharing limit.',
    'HR-SHARE-003': '此账号已达到十台共享 Mac 的上限。 / This account has reached its shared-device limit.',
    'HR-SHARE-004': '邀请无效、已过期或已取消。 / The invitation is invalid, expired, or cancelled.',
    'HR-SHARE-005': '邀请邮箱与当前账号不匹配。 / The invitation email does not match.',
    'HR-SHARE-006': '请先确认整台设备的访问范围。 / Confirm the whole-device access scope.',
    'HR-SHARE-007': '共享状态冲突，请刷新后重试。 / Sharing state changed. Refresh and retry.',
    'HR-SHARE-008': '邀请已保留，但邮件暂时无法送达。 / The invitation was saved but email delivery failed.'
  };
  function uiError(error) { var code = error && error.code ? error.code : 'HR-ACCOUNT-002'; var message = messages[code] || '操作未完成。 / The operation could not be completed.'; return message + '（' + code + '）'; }
  function uuid() { return crypto.randomUUID(); }
  async function rawRequest(path, options) {
    options = options || {};
    var headers = { 'Accept': 'application/json' };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (options.mutation) { headers['X-Hermes-CSRF'] = state.csrf; headers['Idempotency-Key'] = options.idempotencyKey || uuid(); }
    var response = await fetch(path, { method: options.method || 'GET', credentials: 'same-origin', headers: headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
    var payload = null;
    if (response.status !== 204) { try { payload = await response.json(); } catch (_) { payload = null; } }
    if (!response.ok) { var failure = new Error('request_failed'); failure.status = response.status; failure.code = payload && payload.error && payload.error.code ? payload.error.code : 'HR-ACCOUNT-002'; throw failure; }
    return payload;
  }
  async function request(path, options, allowRefresh) {
    try { return await rawRequest(path, options); }
    catch (error) {
      if (allowRefresh !== false && error.status === 401 && state.account) {
        try { var refreshed = await rawRequest('/v2/web/auth/refresh', { method: 'POST', mutation: true }); if (refreshed && refreshed.csrfToken) state.csrf = refreshed.csrfToken; return await rawRequest(path, options); }
        catch (_) { state.account = null; showSignIn(); }
      }
      throw error;
    }
  }
  function showSignIn() { hide('loading-view'); hide('app-view'); hide('deletion-new-account'); show('signin-view'); byId('connection-state').textContent = '安全会话已就绪 / Secure session ready'; if (state.googleClientId) prepareGoogleButton('google-signin', 'sign_in'); }
  function showApp() { hide('loading-view'); hide('signin-view'); show('app-view'); byId('connection-state').textContent = '已加密连接 / Securely connected'; }
  function showDeletionPending() { hide('signin-view'); hide('app-view'); show('loading-view'); show('deletion-new-account'); byId('loading-heading').textContent = '云端账号删除已提交 / Cloud account deletion submitted'; byId('loading-detail').textContent = '云端访问已立即停止，个人数据将在 30 天期限后清理。Mac 上的 Hermes 数据仍保留在本机。 / Cloud access stopped immediately. Personal data will be cleaned after the 30-day period. Hermes data on your Macs remains local.'; byId('connection-state').textContent = '删除已提交 / Deletion submitted'; }
  async function bootstrap() {
    try {
      var result = await rawRequest('/v2/web/session'); state.csrf = result.csrfToken; state.googleClientId = result.authentication && result.authentication.google ? result.authentication.google.clientId : ''; state.accountDeletionEnabled = Boolean(result.features && result.features.accountDeletion); parseInvitation();
      byId('google-signin-options').hidden = !state.googleClientId; byId('add-google-identity').hidden = !state.googleClientId;
      byId('account-deletion-section').hidden = !state.accountDeletionEnabled;
      if (result.session && result.session.authenticated) { state.account = result.session.account; showApp(); await loadDashboard(); } else if (result.session && result.session.accountDeletionPending) { showDeletionPending(); } else { showSignIn(); }
    } catch (error) { hide('loading-view'); byId('connection-state').textContent = '服务不可用 / Unavailable'; flash(uiError(error), true); }
  }
  function prepareGoogleButton(targetId, action) {
    var statusId = googleStatusId(targetId);
    var status = byId(statusId); state.googleTargetId = targetId; state.googleAction = action; show(statusId);
    if (!state.googleClientId) { status.textContent = '此 Relay 尚未配置 Google Web 登录，请使用邮箱。 / Google Web sign-in is not configured; use email.'; return; }
    if (state.googleReady) { renderGoogleButton(); return; }
    if (state.googleLoading) return;
    state.googleLoading = true;
    try {
      var script = document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client'; script.async = true; script.defer = true; script.referrerPolicy = 'no-referrer';
      script.addEventListener('load', initializeGoogleIdentity);
      script.addEventListener('error', googleSignInUnavailable);
      document.head.appendChild(script);
    } catch (_) { googleSignInUnavailable(); }
  }
  function initializeGoogleIdentity() {
    state.googleLoading = false;
    if (!window.google || !window.google.accounts || !window.google.accounts.id) { googleSignInUnavailable(); return; }
    try {
      state.googleNonce = uuid();
      window.google.accounts.id.initialize({ client_id: state.googleClientId, callback: exchangeGoogleCredential, nonce: state.googleNonce, ux_mode: 'popup', use_fedcm_for_button: true });
      state.googleReady = true; renderGoogleButton();
    } catch (_) { googleSignInUnavailable(); }
  }
  function renderGoogleButton() {
    if (!state.googleTargetId || !state.googleAction) return;
    var target = byId(state.googleTargetId); clear(target);
    try {
      window.google.accounts.id.renderButton(target, { theme: 'outline', size: 'large', shape: 'pill', text: state.googleAction === 'sign_in' ? 'signin_with' : 'continue_with', width: 320 });
      hide(googleStatusId(state.googleTargetId));
    } catch (_) { googleSignInUnavailable(); }
  }
  function googleSignInUnavailable() {
    state.googleLoading = false; state.googleReady = false;
    var statusId = googleStatusId(state.googleTargetId);
    byId(statusId).textContent = messages['HR-AUTH-013'] + '（HR-AUTH-013）'; show(statusId);
  }
  function googleStatusId(targetId) {
    if (targetId === 'identity-google-button') return 'identity-google-status';
    if (targetId === 'share-google-button') return 'share-google-status';
    if (targetId === 'installation-google-button') return 'installation-google-status';
    if (targetId === 'account-deletion-google-button') return 'account-deletion-google-status';
    return 'google-status';
  }
  async function exchangeGoogleCredential(response) {
    var target = byId(state.googleTargetId || 'google-signin'); target.setAttribute('aria-busy', 'true');
    try {
      if (!response || typeof response.credential !== 'string' || !response.credential) throw { code: 'HR-AUTH-002' };
      if (state.googleAction === 'sign_in') {
        var result = await rawRequest('/v2/web/auth/google/exchange', { method: 'POST', mutation: true, body: { idToken: response.credential, nonce: state.googleNonce, displayName: navigator.platform || 'Web browser' } });
        state.account = result.account; showApp(); await loadDashboard(); return;
      }
      if (state.googleAction === 'reauth_identity') {
        var verified = await request('/v2/web/auth/reauth/google', { method: 'POST', mutation: true, body: { idToken: response.credential, nonce: state.googleNonce, scope: state.identityAction === 'unlink' ? 'account.identity.unlink' : 'account.identity.link' } });
        state.identityGrant = verified.grant; await continueIdentityAfterReauthentication(); return;
      }
      if (state.googleAction === 'link_identity') {
        await request('/v2/web/identities/google', { method: 'POST', mutation: true, body: { idToken: response.credential, nonce: state.googleNonce, grant: state.identityGrant } });
        state.identityGrant = ''; byId('identity-dialog').close(); flash('Google 登录方式已绑定。 / Google identity linked.', false); await loadIdentities(); return;
      }
      if (state.googleAction === 'reauth_share') {
        var shareVerification = await request('/v2/web/auth/reauth/google', { method: 'POST', mutation: true, body: { idToken: response.credential, nonce: state.googleNonce, scope: 'device.share' } });
        await completeShareInvitation(shareVerification.grant); return;
      }
      if (state.googleAction === 'reauth_installation') {
        var installationVerification = await request('/v2/web/auth/reauth/google', { method: 'POST', mutation: true, body: { idToken: response.credential, nonce: state.googleNonce, scope: 'account.installation.revoke' } });
        await completeInstallationRevoke(installationVerification.grant); return;
      }
      if (state.googleAction === 'reauth_account_delete') {
        var deletionVerification = await request('/v2/web/auth/reauth/google', { method: 'POST', mutation: true, body: { idToken: response.credential, nonce: state.googleNonce, scope: 'account.delete' } });
        await completeAccountDeletion(deletionVerification.grant); return;
      }
      throw { code: 'HR-AUTH-002' };
    } catch (error) { if (state.googleAction !== 'sign_in') state.identityGrant = ''; if (state.googleAction === 'link_identity') byId('identity-dialog').close(); flash(uiError(error), true); }
    finally { target.removeAttribute('aria-busy'); }
  }
  function parseInvitation() { var match = /^#share-invitation=(hsi_[A-Za-z0-9_-]{43})$/.exec(location.hash); state.invitationToken = match ? match[1] : ''; }
  async function loadDashboard() {
    try {
      var account = await request('/v2/web/account'); state.account = account.account;
      byId('account-summary').textContent = account.account.displayName || account.account.email || account.account.id;
      byId('browser-session').textContent = account.installation.displayName + ' · ' + account.installation.platform;
      await Promise.all([loadIdentities(), loadDevices(), loadInstallations(), loadAuditEvents()]); byId('invite-banner').hidden = !state.invitationToken;
    } catch (error) { flash(uiError(error), true); }
  }
  async function loadIdentities() {
    var target = byId('identities'); clear(target);
    try {
      var data = await request('/v2/web/identities'); state.reauthEmail = ''; state.hasGoogleIdentity = false;
      data.items.forEach(function (identity) { if (!state.reauthEmail && identity.provider === 'email_otp' && identity.email) state.reauthEmail = identity.email; });
      data.items.forEach(function (identity) {
        if (identity.provider === 'google') state.hasGoogleIdentity = true;
        var row = document.createElement('div'); row.className = 'row';
        var label = document.createElement('span'); label.appendChild(textNode('span', '', identity.provider === 'google' ? 'Google' : '邮箱验证码')); label.appendChild(textNode('span', 'meta', identity.email || '已验证')); row.appendChild(label);
        if (data.items.length > 1) row.appendChild(button('解绑', 'button-quiet danger', function () { beginIdentityUnlink(identity); }));
        target.appendChild(row);
      });
      byId('add-identity').disabled = !(state.reauthEmail || state.hasGoogleIdentity);
      byId('add-google-identity').disabled = !state.googleClientId || !(state.reauthEmail || state.hasGoogleIdentity);
    }
    catch (error) { target.appendChild(textNode('p', 'meta', uiError(error))); }
  }
  async function loadDevices() {
    var target = byId('devices'); clear(target);
    try { var data = await request('/v2/web/devices'); data.items.forEach(function (device) { target.appendChild(deviceCard(device)); }); }
    catch (error) { target.appendChild(textNode('p', 'meta', uiError(error))); }
  }
  async function loadInstallations() {
    var target = byId('installations'); clear(target);
    try {
      var data = await request('/v2/web/installations');
      data.items.forEach(function (installation) {
        var row = document.createElement('div'); row.className = 'row';
        var label = document.createElement('span');
        label.appendChild(textNode('span', '', installation.displayName));
        label.appendChild(textNode('span', 'meta', installation.kind + ' · ' + installation.platform + ' · ' + installation.activeSessionCount + ' 个会话'));
        row.appendChild(label);
        if (installation.current) row.appendChild(textNode('span', 'badge', '当前'));
        else row.appendChild(button('撤销登录', 'button-quiet danger', function () { beginInstallationRevoke(installation); }));
        target.appendChild(row);
      });
    } catch (error) { target.appendChild(textNode('p', 'meta', uiError(error))); }
  }
  async function loadAuditEvents() {
    var target = byId('audit-events'); clear(target);
    try {
      var data = await request('/v2/web/audit-events');
      data.items.forEach(function (event) {
        var row = document.createElement('div'); row.className = 'row';
        var label = document.createElement('span');
        label.appendChild(textNode('span', '', auditLabel(event.eventType)));
        label.appendChild(textNode('span', 'meta', new Date(event.occurredAt).toLocaleString() + (event.actorInstallation ? ' · ' + event.actorInstallation.displayName : '')));
        row.appendChild(label); target.appendChild(row);
      });
    } catch (error) { target.appendChild(textNode('p', 'meta', uiError(error))); }
  }
  function auditLabel(eventType) {
    var labels = {
      'auth.session.created': '新设备登录 / New sign-in',
      'auth.session.refreshed': '会话已续期 / Session refreshed',
      'auth.session.revoked': '会话已退出 / Session signed out',
      'auth.reauthenticated': '敏感操作验证 / Identity verified',
      'account.identity.linked': '登录方式已绑定 / Identity linked',
      'account.identity.unlinked': '登录方式已解绑 / Identity unlinked',
      'account.installation.revoked': '设备登录已撤销 / Device sign-in revoked'
    };
    return labels[eventType] || ('账号安全事件 / Account security event · ' + eventType);
  }
  async function beginInstallationRevoke(installation) {
    if (!state.reauthEmail && !state.hasGoogleIdentity) { flash(messages['HR-AUTH-006'] + '（HR-AUTH-006）', true); return; }
    if (!confirm('确认撤销“' + installation.displayName + '”上的全部账号会话？这不会断开 Hermes Connector。')) return;
    state.installationId = installation.id; state.installationChallengeId = '';
    byId('installation-reauth-code').value = ''; hide('installation-google-reauth'); show('installation-reauth-form');
    if (!state.reauthEmail) {
      hide('installation-reauth-form'); show('installation-google-reauth');
      byId('installation-dialog-copy').textContent = '通过 Google 确认后，该设备需要重新登录；Mac 上的 Hermes Connector 保持连接。';
      byId('installation-dialog').showModal(); prepareGoogleButton('installation-google-button', 'reauth_installation'); return;
    }
    try {
      var result = await request('/v2/web/auth/reauth/email/challenges', { method: 'POST', mutation: true, body: { email: state.reauthEmail } });
      state.installationChallengeId = result.challenge.challengeId;
      byId('installation-dialog-copy').textContent = '验证码已发送。验证后，该设备需要重新登录；Mac 上的 Hermes Connector 保持连接。';
      byId('installation-dialog').showModal(); byId('installation-reauth-code').focus();
    } catch (error) { flash(uiError(error), true); }
  }
  function deviceCard(device) {
    var card = document.createElement('article'); card.className = 'device';
    var head = document.createElement('div'); head.className = 'device-head'; var title = document.createElement('div');
    title.appendChild(textNode('div', 'device-title', device.desktopDisplayName)); title.appendChild(textNode('div', 'meta', device.deviceId)); head.appendChild(title); head.appendChild(textNode('span', 'badge', device.connector.online ? '在线' : '离线')); card.appendChild(head);
    var badges = document.createElement('div'); badges.appendChild(textNode('span', 'badge', device.access === 'owner' ? '我的设备' : '共享给我')); if (device.isDefault) badges.appendChild(textNode('span', 'badge', '默认')); if (device.hermes && device.hermes.version) badges.appendChild(textNode('span', 'badge', 'Hermes ' + device.hermes.version)); card.appendChild(badges);
    var actions = document.createElement('div'); actions.className = 'actions';
    if (!device.isDefault) actions.appendChild(button('设为默认', 'button-quiet', function () { selectDefault(device.deviceId); }));
    if (device.access === 'owner') actions.appendChild(button('管理共享', 'button-quiet', function () { openShares(device); }));
    if (device.access === 'operator') actions.appendChild(button('退出共享', 'button-quiet danger', function () { leaveDevice(device); }));
    card.appendChild(actions); return card;
  }
  async function selectDefault(deviceId) { try { await request('/v2/web/devices/' + encodeURIComponent(deviceId) + '/select-default', { method: 'POST', mutation: true }); flash('默认设备已更新。 / Default device updated.', false); await loadDevices(); } catch (error) { flash(uiError(error), true); } }
  async function leaveDevice(device) { if (!confirm('确认退出“' + device.desktopDisplayName + '”的整机共享？')) return; try { await request('/v2/web/devices/' + encodeURIComponent(device.deviceId) + '/leave', { method: 'POST', mutation: true }); flash('已退出设备共享。 / Shared-device access removed.', false); await loadDevices(); } catch (error) { flash(uiError(error), true); } }
  async function openShares(device) { state.deviceId = device.deviceId; byId('share-device-name').textContent = device.desktopDisplayName; show('share-form'); hide('reauth-form'); hide('share-google-reauth'); byId('share-dialog').showModal(); await loadShares(); }
  async function loadShares() {
    var target = byId('share-list'); clear(target);
    try {
      var data = await request('/v2/web/devices/' + encodeURIComponent(state.deviceId) + '/shares');
      data.invitations.forEach(function (invite) { target.appendChild(managementRow('待接受 · ' + invite.targetEmailHint, '取消邀请', function () { return cancelInvitation(invite.id); })); });
      data.grants.forEach(function (grant) { target.appendChild(managementRow('可操作 · ' + grant.granteeEmailHint, '撤销权限', function () { return revokeGrant(grant.id); })); });
    } catch (error) { target.appendChild(textNode('p', 'meta', uiError(error))); }
  }
  function managementRow(label, actionLabel, action) { var row = document.createElement('div'); row.className = 'row'; row.appendChild(textNode('span', '', label)); row.appendChild(button(actionLabel, 'button-quiet danger', action)); return row; }
  async function cancelInvitation(invitationId) { try { await request('/v2/web/devices/' + encodeURIComponent(state.deviceId) + '/share-invitations/' + invitationId, { method: 'DELETE', mutation: true }); flash('邀请已取消。 / Invitation cancelled.', false); await loadShares(); } catch (error) { flash(uiError(error), true); } }
  async function revokeGrant(grantId) { if (!confirm('确认立即撤销此用户的整机访问权限？')) return; try { await request('/v2/web/devices/' + encodeURIComponent(state.deviceId) + '/shares/' + grantId, { method: 'DELETE', mutation: true }); flash('访问权限已撤销。 / Device access revoked.', false); await loadShares(); } catch (error) { flash(uiError(error), true); } }
  byId('email-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; state.email = byId('email').value.trim(); setBusy(form, true);
    try { var result = await rawRequest('/v2/web/auth/email/challenges', { method: 'POST', mutation: true, body: { email: state.email } }); state.challengeId = result.challenge.challengeId; hide('email-form'); show('code-form'); byId('code').focus(); flash('验证码已发送。 / Verification code sent.', false); }
    catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('code-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; setBusy(form, true);
    try { var result = await rawRequest('/v2/web/auth/email/exchange', { method: 'POST', mutation: true, body: { challengeId: state.challengeId, email: state.email, code: byId('code').value, displayName: navigator.platform || 'Web browser' } }); state.account = result.account; showApp(); await loadDashboard(); }
    catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('change-email').addEventListener('click', function () { state.challengeId = ''; byId('code').value = ''; hide('code-form'); show('email-form'); });
  byId('share-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; state.shareEmail = byId('share-email').value.trim(); setBusy(form, true);
    try {
      if (!state.reauthEmail) {
        if (!state.hasGoogleIdentity) throw { code: 'HR-AUTH-006' };
        form.hidden = true; show('share-google-reauth'); prepareGoogleButton('share-google-button', 'reauth_share'); return;
      }
      var result = await request('/v2/web/auth/reauth/email/challenges', { method: 'POST', mutation: true, body: { email: state.reauthEmail } }); state.reauthChallengeId = result.challenge.challengeId; form.hidden = true; show('reauth-form'); byId('reauth-code').focus(); flash('身份验证码已发送。 / Confirmation code sent.', false);
    }
    catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('reauth-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; setBusy(form, true);
    try {
      var verified = await request('/v2/web/auth/reauth/email', { method: 'POST', mutation: true, body: { challengeId: state.reauthChallengeId, email: state.reauthEmail, code: byId('reauth-code').value, scope: 'device.share' } });
      await completeShareInvitation(verified.grant);
    } catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  async function completeShareInvitation(grant) {
    await request('/v2/web/devices/' + encodeURIComponent(state.deviceId) + '/share-invitations', { method: 'POST', mutation: true, body: { email: state.shareEmail, grant: grant, acknowledgedWholeDeviceAccess: true } });
    state.shareEmail = ''; byId('share-email').value = ''; byId('reauth-code').value = ''; clear(byId('share-google-button')); hide('reauth-form'); hide('share-google-reauth'); show('share-form');
    if (state.googleTargetId === 'share-google-button') { state.googleAction = ''; state.googleTargetId = ''; }
    flash('共享邀请已发送。 / Device invitation sent.', false); await loadShares();
  }
  byId('accept-invite').addEventListener('click', async function () {
    if (!byId('invite-ack').checked) { flash('请先确认整台设备的访问范围。 / Confirm the whole-device access scope first.', true); return; }
    try { await request('/v2/web/share-invitations/' + state.invitationToken + '/accept', { method: 'POST', mutation: true, body: { acknowledgedWholeDeviceAccess: true } }); history.replaceState(null, '', '/account'); state.invitationToken = ''; hide('invite-banner'); flash('设备已添加。 / Shared device added.', false); await loadDevices(); }
    catch (error) { flash(uiError(error), true); }
  });
  function resetIdentityDialog() {
    state.identityAction = ''; state.identityId = ''; state.identityEmail = ''; state.identityGrant = ''; state.identityReauthChallengeId = ''; state.identityLinkChallengeId = ''; state.identityTargetProvider = ''; state.googleAction = ''; state.googleTargetId = '';
    byId('identity-email').value = ''; byId('identity-reauth-code').value = ''; byId('identity-link-code').value = '';
    clear(byId('identity-google-button')); show('identity-target-form'); hide('identity-reauth-form'); hide('identity-link-form'); hide('identity-google-form');
  }
  async function requestIdentityReauth() {
    hide('identity-target-form');
    if (state.reauthEmail) {
      var result = await request('/v2/web/auth/reauth/email/challenges', { method: 'POST', mutation: true, body: { email: state.reauthEmail } });
      state.identityReauthChallengeId = result.challenge.challengeId;
      show('identity-reauth-form'); byId('identity-reauth-code').focus();
      flash('当前身份验证码已发送。 / Current identity code sent.', false); return true;
    }
    if (state.hasGoogleIdentity) {
      byId('identity-google-copy').textContent = '先使用当前账号已绑定的 Google 登录方式确认是你本人。';
      show('identity-google-form'); prepareGoogleButton('identity-google-button', 'reauth_identity'); return true;
    }
    flash(messages['HR-AUTH-006'] + '（HR-AUTH-006）', true); return false;
  }
  async function continueIdentityAfterReauthentication() {
    if (state.identityAction === 'unlink') {
      var removed = await request('/v2/web/identities/' + state.identityId, { method: 'DELETE', mutation: true, body: { grant: state.identityGrant } });
      state.identityGrant = ''; byId('identity-dialog').close();
      if (removed.currentSessionRevoked) { state.account = null; location.replace('/account'); return; }
      flash('登录方式已解绑，相关会话已撤销。 / Identity and related sessions removed.', false); await loadIdentities(); return;
    }
    if (state.identityTargetProvider === 'google') {
      hide('identity-reauth-form'); show('identity-google-form');
      byId('identity-google-copy').textContent = '现在选择要绑定到此 Hermes GO 账号的 Google 账号。';
      prepareGoogleButton('identity-google-button', 'link_identity'); return;
    }
    var challenge = await request('/v2/web/identities/email/challenges', { method: 'POST', mutation: true, body: { email: state.identityEmail } });
    state.identityLinkChallengeId = challenge.challenge.challengeId; hide('identity-reauth-form'); hide('identity-google-form'); show('identity-link-form'); byId('identity-link-code').focus(); flash('新邮箱验证码已发送。 / New email code sent.', false);
  }
  async function beginIdentityUnlink(identity) {
    if (!confirm('确认解绑“' + (identity.email || identity.provider) + '”？由它创建的登录会话会被撤销。')) return;
    resetIdentityDialog(); state.identityAction = 'unlink'; state.identityId = identity.id;
    byId('identity-dialog-copy').textContent = '解绑需要重新验证当前账号，并且账号必须保留至少一种登录方式。';
    byId('identity-dialog').showModal();
    try { await requestIdentityReauth(); } catch (error) { flash(uiError(error), true); }
  }
  byId('add-identity').addEventListener('click', function () {
    resetIdentityDialog(); state.identityAction = 'link'; state.identityTargetProvider = 'email_otp'; byId('identity-dialog-copy').textContent = '先验证当前账号，再分别验证要绑定的新邮箱。'; byId('identity-dialog').showModal(); byId('identity-email').focus();
  });
  byId('add-google-identity').addEventListener('click', async function () {
    resetIdentityDialog(); state.identityAction = 'link'; state.identityTargetProvider = 'google'; hide('identity-target-form'); byId('identity-dialog-copy').textContent = '先验证当前账号，再选择要绑定的新 Google 账号。'; byId('identity-dialog').showModal();
    try { await requestIdentityReauth(); } catch (error) { flash(uiError(error), true); }
  });
  byId('identity-target-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; state.identityEmail = byId('identity-email').value.trim(); setBusy(form, true);
    try { await requestIdentityReauth(); } catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('identity-reauth-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; setBusy(form, true);
    try {
      var verified = await request('/v2/web/auth/reauth/email', { method: 'POST', mutation: true, body: { challengeId: state.identityReauthChallengeId, email: state.reauthEmail, code: byId('identity-reauth-code').value, scope: state.identityAction === 'unlink' ? 'account.identity.unlink' : 'account.identity.link' } });
      state.identityGrant = verified.grant; await continueIdentityAfterReauthentication();
    } catch (error) { state.identityGrant = ''; flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('identity-link-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; setBusy(form, true);
    try {
      await request('/v2/web/identities/email', { method: 'POST', mutation: true, body: { challengeId: state.identityLinkChallengeId, email: state.identityEmail, code: byId('identity-link-code').value, grant: state.identityGrant } });
      state.identityGrant = ''; byId('identity-dialog').close(); flash('新邮箱已绑定。 / New email identity linked.', false); await loadIdentities();
    } catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('identity-dialog').addEventListener('close', resetIdentityDialog);
  byId('installation-reauth-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; setBusy(form, true);
    try {
      var verified = await request('/v2/web/auth/reauth/email', { method: 'POST', mutation: true, body: { challengeId: state.installationChallengeId, email: state.reauthEmail, code: byId('installation-reauth-code').value, scope: 'account.installation.revoke' } });
      await completeInstallationRevoke(verified.grant);
    } catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  async function completeInstallationRevoke(grant) {
    await request('/v2/web/installations/' + state.installationId, { method: 'DELETE', mutation: true, body: { grant: grant } });
    state.installationId = ''; state.installationChallengeId = ''; byId('installation-dialog').close();
    flash('该设备的登录会话已撤销。 / Device sessions revoked.', false); await Promise.all([loadInstallations(), loadAuditEvents()]);
  }
  async function beginAccountDeletion() {
    if (!state.accountDeletionEnabled) return;
    if (!state.reauthEmail && !state.hasGoogleIdentity) { flash(messages['HR-AUTH-006'] + '（HR-AUTH-006）', true); return; }
    state.accountDeletionChallengeId = ''; byId('account-deletion-confirmation').value = ''; byId('account-deletion-acknowledgement').checked = false; byId('account-deletion-code').value = '';
    hide('account-deletion-reauth-form'); hide('account-deletion-google-reauth'); show('account-deletion-confirm-form');
    byId('account-deletion-dialog').showModal(); byId('account-deletion-confirmation').focus();
  }
  byId('account-deletion-confirm-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget;
    if (byId('account-deletion-confirmation').value !== 'DELETE') { flash('请输入 DELETE 以确认永久删除。 / Type DELETE to confirm permanent deletion.', true); return; }
    if (byId('account-deletion-acknowledgement').checked !== true) return;
    setBusy(form, true);
    try {
      hide('account-deletion-confirm-form');
      if (!state.reauthEmail) { show('account-deletion-google-reauth'); prepareGoogleButton('account-deletion-google-button', 'reauth_account_delete'); return; }
      var result = await request('/v2/web/auth/reauth/email/challenges', { method: 'POST', mutation: true, body: { email: state.reauthEmail } });
      state.accountDeletionChallengeId = result.challenge.challengeId; show('account-deletion-reauth-form'); byId('account-deletion-code').focus();
      flash('最终确认验证码已发送。 / Final confirmation code sent.', false);
    } catch (error) { show('account-deletion-confirm-form'); flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  byId('account-deletion-reauth-form').addEventListener('submit', async function (event) {
    event.preventDefault(); var form = event.currentTarget; setBusy(form, true);
    try {
      var verified = await request('/v2/web/auth/reauth/email', { method: 'POST', mutation: true, body: { challengeId: state.accountDeletionChallengeId, email: state.reauthEmail, code: byId('account-deletion-code').value, scope: 'account.delete' } });
      await completeAccountDeletion(verified.grant);
    } catch (error) { flash(uiError(error), true); } finally { setBusy(form, false); }
  });
  async function completeAccountDeletion(grant) {
    await request('/v2/web/account', { method: 'DELETE', mutation: true, body: { grant: grant, acknowledgedPermanentCloudDeletion: byId('account-deletion-acknowledgement').checked === true } }, false);
    state.account = null; byId('account-deletion-dialog').close(); showDeletionPending();
  }
  byId('delete-account').addEventListener('click', beginAccountDeletion);
  byId('deletion-new-account').addEventListener('click', showSignIn);
  byId('account-deletion-dialog').addEventListener('close', function () {
    state.accountDeletionChallengeId = ''; byId('account-deletion-confirmation').value = ''; byId('account-deletion-acknowledgement').checked = false; byId('account-deletion-code').value = ''; clear(byId('account-deletion-google-button')); hide('account-deletion-reauth-form'); hide('account-deletion-google-reauth'); show('account-deletion-confirm-form');
    if (state.googleTargetId === 'account-deletion-google-button') { state.googleAction = ''; state.googleTargetId = ''; }
  });
  byId('installation-dialog').addEventListener('close', function () {
    state.installationId = ''; state.installationChallengeId = ''; byId('installation-reauth-code').value = ''; clear(byId('installation-google-button')); hide('installation-google-reauth'); show('installation-reauth-form');
    if (state.googleTargetId === 'installation-google-button') { state.googleAction = ''; state.googleTargetId = ''; }
  });
  byId('share-dialog').addEventListener('close', function () {
    state.reauthChallengeId = ''; state.shareEmail = ''; byId('reauth-code').value = ''; clear(byId('share-google-button')); hide('share-google-reauth'); hide('reauth-form'); show('share-form');
    if (state.googleTargetId === 'share-google-button') { state.googleAction = ''; state.googleTargetId = ''; }
  });
  byId('refresh').addEventListener('click', loadDashboard);
  byId('signout').addEventListener('click', async function () { try { await request('/v2/web/auth/sign-out', { method: 'POST', mutation: true }); } catch (_) {} state.account = null; location.replace('/account'); });
  bootstrap();
}());`;
