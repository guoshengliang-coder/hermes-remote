# HG-216 · P0 同步契约与已读版本验证

日期：2026-10-15；Hermes GO 核对基线 `0870ebd5`。
范围来自 [总方案](ACCOUNT_DATA_SYNC_PLAN.md) 的 P0：可测试的跨端语义、后续 DTO/API 约束及权威内容版本可行性。
**这是实施前的契约基线与测试进程参考模型，尚未接入客户端或服务。**
没有新增 HTTP 路由、能力开关、数据库表、生产日志或新公开错误码，也没有改变现有投递协议。

## 1. 用户已确定的行为

用户决策 · 2026-10-15

用户在本会话批准迭代规划，并对以下三项回复“都按推荐”：

1. 模型先同步收藏与推理偏好；提供商分组展开/折叠留在本端，不新增隐藏模型名单。
2. 首次启用保留可识别的本机未读；其余旧会话建立历史基线，之后的新活动才标未读。
3. 共享撤销或解绑后，相关私人偏好立即隐藏，30 天内清理；重新授权不自动恢复。
   不删除 Mac 会话正文，不影响其他账号/Mac。

其余总方案 §11 的推荐仍是推荐。内部实现与测试选择由代理按仓库规则推进，
未选定的产品行为不写成“用户已经批准”。本 P0 没有新增条目或重新登记派单会话。
HG-216 原文档 PR #590 的开发完成证据保留，后续阶段用评论记录，不能自动退回或复用旧 PR。

## 2. 交付物与执行方式

| 文件 | 用途 |
| --- | --- |
| [v1-fixtures.json](account-data-sync/v1-fixtures.json) | 匿名 JSON 场景；后续 Gateway/Android/Web 可复用同一输入与预期结果 |
| [account-data-sync-model.mjs](../scripts/test/fixtures/account-data-sync-model.mjs) | 仅测试进程可执行规范，覆盖 pin/favorite/read 的高风险语义；不是可部署服务 |
| [account-data-sync-contract.test.mjs](../scripts/test/account-data-sync-contract.test.mjs) | 重放场景、校验边界与反例；由既有 `test:scripts` 自动发现 |

运行：`node --test scripts/test/account-data-sync-contract.test.mjs`。
fixture 的 `specVersion: 1` 是草案版本，不是 `PROTOCOL_VERSION`，也不表示线上支持了同步 V1。
测试中的 actor a/b 表示认证后的不同账号，同一 actor 的 android/web/phone 操作表示其独立客户端。
所有数据是人工构造，域名使用 `.invalid`，没有账号真实内容、Token、私钥或生产数据库快照。

**测试影响审查**：P0 要验证的是接口语义与竞态，不为纯文字添加镜像断言。
通过对跨端操作序列的最终状态断言，防止 toggle、旧请求覆盖、取消后复活、跨账号读取及权限撤销绕过。
参考模型在测试目录；生产代码没有 import，不能把“规范模型测试成功”描述为服务实现或端到端通过。
未来 P1a/P1b 必须让真实实现重放这些向量，并增加 PostgreSQL 并发与 Kotlin/Web DTO 消费测试。

## 3. 认证、作用域与实体 DTO

认证主体提供 `origin/accountId/installationId`；客户端请求不能选择 accountId。
native 用独立 Bearer，Web 用既有 HttpOnly cookie，所有不安全方法继续验证 Origin、Fetch Metadata、CSRF。
Legacy 没有账号同步身份，保持本地。当前 Mac 的 `deviceId` 与客户端 installationId 不是一个东西。

所有客户端缓存、队列、快照与游标先按 `(origin, accountId)` 隔离。
device-scoped 实体还需权限代际 `accessGeneration`；服务端每次 grant/revoke 变更其代际，
旧数据保留在旧代际内，重新授权不再可见。installation 会话有效性是独立检查，不因代际相同而跳过。

| 实体 | wire 标识/内容 | 所属阶段 |
| --- | --- | --- |
| accountProfile | 服务端账号主体；`displayName/avatarRevision`；字段修订 | P1a，复用 account_profiles |
| profileDecoration | `deviceId/profile/profileEpoch`；`displayName/avatarRevision/colorArgb/style` | P1a，和登录账号资料分离；无稳定 profileEpoch 时不能猜同名重建 |
| pin | `kind/deviceId/profile/sessionId/sessionEpoch`，bool value | P1b，参考模型已覆盖 |
| read | 同上、`kind: read`；写请求仅携带 observation，读结果为 readThrough/基线/旧未读/活动版本 | P1b，权威源仍未通过 §8 门禁 |
| favorite | `kind: favorite/provider/model`，bool value | P1c，参考模型已覆盖；不含 Mac 路由 |
| reasoningPreset | `provider/model`，受目标 Mac 能力约束的 effort | P1c，语义字段，不用整数档位跨端猜映射 |
| savedPrompt | 稳定 UUID、title/body、revision/deletedAt | P1c，冲突副本；不按同标题强合并 |

