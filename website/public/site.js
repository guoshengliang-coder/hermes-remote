// Progressive enhancement: every entry and the invitation form works without JavaScript.
const primary = document.querySelector('[data-primary-entry]');
if (primary) {
  const label = primary.querySelector('[data-primary-label]');
  if (/Android/i.test(navigator.userAgent)) {
    primary.href = '/download/#android'; label.textContent = '下载 Android App';
  } else if (/iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)) {
    primary.href = document.querySelector('[data-web-entry]').href; label.textContent = '打开 Web App';
  }
}
document.querySelector('[data-retry]')?.addEventListener('click', () => location.reload());
const form = document.querySelector('[data-invite-form]');
form?.addEventListener('submit', async event => {
  event.preventDefault();
  const status = document.getElementById('invite-status');
  const button = form.querySelector('button');
  button.disabled = true; status.classList.remove('error'); status.textContent = '正在验证…';
  try {
    const response = await fetch('/api/invite', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ code: form.elements.code.value }) });
    const result = await response.json();
    if (response.ok && result.verified) {
      form.reset();
      // A hash-only navigation does not request new server-rendered download links.
      history.replaceState(null, '', '/download/#clients');
      location.reload();
      return;
    }
    const allowed = ['HR-SITE-001', 'HR-SITE-002', 'HR-SITE-005', 'HR-SITE-006'];
    if (!allowed.includes(result.error?.code) || typeof result.error?.message !== 'string') throw new Error('response');
    status.textContent = `${result.error.message}（${result.error.code}）`; status.classList.add('error');
  } catch {
    status.textContent = '网络连接失败，请检查网络后重试。（HR-SITE-007）'; status.classList.add('error');
  } finally { button.disabled = false; }
});
