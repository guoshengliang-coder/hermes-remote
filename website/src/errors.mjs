// Primary copy and fixed stages only. Never serialize an exception or request data.
export const errors = Object.freeze({
  'HR-SITE-001': ['邀请码不正确，请向管理员确认后重新输入。', 'Incorrect invitation code. Confirm it with the administrator and enter it again.', false, 'contact_admin', 'invite'],
  'HR-SITE-002': ['尝试次数过多，请稍后再试。', 'Too many attempts. Please try again later.', true, 'retry', 'invite'],
  'HR-SITE-003': ['下载资格已过期，请重新验证邀请码。', 'Download access has expired. Verify your invitation code again.', false, 'verify_invite', 'download'],
  'HR-SITE-004': ['暂时无法获取版本信息，请稍后刷新。', 'Release information is unavailable. Refresh later.', true, 'retry', 'release'],
  'HR-SITE-005': ['请求无法处理，请返回下载页重新操作。', "This request couldn't be processed. Return to Downloads and try again.", false, 'downloads', 'request'],
  'HR-SITE-006': ['官网暂时无法处理请求，请稍后再试。', "The website couldn't process this request. Please try again later.", true, 'retry', 'website'],
  'HR-SITE-007': ['网络连接失败，请检查网络后重试。', "Couldn't connect. Check your network and try again.", true, 'retry', 'network'],
  'HR-SITE-008': ['官网配置无效，请联系管理员检查配置。', 'Website configuration is invalid. Ask the administrator to check the configuration.', false, 'contact_admin', 'config'],
});
export function errorEnvelope(code, language = 'zh') {
  const [zh, en, retryable, recoveryAction, stage] = errors[code] ?? errors['HR-SITE-006'];
  return { code: errors[code] ? code : 'HR-SITE-006', message: language === 'en' ? en : zh, retryable, recoveryAction, stage };
}
