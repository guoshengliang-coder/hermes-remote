// Tiny classic script, emitted as a hashed same-origin asset. It runs before the first paint,
// independently of the application bundle; a missing bundle still gets a working Reload action.
(function () {
  // The main entry also imports this as a fallback when the small head asset could not load.
  // A late application bundle must never restart an existing gate or erase its timeout.
  if (window.hermesStartup) return;
  var html = document.documentElement;
  function choice(key, allowed) {
    try { var value = localStorage.getItem(key); return allowed.indexOf(value) >= 0 ? value : allowed[0]; }
    catch (_) { return allowed[0]; }
  }
  var theme = choice('hermes-go.theme', ['system', 'light', 'dark']);
  html.dataset.theme = theme === 'system' ? (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : theme;
  var font = choice('hermes-go.fontSize', ['standard', 'large', 'xlarge', 'xxlarge']);
  if (font !== 'standard') html.dataset.fontSize = font;
  var language = choice('hermes-go.language', ['system', 'zh', 'en']);
  if (language === 'system') {
    var tag = (navigator.language || (navigator.languages || [])[0] || '').toLowerCase();
    language = !tag || tag === 'zh' || tag.indexOf('zh-') === 0 ? 'zh' : 'en';
  }
  html.lang = language === 'en' ? 'en' : 'zh-CN';
  html.dataset.startup = 'loading';

  var timer, reveal, exit, observer, nodes, retry, onTimeout, failure;
  var state = 'loading', revealed = false;
  function text(zh, en) { return language === 'en' ? en : zh; }
  function clearTimers() { clearTimeout(timer); clearTimeout(reveal); clearTimeout(exit); }
  function paint() {
    if (!nodes) return;
    nodes.loading.hidden = state !== 'loading' || !revealed;
    nodes.loading.classList.toggle('visible', revealed);
    nodes.error.hidden = state !== 'failed';
    nodes.status.textContent = text('正在准备…', 'Getting ready…');
    nodes.retry.textContent = text('重试', 'Retry');
    nodes.detailsLabel.textContent = text('详情', 'Details');
    if (failure) {
      nodes.summary.textContent = text(failure.zh, failure.en);
      nodes.code.textContent = failure.code;
      nodes.retry.hidden = !failure.retryable && failure.action !== 'reload';
      if (failure.action === 'reload') nodes.retry.textContent = text('重新加载', 'Reload');
      nodes.details.hidden = !failure.details;
      nodes.cause.textContent = failure.details || '';
    }
  }
  function attach() {
    var gate = document.getElementById('startup');
    var app = document.getElementById('app');
    if (!gate || !app) return;
    observer && observer.disconnect();
    nodes = {
      gate: gate, app: app,
      loading: document.getElementById('startup-loading'), status: document.getElementById('startup-status'),
      error: document.getElementById('startup-error'), summary: document.getElementById('startup-summary'),
      code: document.getElementById('startup-code'), retry: document.getElementById('startup-retry'),
      details: document.getElementById('startup-details'), detailsLabel: document.getElementById('startup-details-label'),
      cause: document.getElementById('startup-cause')
    };
    nodes.retry.addEventListener('click', function () {
      if (!retry || (failure && failure.action === 'reload')) { window.location.reload(); return; }
      clearTimers(); failure = null; revealed = false; state = 'loading'; html.dataset.startup = state;
      nodes.details.open = false;
      nodes.loading.classList.remove('visible');
      arm(); paint(); retry();
    });
    paint();
  }
  function fail(error) {
    if (state !== 'loading') return;
    clearTimers(); failure = error; state = 'failed'; html.dataset.startup = state; paint();
    if (nodes && !nodes.retry.hidden) nodes.retry.focus();
    else if (nodes && !nodes.details.hidden) nodes.detailsLabel.focus();
  }
  function arm() {
    reveal = setTimeout(function () {
      if (state !== 'loading') return;
      revealed = true; paint();
      if (nodes) nodes.loading.classList.add('visible');
    }, 700);
    timer = setTimeout(function () {
      fail({ code: 'HR-WEB-013', zh: '网页启动超时，请检查网络后重试。', en: 'The web app took too long to start. Check your connection and retry.', retryable: true, action: 'reload' });
      if (onTimeout) onTimeout();
    }, 15000);
  }
  window.hermesStartup = {
    bind: function (onRetry, timeout) {
      retry = onRetry; onTimeout = timeout;
      if (!nodes) attach();
      if (state === 'failed') timeout();
      return function () { retry = null; onTimeout = null; };
    },
    get pending() { return state === 'loading'; },
    fail: fail,
    language: function (value) { language = value; paint(); },
    complete: function () {
      if (state !== 'loading') return;
      clearTimers(); state = 'done'; html.dataset.startup = state;
      if (!nodes) attach();
      if (!nodes) return;
      // Completion never waits to show a success message or a minimum brand duration.
      nodes.loading.hidden = true;
      nodes.app.inert = false;
      nodes.app.removeAttribute('aria-hidden');
      nodes.gate.setAttribute('aria-hidden', 'true');
      nodes.gate.inert = true;
      if (nodes.gate.contains(document.activeElement)) document.activeElement.blur();
      var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      exit = setTimeout(function () { nodes.gate.hidden = true; }, reduced ? 0 : 180);
    },
    dispose: function () { clearTimers(); observer && observer.disconnect(); }
  };
  observer = new MutationObserver(attach);
  observer.observe(html, { childList: true, subtree: true });
  attach(); arm();
})();