kind、字段集合、空值语义和长度上限在各端必须一致；未知实体类型不能被删改。
参考模型严格解析的子集为 pin/favorite/read，其余实体的具体 DTO/字段配额在各阶段落地时补全，
不能声称本 P0 已验证所有消费端解析器。头像字节仍走独立受限上传，绝不将本机路径或公网 URL当图片内容。
profile 的 default 归一化由受权的上游适配器处理；模型不按空串、大小写或别名猜身份。
模型键采用两个字段，测试覆盖 `a/b + c` 与 `a + b/c` 不碰撞。

revision、baseRevision、accessGeneration、activityVersion/readThrough 均为规范无符号 64-bit 十进制**字符串**：
`0` 或非零开头的十进制，最大 `18446744073709551615`。不接受 JSON number、前导零、负数、科学计数。
客户端以精确整数/十进制比较，不能先转 JS Number；Kotlin 无符号全范围不得直接用有符号 Long。
个人字段更新时间只展示，不用设备时钟决胜。超过版本上限必须拒绝/进入受控新代际，不能回绕。

## 4. 写入请求与结果

以下示例为后续 `/v2/account-data/*` 和 `/v2/web/account-data/*` 领域 payload 草案；接口没有实现。

```json
{
  "opId": "android-operation-uuid",
  "entity": {"kind": "pin", "deviceId": "mac-a", "profile": "default", "sessionId": "conversation-a", "sessionEpoch": "epoch-1"},
  "accessGeneration": "1",
  "baseRevision": "0",
  "value": true
}
```

- mutation 为显式 set/delete/readThrough，不提供 toggle。
- baseRevision 为该实体/字段的最后版本；`0` 仅用于此前不存在的项，不能覆盖已有 false/墓碑。
- opId 为账号和应用环境内的幂等键，重放同一 payload 回同一终态结果；相同 opId 不同 payload 拒绝。
  JSON 对象字段顺序不参与 payload 差异；签名/哈希按规范序列化实现。
- 重放前仍校验有效会话、账号状态、当前设备授权与代际；被撤销后不能返回缓存成功来绕过授权。
- 每操作结果为 accepted/conflict/denied，规范模型还用 op_mismatch/invalid_observation/reset_required 表示内部判定。
  **这些不是用户可见消息或注册 HR 码**；P1a 接入前登记公开错误映射、中英摘要、恢复动作/重试性和脱敏测试。
- conflict 不自动 rebase；用户明确重新设置才生成新 opId 和当前 baseRevision。
- accepted 的幂等重放可能返回比当前云端旧的 revision。客户端不能用旧 ACK 覆盖更新底稿；
  仅移除相应待确认操作，按实体版本取新值，再拉增量。
- 单批有界，逐项终态结果；每项实体写、日志和幂等终态同事务，批内按顺序返回。
  部分成功不能重发新 opId，错误项不能阻塞其他无依赖操作。

取消置顶/收藏的 false 是墓碑而非物理不存在。过期客户端必须重取快照，旧导入标记需要重审，
不能在墓碑清理后以 base=0 复活旧选择。可选草稿/提示词的文字冲突要保留版本，不能用本模型的 bool 策略代替。

## 5. 快照、增量与撤销

服务端以账号内提交次序分配修订并写日志。建议账号行锁串行分配修订，
不能直接拿可能先分配后提交的数据库 sequence 当无遗漏游标。
完整快照固定 snapshotRevision/accessRevision；后续 mutation 只从边界之后增量出现。
分页使用短时、受限的物化快照或等效一致性机制，不能各页重新读最新数据并谎称同一快照。

fixture 的 cursor 明文结构为 `{scope, revision, accessRevision}`，其中 scope 编码 origin/accountId，
只是为了让客户端团队读得懂测试；**生产 cursor 要不透明并防篡改**，绑定认证主体/访问代际、快照、期限。
scope 不能由客户端修改后拿去访问别人的日志。丢失/过期/不匹配游标返回明确 reset_required并重取快照。

