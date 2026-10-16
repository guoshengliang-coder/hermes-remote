# HG-216 · P1c 模型收藏与推理偏好

2026-10-16。用户选择账号模式自动同步、旧数据首次导入先确认、Legacy 仅本机；
每账号 500 个有效模型组合，增量日志和重放记录保留 90 天，取消保留最小修订直到账号删除。
本阶段只交付模型偏好，不包含提示词、置顶、已读或 Hermes profile 装饰。
服务端能力默认关闭；本文不是生产迁移、发布、启用或设备安装凭据。

## 行为与边界

兼容能力为 `accountAuth.modelPreferencesSync={version:1,maxModels:500,retentionDays:90}`，
由独立的 `ACCOUNT_MODEL_SYNC_ENABLED=0` 控制，与 P1a 资料能力和游标独立。
账号服务关闭、能力缺失或版本未知时不调用模型同步接口。已有账号缓存在能力关闭后保持
账号私有只读并保留队列，不回写 Legacy。首次没有账号缓存且能力关闭时仍使用原本机偏好。
服务端开关是部署能力控制；产品不增加独立同步开关。

两端同步收藏与“记住的推理偏好”，不自动调用 Hermes 切模型、改当前会话推理或改 Mac 默认。
实际用户选模型、改当前推理仍走现有确认/失败/恢复流程，成功后才记住偏好。
可清除记住的推理值而不改变当前对话。最近使用及分组展开继续本机。
云端收藏在当前 Mac 不可用时仍显示并可取消收藏，切换动作禁用。

账号归属是 Relay origin + account；不是 Mac/profile。取消收藏不清除该组合的推理偏好，
清除推理不取消收藏。两个字段都为空才形成最小取消标记。
模型身份是原始 `(provider,model)` tuple，不能拼接斜线、规范化空格或按模型名跨 provider 猜测。

## 服务端协议

| Native | Web | 请求与响应 |
| --- | --- | --- |
| GET `/v2/account-data/models/snapshot` | GET `/v2/web/account-data/models/snapshot` | 有效行完整快照，至多 500 行，无分页 |
| GET `/v2/account-data/models/changes?cursor=…` | GET `/v2/web/account-data/models/changes?cursor=…` | 有序增量，每页至多 200 行，冻结本轮上界 |
| POST `/v2/account-data/models/resolve` | POST `/v2/web/account-data/models/resolve` | `{provider,model}`；返回当前行或已取消行的修订 |
| POST `/v2/account-data/models/mutations` | POST `/v2/web/account-data/models/mutations` | 单操作字段 CAS；不提供整包覆盖或批量部分提交 |

Native 使用原生 Bearer，Web 使用 HttpOnly Cookie、Origin/Fetch Metadata/CSRF。
Web 必带 `X-Hermes-Expected-Account`，只断言当前主体，不能选择其他账号。响应 `no-store`。
账号、会话、安装实例在事务内重新检查并加锁，之后才读写实体、日志或幂等终态。
撤销后的旧成功不能重放；无需选择 Mac 即可同步账号偏好。

快照/增量形状：`{version:1,revision,cursor,lease,rows,hasMore}`。
行包含 `key`（64 位十六进制 HMAC）、原始 provider/model、favorite、reasoningPreset，
以及 revision/favoriteRevision/reasoningRevision。修订均为精确 uint64 十进制字符串，
Web 用 BigInt、Android 用 BigInteger，禁止 Number/Long 转换。到上限拒绝，不回绕。
推理值为 none/minimal/low/medium/high/xhigh/max/ultra 或 null（清除偏好）。

操作形状：`{opId,lease,provider,model,field,baseRevision,value}`，拒绝未知字段。
field 为 favorite（Boolean）或 reasoningPreset（枚举/null）；baseRevision 是对应字段修订。
同账号一把提交锁分配递增修订，实体、日志和终态同事务提交。不同字段可合并，同字段旧 base
返回 `{status:"conflict",row}`，成功返回 accepted。客户端用 HR-SYNC-012 显示冲突。
同 opId/语义 payload 返回同一终态，换 payload 拒绝 HR-ACCOUNT-005；lease 不参与语义摘要。

