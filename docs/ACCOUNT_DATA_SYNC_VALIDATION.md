# HG-216 · 账号资料同步本地联调

2026-10-15，承接 [P1a](ACCOUNT_DATA_SYNC_P1A.md) 的默认关闭实现。
本轮使用真实 Gateway、临时 PostgreSQL 18.6、人工测试账号和浏览器；Mac 端为 mock Hermes，
未读取个人数据库、真实邮件或生产配置。此记录不表示生产迁移、发布或能力开启。

## 联调发现与修复

WebKit 实测在 IndexedDB 保存头像 Blob 时拒绝写入，出现 `HR-SYNC-011`，没有发出修改。
新队列改存 ArrayBuffer 字节及 MIME，发送时还原 Blob；名字和头像的 wire/幂等语义不变。
IndexedDB 升到版本 2，升级不删旧记录，仍可读取版本 1 的 Blob 队列并沿用原 opId。
旧的 P1a Web 代码若尝试打开已升级数据库，会因版本不匹配停止编辑（`HR-SYNC-011`），
不会猜读新格式或清空队列；回退不应通过删库解决。P1a 尚未生产启用，本轮不提高服务端最小客户端要求。

原实现收到其他标签页的退出代际后，只停止资料同步 worker。已打开的账号页或聊天页可能
继续显示私人内容，直到后续网络请求拒绝。网络不可达时不能依赖 HTTP 401 来清屏。

Web App 现在直接消费同一退出信号，取消启动/设备加载，停止续期和资料 worker，隐藏账号、
Mac 与会话，清除私人缓存和运行态，回到普通登录页。不重复调用服务端退出，也不再次广播
退出代际，避免标签页来回触发。既有显式退出语义不变；没有新用户错误码或视觉设计决策。

`App.test.tsx` 的即时退出用例在旧代码上失败，在修复后通过；另一个用例让旧设备列表请求
在退出后才返回，证明它不能重新打开私人页面。浏览器冒烟将第二标签页的 `/v2` 网络全部
阻断，仍要求它收到本地退出信号就显示登录页，恢复网络后再核对服务器会话已失效。

## 可重复执行

先在独立 worktree 按组件规则安装依赖并构建根包和 `web/`。Playwright 为独立开发工具，
不进入产品依赖或分发包。本次使用 Playwright 1.64.0；`HR_PLAYWRIGHT_MODULE` 指向其
`index.mjs`，对应浏览器需已安装。测试使用全新浏览器上下文，不打开用户浏览器资料目录。

```bash
export HR_WEB_STACK_STATE="$(mktemp -d /tmp/hermes-profile-sync.XXXXXX)"
export HR_WEB_STACK_PG_BIN="$(dirname "$(command -v pg_ctl)")"
export HR_WEB_STACK_ACCOUNT_DATA_SYNC=1
export HR_PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs
./scripts/dev/web-stack.sh up
node scripts/dev/profile-sync-smoke.mjs
./scripts/dev/web-stack.sh down
```

`up` 会重建该隔离栈的测试数据库。每轮完整冒烟都从新数据库开始，避免账号的正常 OTP 配额
影响重复运行。受控执行器若会在命令退出时回收子进程，让 `up → smoke → down` 在同一个
控制进程里执行，并用 `trap` 保证停止。不要因此改用共享开发栈或生产账号。

`HR_WEB_STACK_ACCOUNT_DATA_SYNC` 只接受 `0|1`、默认 `0`，只控制本机开发栈；不影响生产。
冒烟脚本只接受 HTTPS `localhost`、端口不小于 18000 和 `@example.test` 人工账号。
换端口时同时设置 `HR_WEB_STACK_GATEWAY_PORT` 和 `HR_PROFILE_SYNC_ORIGIN`，并为 mock 与
PostgreSQL 分配独立端口。WebKit 用 `HR_PROFILE_SYNC_BROWSER=webkit`，同样要重建隔离栈。

浏览器测试覆盖独立安装登录、前台改名、编辑冲突且不隐式覆盖、名字和头像离线 IndexedDB 队列跨页面
重启、提交后丢 ACK 的原操作重放、真实头像编辑上传/渲染与 no-store、跨账号拒绝和离线标签页退出。
丢 ACK 用网络拦截先向真实服务完成请求，再丢弃响应；未 mock 成功响应或替换数据库。
为保证该拦截在 WebKit 也确实生效，本套件禁用 Service Worker；否则已提交请求可能直接收到
worker 转发的响应，不能把它误记为“丢 ACK”。Service Worker 缓存/离线 app shell 仍须单独验收。

