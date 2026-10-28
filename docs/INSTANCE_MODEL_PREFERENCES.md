# HG-240 · Hermes 实例模型偏好

用户决策 · 2026-10-28：收藏和隐藏归属所连接的 Hermes 实例，不同 Mac 隔离。三端共用；旧 Go 收藏保留且确认导入；共享访客不改主人的偏好。

## 存储与边界

上游 Hermes 后端在启动 home 的 `model-preferences.db` 保存 presentation 偏好，不修改会话、模型配置或 state.db。随机 instanceId 跨进程重启保持；每个 home 独立。模型身份是原始 provider/model 二元组。favorite 与 hidden 分别修订；hidden=null 使用该客户端目录默认规则；model 为空仅用于提供商隐藏规则，显式模型规则优先。

`GET /api/model/preferences` 返回 `{version:1,instanceId,revision,rows}`；每行包含 provider/model、favorite、hidden、favoriteRevision/hiddenRevision。包括取消行，修订为 <2^63 的精确十进制字符串；最多 5000 个组合（取消行也占用）。`POST` 仅接受 `{instanceId,provider,model,field,baseRevision,value}`，field 为 favorite 或 hidden。事务比较相应字段版本和实例身份；冲突 409、不重基、不覆盖。hidden 支持 null 恢复默认；favorite 必须 Boolean 且模型非空。无整包替换。

Go 不访问 Hermes 凭据或本地数据库，通过现有 Gateway→Connector 转发。Gateway 增加浏览器精确路径/请求体白名单和 instance-model-preferences 能力；原生 Bearer 显式/隐式路由、Web Cookie 路由都在转发修改前核验设备 owner。共享访客可以读、不能写；不能只靠客户端禁用按钮。

## 客户端与迁移

原生、Web、安卓选择器前台每约 5 秒读取；切换实例清空当前视图，旧异步响应不得回写新实例。先收到服务端成功再展示修改；写入失败（含丢 ACK）保留已确认状态并读回真实状态，不自动重放旧修改。离线时展示最后确认的值与错误，联网后读取恢复。此实现没有持久离线修改队列或后台实时 SLA。

只有 404/405（或旧 Gateway 未声明能力）进入明确的 Legacy 独立模式。网络错误、未知格式、409/5xx 不会降级到本地并假装成功。旧 Go 账号收藏与推理偏好留存；新实例模式只替换收藏/隐藏的显示和写入来源，记住的推理偏好仍走原账号机制。

旧收藏经确认导入到当前实例，原数据保留；已有 favoriteRevision>0 的组合跳过，包括取消标记；首次写入 base=0，竞态仍由 CAS 拒绝。原生导入还包括旧显示/隐藏和提供商隐藏，按实际目录唯一匹配旧复合键，歧义项保留原存储。不跨提供商猜模型，不从原生 localStorage 数据库文件强读。

## 验证及发布

需要上游 Hermes 后端和原生客户端的配套源码改动。Go 源码合并或 APK 构建不能证明已安装的 Hermes 支持新接口。旧版本继续独立管理，并明确提示。上游改动未发布前不得宣布三端线上打通。

覆盖：后端持久化、两个实例隔离、独立字段并发及取消导入；三端消费精确版本；共享访客绕过 UI 的写入拦截；选择器隐藏恢复及切换 Mac 的迟到响应隔离。真机与实际 Hermes 原生界面联调另按 SMOKE_TEST 执行。版本分配和发布需单独授权。

## 2026-10-28 本地验证记录

- Hermes 配套提交以 ee7e0f517232 为基线。完整 scripts/check 13 项通过，0 blocking / 0 advisory；原生模型相关 6 文件、84 项测试通过；类型检查及后端 3 项真实存储、HTTP 认证测试通过。
- Go 构建、Web 类型检查、全量测试和构建通过，另补推理偏好导入回归。已实际查看 390×844 虚构数据预览的浅色、深色布局。
- Android 完整单元测试和本地调试构建通过。首次会话恢复 Compose 等待超时在单独及后续全量复跑中通过，未改该测试或其实现。新增实例偏好、隐藏管理、推理导入重点测试通过。
- Go 全仓测试的 Protocol、Connector、Gateway、Release Server 均通过；scripts 两项旧 legacy recovery 解密测试失败，在未修改主工作区同样复现。系统 OpenSSL 为 LibreSSL 3.3.6，不将本次全仓结果记为全绿。
- Android L1 可用；L2 无实体设备；L3 未安装 emulator。未做真实设备或三套已安装客户端联调，不把 DOM/JVM 验证称为设备验收。
- 未分配版本、发布或修改运行中的 Hermes 安装。上游和 Go 的合并、发布仍需分别核实。

## 合并前 CI 修正

首次 PR CI 的真实 PostgreSQL/Web Cookie 集成测试发现能力列表期望漏加
`instance-model-preferences`，现已同步期望。此前本地未配置测试数据库，该集成测试被跳过，
不能将本地 Gateway 套件通过解释为真实数据库路径已通过；以修正后的 PR CI 为合并门禁。