读游标 HMAC 绑定账号、已扫描修订、冻结上界和 24 小时期限；写 lease 绑定账号、安装实例和
90 天期限。过期、篡改、跨账号/安装或保留窗口外游标返回 HR-SYNC-013。
刷新快照不替换旧队列的 lease/base/opId；过期操作不能偷偷变成新意图。
显式“重新应用”才 resolve 最新字段修订、生成新 opId/lease 并原子替换冲突。

## 存储、保留和容量

迁移 020，schema 19→20，新增账号同步状态、模型偏好、增量日志和终态四表。
账号状态中的独立随机 HMAC key 随数据库备份恢复，不随登录签名 key 轮换改变。
取消行擦除 provider/model，只留 HMAC、默认值与字段修订；完整快照不枚举取消行。
首次导入始终 base=0，仍会与保留的取消修订冲突，不能因完整快照没有该行就复活。
在线新选择 resolve 精确 tuple；离线缺少行时用 0，发生冲突后明确确认，不猜最新 base。

500 指 favorite=true 或 reasoningPreset 非 null 的有效组合。取消最后一个字段释放名额。
另设每账号 10,000 个组合标记、10,000 个终态及 10,000 条日志的防滥用上限；达到上限用
HR-SYNC-015 拒绝，保留本机队列，不删除取消标记腾空间。大量历史取消因此可能限制新组合。
日志保存带名称的变更 payload 90 天，终态仅保存请求 HMAC 和不带名称的终态元数据 90 天。
写 lease 在入队前签发，到期不晚于该操作终态的正常清理时间，避免清理后旧成功再次提交。

现有 retention 任务按预算清日志/终态，先锁账号同步状态以匹配写入顺序，并推进 log_floor。
游标低于 floor 必须重建快照。账号删除到期后的有界清理覆盖四表；没有清完不标记匿名化完成，
账号物理删除也级联。常规 90 天清理不删除最小取消标记。没有新的后台服务。

## 客户端耐久队列与导入

Web 使用独立 IndexedDB v1，账号快照和 outbox 在同一 readwrite transaction 中提交。
多标签页按字段原子占位，重复发送由服务端相同 opId 消重。Android 使用独立 app-private
SQLite，账号快照和队列同事务；不存 access/refresh token、Hermes 凭据或配置，系统备份规则
已排除数据库。SQLite 是应用私有存储，不宣称端到端加密或独立数据库加密。
账号登录 generation 和进程 epoch 阻断迟到回写；退出/撤销/删除清缓存，即使清理失败，下次
登录的新 generation 也不会读回旧队列。刷新 bearer 保留登录 generation。

每端最多 1,000 个待处理字段，同一 tuple/字段同时最多一个。先持久提交再乐观展示/发送；
存储失败 HR-SYNC-017，不发修改、不假装已保存。重试保持原操作；ACK 只移除匹配队列项，
不把旧终态行覆盖当前缓存，再拉云端变化。冲突保留本机选择，提供保留云端/重新应用。
前台约 5 秒拉取，Android 每轮至多发送 20 项；网络失败按 5–60 秒退避。大批导入需要多轮，
后台不保证时延；不是推送协议或已承诺的后台实时同步 SLA。

旧本机数据不会自动入队。选择器确认页展示账号、可识别数量、无法识别数、保留原数据及冲突
说明；切换账号使旧确认失效。确认后按稳定迁移摘要/字段生成 opId，base=0，成功记录耐久
保存，重复点击不会重新上传已成功字段。失败/冲突不能标成完成。原本机数据不删除，Legacy
不双写云端。Android 旧 NUL tuple 可精确拆分；Web 旧 slash key 仅在当前实际目录中唯一匹配
才导入，不存在或多个候选保留本地并报告，不能按第一条斜线猜测。

## 验证与发布门禁

自动覆盖真实 PostgreSQL：并发字段 CAS、终态重放、签名 key 轮换、斜线碰撞、有序分页上界、
游标/lease 期限和作用域、保留 floor、500 配额/取消释放、uint64 上限、撤销及删除。
Web IndexedDB 与 Android SQLite 覆盖离线重开、丢 ACK、旧成功不覆盖新取消、提交失败零修改、
账号 generation、显式导入与稳定完成记录、能力关闭保留私有缓存。错误码 012–017 均有双语
及诊断脱敏测试。浏览器脚本和原生 live consumer 只运行隔离 localhost 栈。

