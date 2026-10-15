# HG-216 · P1a 账号资料同步

基线：P0 `34f9388d`；本阶段实现 accountProfile 纵向链路。默认关闭，尚未发布或在生产迁移。
总方案与 P0 的其他实体没有因此获得线上能力。

## 交付边界

- Gateway 复用 `account_profiles` 的名字/头像，不复制账号资料权威源。
- Web 账号页编辑名字/头像；前台每 30 秒、重新可见及网络恢复时拉取变化。
- Android 前台每 30 秒消费云端名字/头像，更新账号详情卡；名称/修订进原加密账号存储，
  图片进独立应用私有 `account-profile-cache`。本阶段原生只读，没有新增未经持久化的编辑入口。
- Desktop/Connector 不消费该可选能力，既有认证和 Hermes 路由不变；共享 Mac 不共享账号资料。
- `profileDecoration` 仍等待可信 profileEpoch：同名身份删除重建不能猜成同一实体。
  P1b 完整已读的权威内容版本门禁仍未通过。置顶、模型、提示词等继续按后续阶段推进。

这是一项账号资料专用能力，不是已经覆盖总方案全部实体的通用同步引擎。

## 能力与协议

新增 `ACCOUNT_DATA_SYNC_ENABLED=0`。R4 环境工具保留显式配置的 0/1 开关；旧环境不自动追加或启用。开启且账号服务启用时，`GET /v2/capabilities` 提供：

```json
{"accountAuth":{"accountDataSync":{"version":1,"entities":["accountProfile"]}}}
```

客户端必须同时匹配 version=1 与实体名；缺失/未知时维持原行为。
Native 使用独立 Bearer；Web 使用原 HttpOnly Cookie，写请求继续验证 Origin、Fetch Metadata、CSRF。
Web 新请求必须带 `X-Hermes-Expected-Account`；`img` 的头像 URL 用 owner 查询参数承载同一断言。
它是认证主体断言，不能选择别人账号。头像 GET 在同一事务核对 rev 后读取字节，旧 URL 不返回其他修订图片。
Cookie 在另一标签页切换时，旧账号队列因断言不匹配而拒绝，不能写进新账号。

| Native 路径 | Web 路径 | 行为 |
| --- | --- | --- |
| GET `/v2/account-data/snapshot` | GET `/v2/web/account-data/snapshot` | 一个账号实体的完整快照 |
| GET `/v2/account-data/changes?cursor=…` | GET `/v2/web/account-data/changes?cursor=…` | 返回当前实体或 profile=null；无分页 |
| POST `/v2/account-data/mutations` | POST `/v2/web/account-data/mutations` | 显式设置名字，CAS + 幂等 |
| GET/PUT `/v2/account-data/avatar` | GET/PUT `/v2/web/account-data/avatar` | 独立受限图片读写，认证后 private/no-store |

快照包含 version/revision/cursor/hasMore=false，以及 profile（名字字段修订、头像字段修订、account）。
修订全部为精确 uint64 十进制字符串。Android 用 BigInteger 比较，Web 用 BigInt；不得经 JS Number/Long。
无头像时 avatarRevision="0" 且无 avatarUrl。有头像的 URL 指向相应认证端点，不能携带凭据。

名字请求 `{opId, issuedAt, baseRevision, displayName}`，拒绝未知字段；opId 为 UUID。
issuedAt 为操作创建时间（允许未来最多 5 分钟，有效 90 天）；过期操作不得自动重建成新操作。
名字 trim 后 1–40 UTF-16 单元、无控制字符。baseRevision 是**名字字段**修订。
头像以原始 PNG/JPEG/WebP 字节上传，≤4 MiB 且签名匹配；头为 Idempotency-Key、
X-Hermes-Operation-Issued-At、If-Match（带双引号的**头像字段**修订）。

成功仅返回 accepted 与三项修订，不返回存储的旧资料。客户端再拉当前快照，迟到 ACK 不覆盖更新值。
同一 opId/规范语义 payload 返回同一终态；换 payload 用 `HR-ACCOUNT-005` 拒绝。
冲突返回 `HR-SYNC-006`；操作终态与实体变更同事务，不能以新 opId 偷偷重放冲突。

## 持久化、一致性和保留

迁移 019（schema 18→19）给原表增加 numeric(20,0) 的整体及两个字段修订。
数据库 trigger 捕获新接口和旧 Web 接口的实际字段变化；名字与头像独立 CAS，不同字段不互相冲突。
无变化不加修订；上限由数据库约束拒绝，不能回绕。初始名字从认证身份/邮箱默认值持久化一次。