nextCursor 按已扫描日志推进，允许 `rows=[] && hasMore=true`；客户端必须继续。
参考模型过滤已撤销 Mac 的历史项，后页仍可返回合法的账号收藏。
生产分页还需测试：多事务乱序提交、并发 revoke、租期到期、分批删除、多节点通知延迟和日志保留边界。

撤销/解绑必须在同一权限事务里：提高 accessRevision 和该账号-Mac 的 accessGeneration，
标记旧代际立即不可见，记录不晚于 30 天的清理期限。接着通知/关闭受影响通道，客户端清旧缓存和待发队列。
依旧靠每请求检查兜底，不能只靠客户端收到通知。
重新授权创建新代际；旧记录、旧观察 proof、旧队列和旧 cursor 都不能恢复。
30 天清理应覆盖旧实体、关联头像引用、变更日志中私人 payload及幂等结果；保留无敏感内容的
最小拒绝旧请求所需元数据可独立定义期限。仅删除实体行但让日志/重放继续返回旧值不算清理完成。
清理任务的真实时钟、数据库级联、失败重试与备份恢复由 P1a 的集成测试验证，本模型无定时器或物理清理证明。

## 6. 首次历史基线与已读合并

首次基线只在账号尚无该会话的同步阅读实体、归属及代际确认、且有权威内容版本时建立。
新手机加入已有账号读取现有云端结果，不在每次登录重新把所有历史标读。

针对每条确定归属的旧会话，保存 `baselineThrough` 与可选 `legacyUnread=true`。
可识别的本机未读保持待阅读状态；其他历史以 baselineThrough 起步，新活动版本大于基线才未读。
旧 token 不能映射到当前账号/Mac/profile/session 时留本机隔离区供选择，不上传到猜测的范围。
同步启用后的其他旧端导入标记必须带该轮迁移版本；若云端已有后续明确阅读，不得导入旧标记使它重新未读。
“没有本机未读”不作用户读过历史的事实声明，建立基线是本次用户明确选择的展示策略。

阅读请求示例（不是客户端自报 `readThrough=999`）：

```json
{
  "opId": "web-read-operation-uuid",
  "entity": {"kind": "read", "deviceId": "mac-a", "profile": "default", "sessionId": "conversation-a", "sessionEpoch": "epoch-1"},
  "accessGeneration": "1",
  "observation": "opaque-server-issued-observation"
}
```

观察凭据必须由受信任来源签发并绑定账号/设备/profile/session/内容epoch/授权代际和所见内容版本。
服务端根据凭据推进 `readThrough = max(old, observed)`，旧请求不能降回去，也不能清除尚未看见的新版本。
legacyUnread 在用户明确观察到基线及之后的内容时清除；无阅读请求不能因为 baselineThrough=0 就自行清除。
确认投递/worker收件/通知点击不等于可见内容已经渲染；已读不清除真实 approval/clarify任务。
live 页面或本地滚动位置仍由本端控制，不因另一端阅读抢滚动。

## 7. 安全与迁移验收清单

| 风险 | 后续实现必须证明 |
| --- | --- |
| 伪造账号、Mac或内容版本 | 服务端主体、Mac访问、权威profile/session、观察签发/验证和代际绑定 |
| 共享owner/grantee混用 | 账号私人偏好分离；共享授权只允许访问指定Mac，不允许读对方偏好 |
| revoke 与旧成功重放竞争 | 权限检查在幂等lookup之前，事务/锁与撤销线性化；旧页面重置 |
| 公用电脑或账号切换留存 | 写者epoch失效、IndexedDB/worker缓存/队列清理；服务端凭据仍HttpOnly |
| 迁移把全局store归错人 | 来源安装+schema+摘要稳定migrationId；逐类确认；无归属记录隔离；墓碑不当空记录 |
| 离线编辑/重试被覆盖 | 本地队列先持久化，baseRevision冲突不隐式覆盖；未知结果重放原opId |
| 诊断泄漏正文 | 不记录prompt/body、搜索、头像字节、cookie/token或完整私有复合键 |
| 数据删除不完整 | 账号删除、共享撤销清理、孤儿头像、日志/幂等记录、备份恢复一起验证 |