## Android 真正消费同一份云端数据

先启动空闲模拟器，按 [设备手册](DEVICE_TESTING.md) 构建、安装本 worktree 的版本化调试包，
再安装 `:app:assembleDebugAndroidTest` 生成的测试 APK。这是本地验证，不是 APK 分发。
运行浏览器冒烟时额外设置：

```bash
export HR_PROFILE_SYNC_NATIVE_FIXTURE="$HR_WEB_STACK_STATE/native-live.json"
node scripts/dev/profile-sync-smoke.mjs
python3 scripts/dev/profile-sync-android-smoke.py \
  --serial <本次启动的模拟器serial> --fixture "$HR_PROFILE_SYNC_NATIVE_FIXTURE"
```

这两个命令之间保持本轮栈运行。浏览器脚本通过真实原生登录端点签发同账号测试会话，将
结果及本轮 CA 写入权限 0600 的临时夹具，不打印凭据。Android 驱动只接受自报 qemu 的
模拟器，通过 stdin 写入应用 UID 下专属私有目录；不将 token 放进 argv 或共享文件，
不覆盖已有其他反向端口映射。测试结束清除设备夹具，自己创建的 reverse 也会移除。
结束后删除主机夹具、停止自己的栈和模拟器。

instrumentation 在目标 UID 下执行；不能把夹具放在测试 APK 的另一 UID 目录。
测试使用 `hg216-live-` 前缀的独立加密 SharedPreferences，不读取应用保存的真实账号。
测试客户端只信任本轮 CA，并保留主机名校验；生产网络配置不变，不引入 trust-all。

`AccountProfileSyncLiveTest` 使用生产 `AccountApi`、`AccountSessionManager`、
`EncryptedCredentialStore`、头像缓存，核对名字/精确修订、实际可解码头像及 SHA-256、存储
重新打开、网络失败仍保留缓存，最后调用真实原生退出并检查本地资料/图片清理。
驱动检查 `OK (1 test)`，不能把 `am instrument` 的退出码 0 当作 JUnit 通过。
未显式传 `profileSyncLive=1` 时该测试跳过；普通 CI 不需要临时账号、CA 或生产秘密。

## 证据边界与剩余门禁

- Node：`npm run build`；使用隔离 PostgreSQL 和 `RUN_NETWORK_TESTS=1` 的 `npm test`，
  857 通过、0 跳过、0 失败。本机使用已有 OpenSSL 3 运行既有恢复测试。
- Web：typecheck、build 和全套 916 项测试通过；覆盖即时退出/迟到设备列表、拒绝 Blob 时的
  字节队列恢复和旧数据库无损升级。真实浏览器：Chromium 156 与 WebKit 27.2。
- Android L1：`:app:testDebugUnitTest :app:assembleDebug`，2512 项，0 失败/错误/跳过。
- Android L3：Pixel_API_37 / emulator-5554，新增真实 HTTP 消费 instrumentation 通过；
  不是完整 App 手势、进程死亡或厂商 ROM 验证。加密存储重新打开也不等于进程死亡。
- Android L2：当前物理设备未授权 USB 调试，未做真机验证。不推断其品牌或型号。

真实双手机、物理 Safari/Home Screen、Android 账号卡最终外观、生产备份恢复与正式分发版本
仍需相应授权和验收。本地浏览器引擎不代表真实 iPhone；mock Mac 不证明真实上游内容观察。
资料同步开关继续默认关闭，Android 编辑、Hermes 身份装饰、置顶、已读、模型和提示词仍按
后续分期。新增实体前仍须设计提交有序日志/分页、访问代际及首次导入，不能复用 P1a 单实体
游标冒称通用同步。完整已读权威版本门禁不因本轮联调而通过。

2026-10-16 后续：模型收藏/推理偏好独立实现及本地验证见
[ACCOUNT_DATA_SYNC_MODELS.md](ACCOUNT_DATA_SYNC_MODELS.md)，不改变本页 P1a 联调的历史证据边界。