新表 `account_profile_sync_operations` 按账号/opId 唯一，保存 HMAC 请求摘要与终态修订，
不保存名字、邮箱、头像字节或成功响应正文。每账号最多 10,000 个有效操作；超过配额拒绝，不部分提交。
保留到 issuedAt+90 天，既有有界 retention 任务清理过期项。账号删除期限后的批处理也清理
账号资料（包含原有头像 bytea）与操作记录，清完前不标记匿名化完成；父账号物理删除亦级联。

每次读/写在事务中锁账号、会话、安装实例与资料行；核对当前账号 active、会话/安装未撤销、
访问未过期，然后才查询幂等结果。账号退出/删除不能借旧成功重放读取资料。

游标为 HMAC 防篡改的不透明值，绑定账号、修订与 24 小时租期；过期、越界、跨账号或篡改用
`HR-SYNC-007`，客户端取完整快照。当前只有一个实体，增量为当前状态合并，不是多实体变更日志。
增加实体前必须另行设计一致性日志/分页/权限代际并扩展能力，不能复用本游标冒称通用日志。

## 客户端恢复与隐私

Web IndexedDB 按 origin/accountId 存快照及最多一个待同步修改（头像为 Blob）。先持久化才发请求；
存储失败显示 `HR-SYNC-011`，不发送。操作未确认时禁止重复编辑（`HR-SYNC-010`）。
离线/5xx/丢失 ACK 沿用原 opId、issuedAt、baseRevision；4xx 终态不自动重试，429 等待恢复。
冲突保留终态供查看，显示云端当前名字并保留正在输入的文字；用户明确再次保存才创建新操作。

名字的 baseRevision 在开始编辑时捕获，裁剪头像前捕获头像修订。后台拉到新值不得隐式 rebase。
退出时先失效写者，再清队列/快照；持久 logout 代际与跨标签页信号阻止迟到写回和旧队列复活。
每次服务端请求仍以实际 Cookie 主体及 expected-account 断言兜底，不靠缓存认证。
Service Worker 继续只缓存 app shell/静态资源，不缓存任何 `/v2` 响应。

Android 不把私有下载交给带 Legacy 凭据的图片加载器；仅向固定账号路径发送当前 Bearer，响应有界。
下载返回后再次检查 origin/account/installation 和本地登录代际；与退出/切换/删除的提交互斥。
头像目录与 Hermes 身份的 `files/avatars` 分开，退出不删除 Hermes 身份装饰。

## 测试影响与发布门禁

本阶段重点回归：真实 PostgreSQL 并发 CAS、独立字段、旧写入触发修订、重启重放、冲突终态、
撤销后拒绝重放、清理资料/头像；Cookie/Bearer 分离、CSRF、账号断言和错误响应脱敏；
Web 队列先落盘、失去 ACK、重启恢复、冲突不重试、存储失败不发送及退出失效；
Android DTO 精确修订、能力缺失兼容、资料更新保留 Mac 选择、退出后的迟到响应以及账号卡 L1 截图。
错误码 HR-SYNC-006..011 的中英摘要/恢复/重试性见 ERROR_HANDLING；010/011 仅 Web 队列使用。

运行记录在 PR 和 MissionGo 完成评论附实际结果。L2 手机目前 unauthorized，不能声称真机通过。
没有 APK 交付、版本分配或生产迁移。原 HG-216 文档 PR 的开发完成证据和状态不覆盖、不重开。

本改动涉及 Cloud/Android/Web，正式发布至少需要 Server、Web、Android 对应产物核验。
合并需通过所有 PR/main 检查并按 INTEGRATION 红灯（release-contract schema）处理；
发布仍需独立授权并先定版本。先部署兼容 Gateway/schema19（开关保持关闭），再发兼容客户端，
经过跨端验证后才单独授权启用能力。旧接口继续可用，minimumClients/protocol 不提高。
回退优先关闭能力和 Web 静态包回滚；schema19 不能直接改回18标记或手工删表来回滚旧 Gateway，
须走既有 R4 受控数据库恢复/roll-forward 流程。部署历史不提前写成已发生。

本次本机验证（2026-10-15）：`npm run build` 成功；`npm test` 在隔离 PostgreSQL 18.6
数据库并开启 RUN_NETWORK_TESTS=1 运行，857 通过、0 跳过。Web `npm ci --ignore-scripts`、
`typecheck`、全套 912 项测试及 build 成功。Android `:app:testDebugUnitTest :app:assembleDebug`
成功，XML 汇总 2512 项、0 失败/错误/跳过；新账号卡另以精确测试选择渲染浅色/应用深色
fontScale 1.3 截图并目检。L2 探测到手机 A5DN9X4708H02629 未授权 USB 调试，未进行真机验证；
L3 Pixel_API_37 可用，本改动没有 targetSdk 门控行为，未运行模拟器。未进行真实跨端浏览器/手机联调、
生产恢复演练或部署；这些仍是发布启用前的验收门禁。