本 P0 是部分风险的模型证明，不是认证服务器/CSRF/限流/SQL并发测试。
现有 [账号安全契约](ACCOUNT_MODE_SECURITY.md)、[迁移契约](ACCOUNT_MODE_MIGRATION.md)
继续生效；个人数据迁移与 Connector 凭据迁移不是同一过程。
实施时先新增受控数据层，不更改既有 Hermes 配置和数据库，不把 legacy token hash 当账号。

## 8. 已读版本可行性：当前未通过完整能力门禁

只读核对本机 Hermes 源码 `17b5df02f2`，没有打开或修改用户 state.db，也没有执行上游服务代码：

| 来源/证据 | 结论 |
| --- | --- |
| `hermes_cli/web_routers/sessions.py:get_session_messages` | 返回 session/profile/messages/pagination，没有完整内容revision或快照token |
| `hermes_state_messages.py:set_user_message_content` | 可按现有 row ID 原地修改content；row ID、消息数、timestamp不足以识别变化 |
| `hermes_state_messages.py:replace_messages` | retry/undo/compress会重写/软归档历史；单纯message_count不是递增内容版本 |
| [history-preview](../connector/src/history-preview.ts) | 现有有界最新页投影保留row ID用于取原文，但没有对整个会话修订提供证明 |
| [session-observer](../connector/src/session-observer.ts):lifecycleTransition | 观察运行状态转换；未保证覆盖所有历史编辑及仅在轮询间发生的idle到idle内容变化 |
| [上游契约库存](HERMES_CONTRACT.md) §1b | 原始row ID和客户端映射的h-/u-显示ID不同；不能把后者当跨端版本 |

匿名反例测试证明两组内容不同却具有相同消息数、max row ID、max timestamp、lastActive；
另一个反例保留最新页不变，只修改更旧内容，因此只对最新页hash也不能补成完整证明。
这些是基于已读到的写路径构造的反例，不是对真实用户数据或所有上游版本跑过集成实验。

参考模型的 `observe()` 是**可信观察发行者测试缝**，测试传入版本12/10只证明合并规则。
它没有实现生产签名、当前内容验证、可见性证明或 Hermes 内容观察；不能因为这些测试通过启用 P1b。

后续可靠来源只有满足门禁后才能采用：

1. 首选上游提供与内容快照原子一致的、覆盖 append/edit/rewind/compaction/restore 的修订和epoch，
   并支持不同上游版本能力探测；不能只按版本号假定存在。
2. 若走 Connector 只读观察，需要完整/等效有界的变化检测、串行读取与持久epoch、重启/恢复识别，
   以及与客户端所见快照绑定的证明。**轮询最新页、整段内容裸hash或message_count都不是现成方案**。
   对任意旧页修改又要求完整性时，现有分页接口没有原子整会话快照；单纯分批扫全库也无法保证无竞态，
   且会触发大附件成本，不能作为默认无界轮询。

因此 P1b 完整已读功能目前保持关闭门禁，不能降格为“通知已读”后宣称完整满足。
P1a资料/基础同步与P1c模型/提示词无需等待完整已读。置顶也不依赖完整内容修订，
但仍需可信会话实例身份；2026-10-16 的[源身份实验](ACCOUNT_DATA_SYNC_PIN_IDENTITY.md)
证明当前核对的上游标记不足，P1b置顶仍须先通过独立身份门禁，不能直接把本机键上传。
这是一项具体的上游/观察能力缺口，不需要用户再次批准已经确定的合并语义；新技术证据出现后继续验证。

## 9. P0验证记录与后续门禁

P0聚焦套件覆盖：四组可移植跨端轨迹、精确版本解析、复合键、无权威字段注入、
取消墓碑/旧导入、幂等payload一致性、观察凭据作用域、迟到阅读、首次基线、
快照边界、空页推进、撤销分页失效，以及内容版本反例。
真实端/服务消费还没有接入，Android L1/L2/L3、Web浏览器交互、PostgreSQL并发和真实Mac观察均未运行。
完成记录应附实际命令和结果；不能把本节列出的未来门禁描述为通过。

P0产物只有文档、匿名fixture和测试目录代码，不进入APK、Web bundle、Gateway/Connector构建产物。
没有修改现行 [DESIGN.md](DESIGN.md) 的“仅此设备”运行契约；P1b接入时必须更新文案和设计契约。
没有新增公开错误码，P1a接入HTTP前需完成错误码/DTO消费和安全回归；不能直接复制规范模型当生产服务。
本批可以独立合并，无需发布或版本变更；完整已读门禁仍未通过，其余阶段按已批准规划推进。
