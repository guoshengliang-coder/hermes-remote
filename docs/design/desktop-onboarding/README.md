# Desktop 登录门禁与新人引导设计稿

对应需求：`docs/DESKTOP_ONBOARDING_REQUIREMENTS.md`。2026-09-23 绘制，视觉令牌取自
`docs/DESKTOP_DESIGN.md`「Shared visual tokens」，应用图标直接引用规范源
`android/app/src/main/ic_launcher-playstore.png`。

- `onboarding.html`：全部画面的唯一源文件，`?s=<id>` 只显示一个画面。
- `render.sh`：用本机 Chrome 无头模式把每个画面渲染成 2x PNG（macOS）。
- `download-qr.png`：用 CoreImage 生成、可以真实扫码的二维码，内容为 `https://mrlgs.net/`，已解码校验。

| 文件 | 画面 | 需求章节 |
|---|---|---|
| `01-launch-checking.png` | 启动检查 | §5.1 |
| `02-launch-error.png` | 启动时连不上服务（`HR-CONN-002`） | §5.1 |
| `03-signin-email.png` | 登录 · 输入邮箱 | §5.2 |
| `04-signin-code.png` | 登录 · 输入验证码 | §5.2 |
| `05-signin-expired.png` | 登录已失效（`HR-AUTH-003`） | §5.3 |
| `06-signin-email-dark.png` | 登录 · 深色模式 | §5.2 |
| `07-choice-new-mac.png` | 老账号、新 Mac · 未满额 | §7.1 |
| `08-choice-full.png` | 老账号、新 Mac · 已满 3 台（`HR-BIND-010`） | §7.2 |
| `09-remove-mac-sheet.png` | 移除一台 Mac 的确认 sheet | §7.3 |
| `10-onboard-hermes-found.png` | 引导第 2 步 · 已有 Hermes | §6.2 |
| `11-onboard-hermes-missing.png` | 引导第 2 步 · 没有 Hermes | §6.2 |
| `12-onboard-connect.png` | 引导第 3 步 · 连接中 | §6.2 |
| `13-onboard-phone.png` | 引导第 4 步 · 等待手机（下载二维码） | §6.2 |
| `14-onboard-phone-done.png` | 引导第 4 步 · 手机已连上 | §6.2 |
| `15-manage-only-overview.png` | 只管理模式的主界面 | §7.1、§9 |
| `16-menubar.png` | 菜单栏：未登录 / 未装机 / 只管理 | §9 |

这些是方向稿，不是像素规格。设备名、邮箱、代理地址和验证码都是示例值。「改用内置 Hermes」和
第 3 步开始前的确认 sheet 沿用现有设计，本组没有重画。