```bash
# 先构建根包、web；Playwright 单独安装，不能加入产品依赖。
export HR_WEB_STACK_STATE="$(mktemp -d /tmp/hermes-model-sync.XXXXXX)"
export HR_WEB_STACK_ACCOUNT_MODEL_SYNC=1 HR_WEB_STACK_ACCOUNT_DATA_SYNC=1
export HR_WEB_STACK_PG_BIN=/absolute/path/to/postgresql18/bin
export HR_PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs
./scripts/dev/web-stack.sh up
node scripts/dev/model-sync-smoke.mjs
HR_MODEL_SYNC_BROWSER=webkit node scripts/dev/model-sync-smoke.mjs
./scripts/dev/web-stack.sh down
```

每次完整浏览器冒烟从新测试数据库开始；上例两个浏览器应各自重复 up→smoke→down。
端口覆盖时同时设置 HR_MODEL_SYNC_ORIGIN。故障注入关闭 Service Worker，不能代替 SW/PWA
或真机 Safari 验证。生成原生人工账号 fixture 用 HR_MODEL_SYNC_NATIVE_FIXTURE 指向临时 0600
文件，安装本地 debug/androidTest 包后用 model-sync-android-smoke.py 在指定模拟器消费，
驱动只接受 emulator 序列号、保留现有 reverse 映射，测试用完删除设备 fixture。
不把应用私有 SQLite 重新打开描述为真机进程死亡或完整 UI 验证。

schema20 为红灯变更，PR 检查全绿后仍需所有者批准合并。升版、发布及生产能力启用是独立闸门；
本地 assembleDebug 不是分发凭据。待后续授权时先核对数据库备份恢复与 migration 020，再部署
Gateway、兼容 Web/Android，最后显式启用能力。关闭开关可暂停请求；客户端账号缓存保留。
回退 schema19 二进制需要遵守精确 schema 门禁，不能把关开关当作数据库降级。
P1b 置顶实例身份和完整已读观察版本门禁仍未解决；提示词没有因 P1c 模型偏好实现而交付。

## 本次本地验证记录

2026-10-16，固定基线 origin/main `f69328ad`；所有账号、邮件捕获、数据库和 Mac 都为本轮隔离夹具。

- `npm run test:ci`：879 通过、0 跳过/失败；启用临时 PostgreSQL 18 和网络回环测试，使用已有 OpenSSL 3。
- Web typecheck、935 项测试、build 通过。Chromium 156.0.8078.4 / WebKit 27.2
  各自真实 Gateway/PG 冒烟五项通过：确认导入、独立安装同步、离线重开、丢 ACK 重放、显式冲突及账号隔离。
  这些是桌面引擎，不是手机 Safari；故障注入时关闭 Service Worker。
- Android L1：2527 项 JVM 测试通过、0 失败/错误/跳过，debug 与 androidTest 构建通过。
  三张 Roborazzi 浅色/深色/1.3 字号模型同步图已渲染目检；原生确认、取消、账号改变及源读取失败有 UI 回归。
- Android L3：Pixel_API_37 / emulator-5554，`AccountModelSyncLiveTest` 通过。
  使用生产 AccountApi/SessionManager/EncryptedCredentialStore/SQLite：读取浏览器偏好、原生 Bearer 写入、
  离线持久队列、数据库重新打开、重放与原生退出清理。不是完整 UI 手势、真进程死亡或厂商 ROM 验证。
- Android L2：物理序列号 A5DN9X4708H02629 未授权 USB 调试，未验证，不推断品牌。

模拟器依能力探测给出的 AVD/内存启动；只终止自己启动的 PID，不调用会按通用进程名停止
其他应用 Crashpad 的清理路径。隔离栈和原生会话 fixture 用完清理。没有生产访问、迁移、
升版、正式 APK 交付、发布或能力开启。生产恢复、正式产物及真机/PWA 验收仍是后续门禁。
