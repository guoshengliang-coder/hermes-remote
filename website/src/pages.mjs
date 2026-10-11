import { errorEnvelope } from './errors.mjs';

export const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const arrow = '<span aria-hidden="true">↗</span>';
const icons = {
  phone: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>',
  tablet: '<rect x="3" y="3" width="14" height="18" rx="2"/><path d="M9 17h2"/><path d="M20 7h1v14h-1"/>',
  mac: '<rect x="4" y="3" width="16" height="12" rx="2"/><path d="m4 15-2 4h20l-2-4M10 19h4"/>',
};
function icon(name) { return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name]}</svg>`; }
function button(text, url, primary = false, attributes = '') { return `<a class="button${primary ? ' primary' : ''}" href="${escapeHTML(url)}" ${attributes}>${text} ${arrow}</a>`; }
function webButton(config) { return button('打开 Web App', config.webAppURL, false, 'data-web-entry'); }
const introduction = '<p class="description">Hermes 在你的 Mac 上执行任务。<br>拿起手机就能交办、跟进，随时获取结果。</p>';
function pageHead(kicker, title, description) { return `<div class="page-head"><span class="kicker">${kicker}</span><h1>${title}</h1><p class="muted">${description}</p></div>`; }

function home(config) {
  return `<section class="hero">
    <span class="kicker"><span class="dot"></span>Your AI agent, in your pocket.</span>
    <h1>你的 AGENT，<br><em>随时在身边。</em></h1>
    ${introduction}
    <div class="actions">${button('<span data-primary-label>获取移动端</span>', '/download/', true, 'data-primary-entry')}${button('快速上手', '/guide/')}</div>
    <p class="note">邀请测试中 · Hermes GO 免费，模型/API 费用另计。</p>
  </section>
  <div class="entry-grid" aria-label="选择你的设备">
    <article class="entry"><div class="entry-title">${icon('phone')}<h2>Android</h2></div><p>下载 App，把你的 Agent 带在身边。</p>${button('下载 Android App', '/download/#android', true)}</article>
    <article class="entry"><div class="entry-title">${icon('tablet')}<h2>iPhone / iPad</h2></div><p>通过 Web App 使用，无需下载。</p>${webButton(config)}</article>
  </div>
  <div class="mac-line">${icon('mac')}<span>首次使用？先在你的 Mac 上准备 Hermes。</span><a href="/download/#desktop">获取 macOS Desktop <span aria-hidden="true">→</span></a></div>
  <section class="section"><div class="section-heading"><div><h2>离开电脑，事情继续。</h2><p class="muted">让任务跟着你的节奏走。</p></div><a href="/guide/">看看如何开始 →</a></div>
    <div class="scenarios">
      <article><span class="kicker">01 / 交办</span><h3>想到了，就交给 Hermes</h3><p>临时想到一个需求，打开手机继续对话，把要做的事说清楚。</p></article>
      <article><span class="kicker">02 / 跟进</span><h3>路上也能掌握进展</h3><p>看看任务执行到了哪一步，需要你补充信息时，及时接上。</p></article>
      <article><span class="kicker">03 / 获取</span><h3>完成的结果，随手可用</h3><p>阅读回复、查看输出文件，让工作从电脑延伸到手边。</p></article>
    </div>
  </section>
  <section class="ecosystem"><div><h2>任务在 Mac 上执行，<br>你在任何地方继续。</h2><p class="muted">远程使用时，Mac 和 Hermes 需要保持在线。</p><a class="text-link" href="/guide/">了解如何连接 →</a></div>
    <div class="device-list">${[['mac', 'Mac + Desktop', '准备 Hermes，管理连接。'], ['phone', 'Android App', '手机上的原生入口。'], ['tablet', 'iPhone / iPad', '通过 Web App，继续你的任务。']].map(([symbol, title, text]) => `<div>${icon(symbol)}<p><strong>${title}</strong><span>${text}</span></p></div>`).join('')}</div>
  </section>`;
}

function releaseMetadata(release) {
  if (!release || release.status === 'unpublished') return '<p class="release-status">安装包暂未开放</p>';
  if (release.status !== 'available') {
    const error = errorEnvelope('HR-SITE-004');
    return `<p class="release-status">${error.message} <span class="error-code">${error.code}</span></p>`;
  }
  return `<dl class="release-meta"><div><dt>最新版本</dt><dd>${escapeHTML(release.version)}</dd></div><div><dt>发布时间</dt><dd>${escapeHTML(release.date.slice(0, 10))}</dd></div><div><dt>文件大小</dt><dd>${(release.sizeBytes / 1024 / 1024).toFixed(1)} MB</dd></div><div><dt>系统要求</dt><dd>${escapeHTML(release.requirement)}</dd></div></dl>`;
}
function releaseNotes(release) {
  if (release?.status !== 'available') return '';
  return `<details class="release-notes"><summary>更新说明与文件校验</summary><ul>${release.notes.map(note => `<li>${escapeHTML(note)}</li>`).join('')}</ul><p>SHA-256</p><code>${escapeHTML(release.sha256)}</code></details>`;
}
function downloadButton(platform, release, verified) {
  if (release?.status !== 'available') return '<span class="button unavailable" aria-disabled="true">暂未提供下载</span>';
  return verified ? button(`下载 ${platform === 'android' ? 'Android APK' : 'Desktop DMG'}`, `/downloads/${platform}`, platform === 'android')
    : button('验证邀请码后下载', '/download/#invite');
}
function download(config, { verified, metadata }) {
  const { android, desktop } = metadata;
  return `${pageHead('GET HERMES GO', '在手机上，连接你的 Agent。', 'Android 下载 App；iPhone 和 iPad 使用 Web App。')}
    <section class="invite" id="invite"><div><h2>${verified ? '下载资格已解锁' : '已有测试邀请码？'}</h2><p class="muted">${verified ? '你可以下载下方已开放的客户端。' : '输入管理员提供的六位邀请码，解锁客户端下载。'}<br>Web App 沿用现有登录规则，可直接进入。</p></div>
      ${verified ? '<p class="verified" role="status">已验证 · 下载资格有效期为 12 小时</p>' : '<form action="/api/invite" method="post" data-invite-form><label for="invite-code">测试邀请码</label><div class="input-row"><input id="invite-code" name="code" type="text" inputmode="numeric" pattern="[0-9]{6}" minlength="6" maxlength="6" autocomplete="off" placeholder="请输入六位邀请码" required aria-describedby="invite-status"><button class="button primary" type="submit">验证并解锁</button></div><p id="invite-status" class="form-status" role="status" aria-live="polite">邀请码由管理员提供。</p></form>'}
    </section>
    <div class="entry-grid downloads" id="clients">
      <article class="entry" id="android"><div class="platform-icon">${icon('phone')}</div><h2>Android App</h2><p>在安卓手机上安装 Hermes GO，<br>随时交办任务、查看进展和结果。</p>${releaseMetadata(android)}${downloadButton('android', android, verified)}${releaseNotes(android)}</article>
      <article class="entry"><div class="platform-icon">${icon('tablet')}</div><h2>iPhone / iPad</h2><p>使用 Web App 访问 Hermes，<br>无需下载原生客户端。</p><p class="release-status">浏览器入口 · 沿用现有登录规则</p>${webButton(config)}<a class="text-link" href="/guide/#mobile">了解如何添加到主屏幕 →</a></article>
    </div>
    <section class="mac-companion" id="desktop"><div class="platform-icon">${icon('mac')}</div><div class="mac-copy"><h2>准备你的 Mac · macOS Desktop</h2><p class="muted">Hermes 在你的 Mac 上运行。Desktop 用于准备和管理连接。</p>${releaseMetadata(desktop)}${releaseNotes(desktop)}</div>${downloadButton('desktop', desktop, verified)}</section>
    <div class="two-columns section"><div><h3>第一次使用？</h3><p class="muted">先准备 Mac 和模型，再连接手机或 Web。<br>跟着上手指南，完成第一个任务。</p><a class="text-link" href="/guide/">阅读快速上手 →</a></div><div><h3>安装 Android APK</h3><p class="muted">下载后打开 APK，按系统提示允许本次安装。测试版不通过应用商店分发；请从官网获取安装包。已安装用户可在 App 中检查更新。</p></div></div>`;
}

function steps(items) { return `<ol class="steps">${items.map(([title, body]) => `<li><h3>${title}</h3><p class="muted">${body}</p></li>`).join('')}</ol>`; }
function guide(config) {
  return `${pageHead('GET STARTED', '让第一个任务，顺利开始。', '从 Mac 到手机，一步步连接你的 Hermes。')}
  <nav class="guide-nav" aria-label="选择上手路径"><a class="button" href="#new">我第一次使用 Hermes</a><a class="button" href="#existing">我已经在用 Hermes</a></nav>
  <div class="guide-layout"><div>
    <section class="guide-path" id="new"><h2>第一次使用 Hermes</h2>${steps([
      ['准备你的 Mac 和模型', '准备一台可联网、能保持运行的 Mac，以及可用的模型服务。模型/API 由你选择和配置，可能产生独立费用。'],
      ['获取 Desktop，准备 Hermes', '在<a href="/download/#desktop">下载页</a>查看可用版本。未开放时请向管理员获取测试版本。安装并打开 Desktop，按其中的引导操作：已有 Hermes 时使用现有安装；没有时按提示安装 Hermes。模型配置仍需由你完成。'],
      ['确认 Mac 上已就绪', '先在 Mac 本地让 Hermes 完成一次对话，再检查 Desktop 的连接状态。暂时不要关闭 Mac 或让它进入睡眠；移动端需要 Mac 和 Hermes 在线。'],
      ['连接移动端，完成第一个任务', 'Android 安装 App；iPhone/iPad 打开 Web App。按当前版本显示的登录或连接引导操作。连接成功后发送“帮我列一个今天的待办清单”，确认可以收到回复。'],
    ])}</section>
    <section class="guide-path" id="existing"><h2>已经在用 Hermes</h2>${steps([
      ['先确认现有 Hermes 可用', '在 Mac 本地完成一次对话，确认模型和服务正常。保留你已有的配置与会话。'],
      ['让 Desktop 连接现有 Hermes', '打开 Desktop，按其连接或接管引导检查这台 Mac 的 Hermes。若需要迁移或停止现有服务，请先阅读客户端的确认信息。'],
      ['使用当前连接方式', '按客户端提供的登录或连接引导操作。若使用 Relay URL + App Token 模式，从 Desktop 或管理员获取公网地址与专用 App Token；不要把 Mac 的 Hermes 凭据填到手机中。'],
      ['继续已有对话', '连接后选择已有会话，发送一条新消息。若看不到预期会话，先确认连接的是同一台 Mac，再查看<a href="/help/#connection">连接排查</a>。'],
    ])}</section>
  </div><aside class="prepare"><span class="kicker">开始之前</span><h2>准备好这三件事</h2><ul><li>一台运行 Hermes 的 Mac</li><li>可用的模型服务或 API 配置</li><li>管理员提供的测试资格与连接信息</li></ul><p class="muted">官网邀请码仅解锁官网下载。客户端与 Web App 的使用资格，以它们现有的登录与准入规则为准。</p><a class="text-link" href="/download/">查看可用版本 →</a></aside></div>
  <section class="section" id="mobile"><h2>把 Web App 放到主屏幕</h2><p class="muted">在 iPhone 或 iPad 的 Safari 中<a href="${config.webAppURL}">打开 Web App</a>，点分享按钮，选择“添加到主屏幕”。之后可以从桌面图标进入。此入口仍需按 Web App 现有规则登录。</p></section>`;
}

function help(config) {
  const questions = [
    ['使用 Hermes GO，需要自己的 Mac 吗？', '需要一台运行 Hermes 的 Mac。手机是远程入口，Hermes 在 Mac 上执行任务。远程使用时请保持 Mac、Hermes 与网络在线。', 'mac'],
    ['没有邀请码，可以先进入 Web App 吗？', `可以<a href="${config.webAppURL}">打开 Web App</a>。官网邀请码只控制 Android 和 Desktop 的官网下载；能否使用 Web App，由它现有的登录与准入规则决定。`, 'invite'],
    ['Hermes GO 免费，为什么还会有模型费用？', 'Hermes GO 在邀请测试阶段免费。Hermes 调用的模型服务可能按供应商规则收费，具体取决于你配置的服务。', 'cost'],
    ['iPhone 可以下载原生 App 吗？', '目前使用 Web App，没有 iOS 原生安装包。可以通过 Safari 添加到主屏幕。', 'ios'],
    ['macOS Desktop 为什么暂时不能下载？', '正式版本索引尚未发布时，官网会显示“安装包暂未开放”。如已参加测试，请向管理员获取当前可用版本与安装说明。', 'desktop'],
    ['连接不上，先检查什么？', '确认 Mac 没有睡眠、Hermes 在本地可用、Desktop 连接正常，再检查手机网络和当前登录或连接配置。若使用 Relay URL + App Token 模式，请确认地址与专用 App Token 正确。仍失败时，保留客户端显示的 HR 错误码与版本信息。', 'connection'],
    ['下载后如何安装或更新？', 'Android：打开 APK，按系统提示允许本次安装；更新时使用同一渠道，不要先卸载已有 App。macOS：按该测试包随附的安装说明操作。如果系统阻止打开，请记录提示并向管理员咨询。', 'installation'],
  ];
  return `${pageHead('HELP', '有疑问，我们一起解决。', '从常见问题开始，找到下一步。')}
    <div class="faq">${questions.map(([title, body, id], i) => `<details id="${id}" ${i === 0 ? 'open' : ''}><summary>${title}</summary><p>${body}</p></details>`).join('')}</div>
    ${config.supportEmail ? `<section class="contact"><h2>还需要帮助？</h2><p class="muted">发邮件时请说明使用的端、版本、遇到的现象和操作步骤。<br>截图中请隐藏邀请码、凭据和个人信息。</p><a class="text-link" href="mailto:${escapeHTML(config.supportEmail)}">${escapeHTML(config.supportEmail)} ${arrow}</a></section>` : ''}
    <section class="section privacy" id="privacy"><h2>数据与隐私</h2><p class="muted">官网不要求提交模型/API 凭据，也不读取你的 Hermes 会话。邀请码验证后，这个浏览器会保存一个 12 小时有效的下载资格 Cookie。服务端为限制重复尝试，在内存中短暂记录请求 IP 与尝试次数（约 10 分钟），不写入磁盘。</p><p class="muted">进入 Web App 或安装客户端后，数据处理以对应产品的规则为准。Hermes 的运行和会话在你的 Mac 上；模型请求会按你的配置发送给相应服务。</p></section>`;
}

function shell(page, config, content, title) {
  const routes = [['home', '/', '产品'], ['download', '/download/', '下载与使用'], ['guide', '/guide/', '快速上手'], ['help', '/help/', '帮助']];
  const path = routes.find(([name]) => name === page)?.[1] ?? '/';
  const description = '你的 AGENT，随时在身边。Hermes GO 让你从手机交办任务、跟进进展和获取结果。';
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>${escapeHTML(title)} · Hermes GO</title><meta name="description" content="${description}"><link rel="canonical" href="${escapeHTML(config.origin + path)}"><meta property="og:title" content="${escapeHTML(title)} · Hermes GO"><meta property="og:description" content="${description}"><meta property="og:type" content="website"><meta property="og:url" content="${escapeHTML(config.origin + path)}"><meta property="og:image" content="${escapeHTML(config.origin)}/logo.png"><link rel="icon" href="/logo.png"><link rel="stylesheet" href="/site.css"><script src="/site.js" defer></script></head>
  <body><a class="skip" href="#main">跳到正文</a><header class="header"><div class="header-inner"><a class="brand" href="/" aria-label="Hermes GO 首页"><img src="/logo.png" width="32" height="32" alt="">Hermes GO</a><nav aria-label="主导航">${routes.map(([name, href, text]) => `<a href="${href}"${name === page ? ' aria-current="page"' : ''}>${text}</a>`).join('')}</nav></div></header><main id="main" class="content">${content}</main><footer><div class="footer-inner"><span>Hermes GO · Your AI agent, in your pocket.</span><div><span>邀请测试</span><a href="/help/#privacy">数据与隐私</a><a href="/help/">帮助${config.supportEmail ? '与反馈' : ''}</a></div></div></footer></body></html>`;
}
export function renderPage(page, config, options = {}) {
  const content = { home: () => home(config), download: () => download(config, options), guide: () => guide(config), help: () => help(config) }[page]();
  return shell(page, config, content, { home: '你的 AGENT，随时在身边。', download: '下载与使用', guide: '快速上手', help: '帮助' }[page]);
}
export function renderError(config, error) {
  return shell('download', config, `${pageHead('HERMES GO', '请稍候，再继续。', escapeHTML(error.message))}<p class="error-code">${error.code}</p><div class="actions">${button(error.recoveryAction === 'verify_invite' ? '重新验证邀请码' : '返回下载页', '/download/', true)}${error.retryable ? '<button class="button" data-retry>重试</button>' : ''}${button('查看帮助', '/help/')}</div>`, '操作提示');
}
