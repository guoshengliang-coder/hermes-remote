# Hermes GO Cloud 产品需求与技术架构

状态：V1 产品与架构基线，供评审和后续实施拆解使用。本文不授权生产部署、服务重启、
账号模式启用或现有 Connector 替换。

关联文档：

- `ARCHITECTURE.md`：现有 Relay、Connector 与 Hermes 数据路径；
- `ACCOUNT_MODE_DESIGN.md`：Android/Desktop 账号模式及交互；
- `ACCOUNT_MODE_API.md`：账号、安装实例与 Connector 绑定 API 合同；
- `ACCOUNT_MODE_SECURITY.md`：身份、凭证、威胁模型和安全边界；
- `ACCOUNT_MODE_IMPLEMENTATION_PLAN.md`：账号模式实施阶段；
- `DEPLOYMENT.md`：当前香港服务器部署现状；
- `APP_UPDATE.md`：Android 发布仓库和客户端升级规则；
- `ERROR_HANDLING.md`：跨端结构化错误合同。

## 1. 产品定义

### 1.1 名称

服务端产品名称为 **Hermes GO Cloud**。

内部部署、升级、备份、恢复和诊断能力统称为 **Hermes Cloud Ops**。Cloud Ops 不是面向
终端用户的产品，不在 Android 或 Hermes Go Desktop 中暴露服务器管理权限。

### 1.2 产品定位

Hermes GO Cloud 是封闭运营、由唯一服务提供方部署和维护的轻量云服务，承担两个职责：

1. **安全连接通道**：在 Android 与 Mac 上的 Desktop Connector 之间转发 HTTPS/WSS
   请求和实时事件，保持 Mac 主动出站、Hermes 不暴露公网的安全边界；
2. **账号控制面**：保存用户身份、客户端安装实例、唯一 Mac 绑定、可迁移的轻量配置、
   生命周期游标和必要审计信息，让用户登录、换手机和换 Mac 时可以恢复正确关系。

Hermes GO Cloud 不是聊天内容云存储、文件同步服务、Hermes 托管服务或通用云盘。完整
会话、Prompt、工具输出、Hermes 配置、Hermes 凭证和用户文件仍保留在用户 Mac 上。

未来 Hermes GO 官网将承担产品介绍、注册/登录、套餐购买、订阅管理、账单查询和支持入口。
官网属于商业化控制面，不进入 Android ↔ Gateway ↔ Connector 的实时数据通道，也不改变
Cloud 不保存完整 Hermes 内容的产品边界。

### 1.3 交付模式

V1 只有一方部署和运营，不开源，也不提供用户自助部署：

- 用户使用统一的 Hermes GO Cloud 公网入口；
- 服务提供方维护生产域名、服务器、数据库、证书、备份和升级；
- 客户端通过能力协商使用服务，不选择或管理服务器版本；
- 服务器管理面不开放公网管理 API，运维通过受控 CI/CD、SSH 和内部 `hermesctl` 完成。

架构保留未来“由服务提供方代第三方部署”的参数化边界，但 V1 不承担多发行版、离线安装、
第三方自主升级、License 激活或公开安装文档的成本。

## 2. 产品目标与非目标

### 2.1 V1 目标

- 用户在 Android 和 Hermes Go Desktop 登录同一 Google 账号后，自动发现并连接同一个
  Mac/Hermes，不再以 URL、App Token 或二维码作为默认入口；
- 一个 Hermes GO 账号最多绑定一台活跃 Desktop Connector，多台手机安装实例可独立授权、
  撤销和消费通知；
- 用户换手机后可以恢复账号、Mac 绑定关系和允许同步的轻量偏好，不迁移本机敏感凭证；
- 用户换 Mac 时必须经过最近重新认证和显式替换，旧 Connector 在新绑定提交前继续工作；
- Gateway 只转发实时业务数据，不长期保存聊天正文、流式输出或文件；
- 账号及绑定数据进入 PostgreSQL，具备事务一致性、备份、恢复和迁移能力；
- 服务端可以由内部工具重复安装、版本化部署、健康检查、升级和回滚；
- Android、Desktop、Connector 和 Server 可以独立发布，并通过协议版本与 capabilities
  判断兼容性；
- 所有用户可见故障都能定位到账号、Gateway、Connector、Hermes 或客户端层，并提供稳定
  `HR-*` 错误码和恢复动作。

### 2.2 V1 非目标

- 不托管或同步完整 Hermes 会话内容；
- 不存储用户 Mac 文件、聊天附件或 Hermes 输出文件；
- 不把 Hermes 本地密码、Cookie、Session Token 或 Connector 私钥上传到 Cloud；
- 不支持一个账号绑定多台活跃 Mac 或多个 Hermes 实例；
- 不支持团队、组织、成员邀请、角色和共享 Hermes；
- 不提供 Web 管理控制台；
- 不提供第三方自助部署或自助运维；
- 不要求 Redis、Kafka、Elasticsearch、Kubernetes 或微服务拆分；
- 不承诺 V1 零停机升级，允许在受控窗口内发生短暂 Connector 重连；
- 不在账号模式上线的同一版本删除 Legacy Token 兼容路径。

这里的“Web 管理控制台”特指服务器运维后台。面向用户的官网、账号中心、购买和账单属于明确
的后续产品方向，但不要求与 Relay V1 同期交付。

### 2.3 后续商业化与运营目标

- 提供公开产品官网，展示功能、客户端、套餐、隐私与服务状态；
- 用户使用同一个 Hermes GO 账号进入 Web 账号中心；
- 支持套餐购买、续费、取消、支付失败恢复和账单/发票记录查询；
- 把“账号是否存在”“订阅是否有效”“当前拥有哪些权益”建模为三个独立概念；
- 付费状态通过 entitlement（权益）影响服务额度，不直接修改账号身份和历史数据；
- 支付服务商负责银行卡等敏感支付信息，Hermes GO 不存储卡号、CVV 或完整支付凭据；
- 商业化控制面故障不得中断已经建立的每个实时数据帧，Gateway 使用有时限的权益快照和明确
  的宽限策略；
- 市场、支付、税务和发票能力按实际销售地区接入，不在核心账号或 Relay 中硬编码供应商。
- 提供不采集往来内容的产品统计，覆盖新增、活跃、发送次数、任务时长、连接质量和版本分布；
- 提供只展示聚合数据的内部统计后台，支持时间、平台、版本和渠道维度；
- 支持用户主动提交诊断包，以及在用户授权下自动上报崩溃/严重异常；
- 统计事件、运行监控和诊断日志使用不同 schema、保留周期、访问权限和存储路径。

### 2.4 后续全球加速目标

- 官网、客户端安装包和公开静态资源可以通过 CDN 就近分发；
- Android 与 Desktop 不永久绑定某一台 Gateway 主机，通过稳定 bootstrap/discovery 入口获得
  当前账号/Connector 应使用的区域端点；
- 多区域数据面以低延迟和快速重连为目标，不承诺把一条已经建立的 WebSocket 无缝迁移到另一
  区域；
- 账号、绑定、订阅和权益仍由一致的全局控制面授权，区域 Gateway 不自行创建第二份账号事实；
- 一个 Connector 连接在任一时刻只能由一个区域/节点持有，跨区域恢复必须使用 generation、
  lease/fencing token 防止 split-brain；
- 区域选择以 Android 到区域、区域到 Mac 的实际链路质量为依据，不只根据单端 IP 地理位置；
- 多区域能力按真实用户分布、延迟和可用性数据启用，不为尚未出现的规模提前引入复杂基础设施。

## 3. 用户与核心场景

### 3.1 角色

| 角色 | 需求 | 权限边界 |
| --- | --- | --- |
| 普通用户 | 登录、连接 Mac、换手机、管理本机登录 | 无服务器运维权限 |
| Desktop 用户 | 绑定或替换当前 Mac、查看和撤销手机 | 只能管理自己的账号和绑定 |
| 内部运营人员 | 查看服务状态、发布、回滚、备份、恢复 | 通过受控内部运维通道操作 |
| 开发/发布人员 | 构建、测试并生成签名服务端制品 | 不默认拥有生产 Secret 或数据库访问权 |
| 官网访客 | 查看产品、价格、下载和帮助 | 无账号数据权限 |
| 付费用户 | 购买、管理订阅、查看账单 | 只能访问自己的 billing account |
| 客服/财务人员 | 处理授权范围内的支持或账单问题 | 与服务器运维权限分离并全程审计 |

### 3.2 核心用户旅程

#### 首次建立连接

1. 用户在 Hermes Go Desktop 使用 Google 登录；
2. Cloud 验证 Google 身份并创建或定位内部 `account_id`；
3. Desktop 生成本机 Connector 密钥，提交公钥和绑定申请；
4. Connector 完成密钥持有证明和 Hermes/端到端健康验证；
5. Cloud 原子激活该账号的唯一 Connector 绑定；
6. 用户在 Android 登录同一账号；
7. Android 注册为独立安装实例并自动发现已绑定 Mac；
8. Android 通过 Gateway 与该 Connector 建立 Hermes 连接。

#### 更换手机

1. 新手机使用同一 Google 账号登录；
2. Cloud 创建新的 `phone_installation` 和独立会话；
3. 新手机恢复允许同步的账号偏好并连接现有 Mac；
4. 旧手机保持独立登录，直至用户在 Desktop 或旧手机主动撤销；
5. 撤销旧手机不得影响新手机或 Connector。

#### 更换 Mac

1. 新 Mac 登录已经存在绑定的账号；
2. Cloud 返回绑定冲突，不自动替换旧 Mac；
3. 用户完成最近 Google 重新认证并显式确认替换；
4. 新 Connector 完成密钥持有证明和健康检查；
5. Cloud 在一个事务中激活新绑定并撤销旧机器凭证；
6. 任何提交前失败都保留旧 Connector；
7. 新 Mac 重新配置本地 Hermes 凭证，该凭证不从 Cloud 恢复。

#### 服务升级

1. CI 对候选提交完成构建、测试、安全扫描和制品生成；
2. 内部运维选择明确 Server 版本部署到 staging；
3. staging 通过数据库迁移、兼容、连接和回滚门禁；
4. 生产部署先备份，再启动候选 Gateway 并完成就绪检查；
5. Nginx 切换新上游，旧连接进入排空；
6. 验证失败则自动恢复旧路由和旧程序版本。

## 4. 功能需求

需求编号用于后续拆解和验收，不替代 `ACCOUNT_MODE_API.md` 中的具体协议。

### 4.1 身份与账号

- `HC-ACCOUNT-001` Cloud 必须验证 Google 证明的签名、issuer、audience、有效期和适用的
  nonce，并按 `(provider, issuer, subject)` 映射内部账号；邮箱不得作为授权主键。
- `HC-ACCOUNT-002` Cloud 必须签发自己的短期 Access Token 和轮换 Refresh Token；Google
  Token 不得直接成为 App 或 Connector 凭证。
- `HC-ACCOUNT-003` 每个客户端会话必须可以独立刷新、退出和撤销；Refresh Token 重放必须
  撤销对应 Token family。
- `HC-ACCOUNT-004` Cloud 必须支持当前账号信息读取、账号禁用和全部会话撤销。
- `HC-ACCOUNT-005` 账号删除应进入可审计状态机，立即撤销访问，再按既定保留期清除个人
  资料、头像、安装实例和非必要审计关联。
- `HC-ACCOUNT-006` Google 临时不可用不得中断已授权 Connector 的正常后台重连。

### 4.2 手机安装实例

- `HC-PHONE-001` 每次 Android 安装必须拥有独立、不可由邮箱推导的安装实例 ID。
- `HC-PHONE-002` 每台手机必须拥有独立会话、撤销状态、最后活动时间、生命周期游标和推送
  注册状态。
- `HC-PHONE-003` Desktop 可以列出本账号的手机，并按安装实例撤销；手机只能主动撤销自己。
- `HC-PHONE-004` 撤销手机 A 不得影响手机 B、Desktop 管理会话或 Connector 机器凭证。
- `HC-PHONE-005` 设备名称、平台和 App 版本是展示/诊断元数据，不能参与授权判断。

### 4.3 Desktop Connector 绑定

- `HC-BIND-001` 一个账号最多有一个活跃 Connector 绑定，必须由数据库约束和事务保证。
- `HC-BIND-002` 首次绑定必须先处于 pending，只有密钥持有证明与所需健康检查均通过后才能
  原子激活。
- `HC-BIND-003` 第二台 Mac 不得静默覆盖现有绑定；替换需要最近重新认证、单次确认和完整
  健康验证。
- `HC-BIND-004` Connector 私钥只保存在 Mac；Cloud 只保存公钥、指纹、代次和撤销状态。
- `HC-BIND-005` Connector 日常重连使用机器凭证，不依赖交互式 Google 登录。
- `HC-BIND-006` Connector 替换、解绑和凭证轮换不得修改 Hermes 源码、配置、数据或凭证。

### 4.4 通道与路由

- `HC-RELAY-001` Mac Connector 必须主动建立出站 WSS；Cloud 不要求 Mac 开放公网或局域网
  入站端口。
- `HC-RELAY-002` Gateway 必须按已认证 `account_id` 和 active binding 路由，不得由客户端
  自报账号或设备 ID 决定目标。
- `HC-RELAY-003` REST、WebSocket、文件流和生命周期事件必须保持请求/安装实例所有权，防止
  跨账号和跨安装实例泄漏。
- `HC-RELAY-004` Gateway 只保留完成转发所需的有界内存状态；流式正文、完整消息和文件不得
  进入长期 Cloud 存储。
- `HC-RELAY-005` 请求体、上传、下载、并发请求、逻辑 WebSocket 和超时必须有可配置硬限制。
- `HC-RELAY-006` Connector 断线后客户端收到明确的离线状态；恢复后 Connector 和客户端应
  自动重连，不要求重新登录。
- `HC-RELAY-007` Legacy App Token、Connector Token、`/api/*`、`/api/ws` 和 `/v1/connect`
  在兼容窗口内继续工作，并通过独立功能开关控制新账号路径。

### 4.5 用户资料、头像与配置同步

- `HC-PROFILE-001` Cloud 可以保存昵称、展示邮箱、头像、语言和少量跨设备偏好；这些字段
  不得成为授权依据。
- `HC-PROFILE-002` 头像必须由服务端解码、去元数据、缩放并重新编码为一个标准格式和尺寸；
  V1 不保存原始大图。
- `HC-PROFILE-003` V1 头像建议为最大 `256 x 256` 的 WebP，编码后上限 `256 KiB`，存储在
  独立 PostgreSQL 表的 `bytea` 字段中，并使用内容哈希/版本作为 ETag。
- `HC-PROFILE-004` Google 头像 URL 只能作为导入来源，不能成为永久授权数据或唯一展示依赖；
  下载必须限制域名、重定向、内容类型、字节数和解码像素数。
- `HC-PROFILE-005` 可扩展偏好使用有上限、有 `schemaVersion` 的 `jsonb`；安全和高频查询字段
  使用明确列，不允许无限增长的任意 JSON。
- `HC-PROFILE-006` Mac 本地 Hermes 凭证、Connector 私钥、目录权限、文件路径以及客户端
  Secret 不得为了换机便利进入同步配置。
- `HC-PROFILE-007` 如果未来头像或媒体规模超过 PostgreSQL 的产品边界，API 和数据模型应允许
  将二进制实现迁移到私有对象存储，而不改变账号关系。

### 4.6 生命周期与通知

- `HC-EVENT-001` Cloud 可以持久化 `started`、`waiting`、`resumed`、`completed` 等脱敏生命周期
  事件，不保存 Prompt、assistant delta、工具输出、审批正文、命令或文件路径。
- `HC-EVENT-002` 每个手机安装实例拥有独立 delivery cursor 和 acknowledgement；手机本地
  read/unread 展示状态在 V1 不做云同步。
- `HC-EVENT-003` 事件写入、转发和确认必须幂等；Connector 和手机重连重放不得产生重复通知。
- `HC-EVENT-004` 生命周期事件必须有数量与时间保留上限；过期清理不得阻塞实时通道。

### 4.7 客户端兼容与能力发现

- `HC-COMPAT-001` Server 必须公开非敏感的协议版本、服务端版本和 capabilities。
- `HC-COMPAT-002` 客户端必须按 capability 决定功能可用性，不得通过比较 Server 版本字符串
  猜测行为。
- `HC-COMPAT-003` 发布清单必须声明最低支持的 Android、Desktop、Connector 和协议版本。
- `HC-COMPAT-004` 账号认证、绑定控制面和 Legacy 兼容必须使用相互独立的服务端开关，以便
  分阶段启用和回退。

### 4.8 内部运营能力

- `HC-OPS-001` Cloud Ops 必须支持新主机初始化、指定版本部署、状态查看、诊断、升级、回滚、
  备份和恢复。
- `HC-OPS-002` 部署必须使用不可变、带校验信息的 Server 制品，不得在生产机执行 `git pull`
  或临时修改源码。
- `HC-OPS-003` 所有部署动作必须记录操作人、目标环境、目标版本、源提交、开始/结束时间和结果，
  但不得记录 Secret。
- `HC-OPS-004` 安装和部署命令必须可重复执行；中断后必须能识别当前阶段并安全继续或回滚。
- `HC-OPS-005` 数据库迁移必须显式执行；Gateway 启动不得自动修改 schema。
- `HC-OPS-006` 生产发布必须先经过 staging 的同版本制品和同路径门禁。
- `HC-OPS-007` `doctor` 必须输出分层状态和脱敏诊断包，不输出 Token、Cookie、私钥、完整邮箱、
  请求正文或本机敏感路径。

### 4.9 官网、付费与权益（后续阶段）

- `HC-WEB-001` 公开营销页面和登录后的账号中心必须是不同安全区域；公开页面应可静态化和
  CDN 缓存，账号中心不得被公共缓存。
- `HC-WEB-002` Web 登录复用 Hermes GO 账号身份，但使用 `Secure`、`HttpOnly`、合适
  `SameSite` 属性的服务端会话 Cookie；不得把 Android/Desktop Refresh Token 放入浏览器
  Local Storage。
- `HC-WEB-003` 所有浏览器写操作必须具备 CSRF、防重放、速率限制和重新认证保护；支付、取消
  和设备撤销使用明确确认与幂等键。
- `HC-BILLING-001` 支付服务商 checkout/portal 承担银行卡等敏感数据收集，Hermes GO 只保存
  provider customer/subscription/invoice ID、金额、币种、状态和必要时间戳。
- `HC-BILLING-002` 支付 Webhook 必须验证签名、持久化原始事件 ID、幂等处理并允许乱序和重复
  投递；不得仅凭前端“支付成功”跳转授予权益。
- `HC-BILLING-003` 套餐和价格必须版本化。已售价格不可被直接覆盖，币种金额使用最小货币单位
  整数，不使用浮点数。
- `HC-BILLING-004` 账单页面以支付服务商状态和本地同步记录为依据；V1 可以优先使用服务商托管
  Portal，降低支付安全与合规范围。
- `HC-ENTITLEMENT-001` 账号、订阅和权益分离。Gateway 只消费标准化权益结果，不直接理解支付
  服务商状态或价格 ID。
- `HC-ENTITLEMENT-002` 权益至少能够表达套餐、有效状态、有效期、宽限期、Connector 数量、
  手机安装上限、并发/速率上限和功能开关。
- `HC-ENTITLEMENT-003` 支付失败、Webhook 延迟和 Billing 服务短时不可用必须有明确宽限策略；
  不得在一次数据库或支付接口抖动时立即中断活跃 Connector。
- `HC-ENTITLEMENT-004` 权益降级不得删除用户账号、设备关系或个人资料；只限制新建连接或超出
  套餐的能力，并给用户提供恢复入口。
- `HC-ENTITLEMENT-005` 定时 reconciliation 必须对账支付服务商与本地订阅/权益，修复漏发、
  乱序或人工退款导致的状态偏差。

### 4.10 产品统计（后续阶段）

- `HC-ANALYTICS-001` 产品事件只能使用版本化、白名单字段 schema，禁止任意自由文本属性。
- `HC-ANALYTICS-002` 新增账号、成功登录、安装注册、Connector 绑定和服务端接受消息等关键事件
  优先由服务端产生，避免仅依赖可能丢失或被伪造的客户端上报。
- `HC-ANALYTICS-003` 活跃用户必须由“有意义的产品行为”定义；Token 刷新、轮询、心跳、后台
  重连和更新检查不得计入 DAU/WAU/MAU。
- `HC-ANALYTICS-004` 发送量默认统计服务端接受且按幂等键去重的用户提交次数，不采集消息正文、
  附件名称、Prompt 长度或输出内容。
- `HC-ANALYTICS-005` 使用时长必须拆分为任务运行时长、客户端前台时长和 Connector 在线时长，
  分别标注口径，不得合并为一个含义不清的指标。
- `HC-ANALYTICS-006` 产品事件必须包含随机 event ID、schema version、事件时间、接收时间、来源、
  平台和安全的版本信息；账号维度使用分析专用伪名，不把邮箱/昵称送入分析表。
- `HC-ANALYTICS-007` 重试、离线补发和进程重启必须按 event ID 或业务幂等 ID 去重；迟到事件按
  event time 归属并记录数据新鲜度。
- `HC-ANALYTICS-008` 统计管道不得位于 Relay 同步热路径；统计写入失败不应导致用户消息发送失败。
- `HC-ANALYTICS-009` 产品统计不得作为未来按量计费的权威账本；若引入按量计费，必须建立独立、
  可审计、强一致的 metering ledger。

### 4.11 统计后台与诊断日志上传（后续阶段）

- `HC-ADMIN-001` 统计后台仅供内部授权角色访问，必须具备独立 SSO/MFA、RBAC、审计和会话超时；
  不复用普通用户或服务器 root 身份。
- `HC-ADMIN-002` 默认页面只展示聚合数据，不提供按邮箱、账号或设备浏览用户活动轨迹的能力。
- `HC-ADMIN-003` V1 页面至少展示新增账号、新增安装、DAU/WAU/MAU、发送次数、任务时长、
  Connector 在线率、成功率、错误码、客户端版本、绑定漏斗和留存趋势。
- `HC-ADMIN-004` 所有指标必须显示口径、时区、新鲜度和数据完整性；“新增账号”“新增安装”
  “登录用户”不得混用。
- `HC-DIAG-001` 用户主动上报前，客户端必须本地脱敏并展示将上传的数据类别、时间范围和大小；
  用户确认后才创建诊断报告。
- `HC-DIAG-002` 自动异常上报必须有明确的用户授权/隐私设置，只上传崩溃或严重错误所需的最小
  envelope；支持采样、去重、速率限制和下次启动补传。
- `HC-DIAG-003` 客户端必须在上传前移除 Token、Cookie、Authorization、Google proof、邮箱、
  Prompt、回复内容、工具输出、文件内容和不必要的本机绝对路径；服务端再次执行脱敏检查。
- `HC-DIAG-004` 诊断包使用短期单次上传凭证、大小上限、SHA-256 和压缩炸弹防护；上传后先进入
  quarantine，验证通过才标记为可供支持人员读取。
- `HC-DIAG-005` PostgreSQL 只保存诊断报告元数据、状态、错误码和对象引用；压缩日志包保存到
  私有、加密、短生命周期对象存储，不使用公开 Release Server 目录。
- `HC-DIAG-006` 每次报告生成用户可见的随机 report ID；支持人员按工单/report ID 最小范围查询，
  所有查看、下载和删除操作必须审计。
- `HC-DIAG-007` 自动上传失败不得影响客户端正常功能；退避重试有次数、网络和存储上限，用户可
  随时关闭自动上报并清理待传数据。
- `HC-DIAG-008` 服务端日志、产品统计事件和用户诊断包不得合并成一个无限权限的日志库。

### 4.12 CDN、区域路由与多节点（后续阶段）

- `HC-EDGE-001` CDN 只缓存明确标记为公开且可缓存的官网资源、带内容哈希的静态文件和客户端
  发布制品；账号、账单、Webhook、capability 个性化响应和诊断下载默认不得公共缓存。
- `HC-EDGE-002` APK/DMG 发布必须保持签名 manifest、SHA-256、Range 请求和不可变版本 URL；
  CDN 命中不得绕过客户端现有完整性校验。
- `HC-REGION-001` 客户端必须先访问稳定 bootstrap/discovery 域名，再使用服务端返回的短期、
  签名区域路由结果连接 Gateway；客户端不得把生产区域 hostname 写死在业务代码中。
- `HC-REGION-002` 一个账号的 active Connector binding 必须记录当前 `home_region` 和路由
  `generation`；Android 应连接同一 home region，或通过最近 Edge 代理到该区域。
- `HC-REGION-003` 区域分配只能由服务端根据可用区域、Connector/手机探测、容量和策略决定；
  客户端提交的区域只是测量输入，不能成为越权路由依据。
- `HC-REGION-004` 跨区域迁移必须经过 drain/reconnect 状态机：先建立并验证候选连接，再原子
  提升 generation；旧区域收到更高 fencing token 后必须停止接受新请求。
- `HC-REGION-005` 区域故障切换允许中断在途请求并返回可重试错误，但不得产生两个可路由的
  active Connector；Connector outbox 和幂等 request/event ID 用于重连恢复。
- `HC-REGION-006` 单区域多 Gateway 节点必须使用明确的连接归属机制：粘性/一致性哈希或共享
  Connector directory；不能假设任意节点都持有目标 Connector 的内存 socket。
- `HC-REGION-007` 全局控制面初期使用单写主库和只读副本/缓存，不为低数据量账号业务采用
  无冲突定义的多主写；账号和绑定写操作可以接受跨区域控制面延迟。
- `HC-REGION-008` Edge/区域指标记录 region、PoP、客户端平台和分段 RTT，不把完整 IP、账号
  邮箱或用户内容写入产品统计。

## 5. 数据边界与保留策略

### 5.1 需要持久化的数据

| 数据类别 | 示例 | 建议存储 | 恢复优先级 |
| --- | --- | --- | --- |
| 账号身份 | account、provider subject、状态 | PostgreSQL | 最高 |
| 登录会话 | Refresh hash、token family、撤销状态 | PostgreSQL | 最高 |
| 手机安装 | installation、平台、版本、last seen | PostgreSQL | 最高 |
| Connector 绑定 | 公钥、代次、状态、健康摘要 | PostgreSQL | 最高 |
| 用户资料 | 昵称、展示邮箱、标准化头像 | PostgreSQL | 高 |
| 可同步偏好 | 语言、轻量默认设置、schema version | PostgreSQL | 中 |
| 生命周期事件 | 脱敏状态事件、序号、游标 | PostgreSQL | 中 |
| 审计/幂等 | 安全操作、请求去重 | PostgreSQL | 高 |
| 发布元数据 | 客户端版本、哈希、下载位置 | 发布索引；后续可进入 PostgreSQL | 中 |
| 商业化元数据（后续） | customer、subscription、invoice 引用、entitlement | PostgreSQL | 最高 |
| 产品事件（后续） | 新增、活跃、发送计数、时长、版本 | PostgreSQL analytics schema | 低，可重建汇总 |
| 聚合指标（后续） | 日/周/月指标和漏斗/cohort | PostgreSQL analytics schema | 中 |
| 诊断报告元数据（后续） | report ID、组件、版本、错误码、状态 | PostgreSQL | 中 |
| 诊断日志包（后续） | 脱敏压缩日志、crash envelope | 私有对象存储 | 低、短期 |

### 5.2 只保存在运行内存的数据

- 当前 Connector WebSocket 与账号绑定映射；
- Android 逻辑 WebSocket 和在途请求映射；
- 有界流式缓冲；
- 心跳、临时网络状态和请求计时器；
- V1 单实例限流桶；
- 尚未提交的临时挑战和极短生命周期上下文（安全合同要求持久化的单次凭证除外）。

Gateway 重启可以丢弃这些状态，客户端和 Connector 必须通过自动重连恢复。

### 5.3 禁止进入 Cloud 持久化的数据

- 完整聊天内容、Prompt、assistant delta 和工具输出；
- Hermes 本地用户名、密码、Cookie、Session Token；
- Connector 私钥；
- 用户 Mac 文件、附件正文和输出文件；
- Google 密码、浏览器 Cookie 或长期 Google Access Token；
- 未脱敏诊断、Authorization header 和签名下载 URL。
- 银行卡号、CVV、网银凭据及支付服务商禁止落库的敏感支付数据。
- 产品统计事件中的 Prompt、回复、工具输出、附件名称、文件路径、邮箱、昵称和任意自由文本。

### 5.4 初始保留建议

下列值是 V1 产品默认值，实施时必须做成配置并在隐私说明中保持一致：

| 数据 | 默认保留 |
| --- | --- |
| 活跃账号、绑定与安装实例 | 账号存续期间 |
| 已撤销会话/Token family | 满足重放检测后至少保留其绝对有效期 |
| 生命周期事件 | 30 天或每账号 10,000 条，先到者清理 |
| 安全审计事件 | 180 天 |
| 幂等记录 | 按操作风险保留 24 小时至 30 天 |
| 已替换头像 | 新头像提交成功后延迟 7 天清理 |
| 账号删除数据 | 立即撤销访问，30 天内完成常规清理；法定留存除外 |
| 原始产品事件 | 90 天；账号删除时删除或不可逆匿名化 |
| 日级聚合指标 | 最长 25 个月，且不得反推出单个用户 |
| 用户主动诊断包 | 默认 14 天；关联未结支持工单时最多 30 天 |
| 自动异常 envelope | 默认 30 天；聚合后的匿名错误趋势可更长保留 |

## 6. 技术架构

### 6.1 总体架构

```text
Android                         Hermes Go Desktop
   |                                  |
   | HTTPS/WSS                        | outbound WSS
   +---------------+------------------+
                   |
              Nginx Edge :443
                   |
        +----------+-----------+
        |                      |
  Hermes GO Gateway      Release Server
        |                      |
        |                      +-- APK/DMG + signed manifests
        |
        +-- account/auth modules
        +-- installation/binding modules
        +-- REST/WSS relay
        +-- lifecycle inbox
        +-- capability/version API
        |
    PostgreSQL
        |
  off-host encrypted backup

Desktop Connector --> localhost/private Hermes
```

Google 只参与交互式身份验证，不位于每次业务请求的数据通道中。

### 6.2 商业化演进架构

```text
Public visitor
      |
 Website/CDN ------------------------------ Client downloads
      |
 Web BFF / Control API
      |
      +-- Account/Profile
      +-- Billing integration <---------- Payment provider
      +-- Entitlement service/view              |
      +-- User billing history <---------- signed webhooks
      |
   PostgreSQL
      |
 entitlement snapshot/API
      |
 Hermes GO Gateway ---------------------- Android/Desktop/Connector
```

公开官网、Web BFF、Billing/Entitlement 是控制面；Gateway 是实时数据面。两者可以在早期共用
一个 PostgreSQL 集群，但使用独立 schema/数据库角色和明确模块接口。Gateway 不直接查询支付
服务商，也不在每条流式消息上同步检查订阅。

### 6.3 架构风格

V1 采用 **模块化单体 Gateway + 单 PostgreSQL**：

- 保留现有 `gateway/`、`protocol/`、`connector/` 和 `release-server/` 组件边界；
- 账号、绑定、路由、事件和运维状态在代码中分模块，不拆成网络微服务；
- 所有需要强一致性的账号与绑定写入由同一 PostgreSQL 事务完成；
- Gateway 初期单实例运行，连接状态放内存；
- 只有出现明确的多实例、高可用或容量需求后才引入 Redis/消息系统。

该选择降低部署和恢复复杂度，同时不会阻止未来水平扩展。

当前账号模块可以暂时留在 Gateway 进程内，但新增代码必须通过 account/entitlement 接口访问，
不得让 Relay 路由直接拼接账号、支付或价格表 SQL。官网开始实施时，将 Web BFF/Control API
拆成独立 deployable；该拆分不要求把 PostgreSQL、鉴权或所有模块同时微服务化。

### 6.4 组件职责

#### Nginx Edge

- 唯一公网 HTTPS/WSS 入口；
- TLS、域名、请求体上限、基础限流和 WebSocket upgrade；
- 将 Gateway、健康检查和客户端发布路径路由到私有上游；
- 不执行账号授权，不记录 Authorization header 或请求正文；
- 支持蓝绿上游切换和旧连接排空。

#### Hermes GO Gateway

- Google proof 验证与 Hermes GO Token 签发；
- 账号、安装实例、Connector 绑定和撤销；
- Connector challenge/proof 与 `/v2/connect`；
- account-aware REST/WSS 路由；
- Legacy 兼容入口；
- 生命周期事件持久化和按安装实例消费；
- 用户资料、头像和轻量偏好 API；
- capabilities、版本、liveness 和 readiness；
- 结构化错误、审计和指标。

#### PostgreSQL

- 账号控制面的唯一权威数据源；
- 事务保证一账号一活跃 Connector、单次替换、Token 轮换和跨账号隔离；
- 保存小体量头像 `bytea`，避免 V1 引入额外对象存储；
- 通过显式迁移、定时备份和恢复演练保障数据完整性。

当前本地账号数据库集成门禁已在 PostgreSQL 18 上通过。生产支持版本必须在 Server Release
兼容矩阵中固定，不以“任意 PostgreSQL 均可”作为承诺。

#### Release Server

- 提供经过签名和哈希验证的 Android APK、未来 Desktop DMG 及版本清单；
- 发布制品与私人用户数据使用独立目录、权限和备份策略；
- 不承担账号、Connector 路由或头像访问；
- Cloud Server 自身制品放入私有制品库，不通过公开客户端发布目录暴露。

#### Hermes Cloud Ops

- 内部 CLI/自动化入口；
- 管理部署状态机、版本目录、服务、数据库迁移、Nginx 切换和健康门禁；
- 产生可审计的部署记录和脱敏诊断包；
- 不通过 Android/Desktop 暴露 root 或服务器控制能力。

#### Website 与 Web BFF（后续）

- Marketing Website 提供产品、价格、下载、文档、隐私、条款和服务状态入口；
- Web BFF 处理浏览器会话、CSRF、账号中心和面向用户的聚合 API；
- 官网前端不得直接持有数据库凭证、支付 Secret 或调用内部 Gateway 管理接口；
- 静态营销页面与登录态账号中心使用独立缓存、部署和安全策略；
- 网站故障不应影响已连接的 Android/Desktop/Connector 数据通道。

#### Billing 与 Entitlement（后续）

- Billing Adapter 隔离支付供应商 SDK、Webhook、Checkout 和 Billing Portal；
- Billing Ledger 保存供应商对象引用和本地状态，不自建银行卡处理；
- Entitlement 把套餐/订阅转换为 Gateway 可理解的稳定能力和额度；
- Reconciliation Job 定时核对支付供应商、本地订阅和权益结果；
- 客服退款、赠送期、优惠和人工调整必须产生审计记录，不直接改 Gateway 配置。

### 6.5 推荐代码与运维目录

不立即移动现有组件，新增内部运维层：

```text
ops/
├── hermesctl/
├── manifests/
├── migrations/
├── systemd/
├── nginx/
├── smoke/
├── runbooks/
└── test/
```

官网阶段建议在不搬动现有 Relay 的前提下增加：

```text
web/                 # 官网与登录后账号中心
control-api/         # 浏览器 BFF、账号资料、Billing/Entitlement API
billing/             # provider adapter、webhook、reconciliation
analytics/           # 事件合同、聚合任务、指标口径
admin/               # 内部统计后台，不包含服务器 root 运维
diagnostics/         # 诊断报告元数据、上传授权和清理任务
```

上述目录可以共享协议类型和 PostgreSQL 集群，但必须保持单向依赖：Gateway 消费 entitlement，
Billing 不调用 Relay 内部连接对象，Website 不直连数据库。

后续可逐步把当前 `deploy/` 中通用内容归并到 `ops/`；迁移必须分阶段，避免与正在进行的账号
和 Desktop 改动发生无关的大范围目录变更。

### 6.6 产品统计与诊断上报架构

```text
Android/Desktop/Gateway
        |
  typed analytics events ---------> analytics ingest
        |                                  |
        |                           PostgreSQL raw events
        |                                  |
        |                           aggregation worker
        |                                  |
        |                           daily/hourly aggregates
        |                                  |
        +----------------------------> Internal Stats UI

Android/Desktop
        |
 local redact + consent
        |
 create diagnostic report --------> short-lived upload grant
                                            |
                                   private object storage
                                            |
                                   support access by report ID
```

初期事件量较小时，不引入 Kafka 或 ClickHouse：版本化事件经有界异步批量写入 PostgreSQL 的
`analytics` schema，聚合 worker 生成小时/日级表，统计后台只查询聚合结果。关键账号新增、绑定
和消息接受事件可通过事务 outbox/提交后事件产生；分析失败不回滚业务事务。

当原始事件保留或聚合查询对主数据库造成可测量影响时，再把 analytics ingest 接口后端迁移到
专用列式分析库。客户端事件合同、指标口径和后台 API 保持不变，不因存储迁移重写客户端。

诊断日志与统计事件使用独立通道。统计事件不携带日志；诊断包不自动展开进入全员可查的统计库。
因为压缩日志大小和敏感度均明显高于头像，正式诊断系统引入私有对象存储是合理的新边界。试运行
可以使用受保护本地目录，但必须复用同一 `DiagnosticObjectStore` 接口并设置短期清理。

### 6.7 CDN 与多区域数据面演进

```text
                         Global bootstrap/discovery
                                   |
                     account -> home_region + generation
                                   |
             +---------------------+---------------------+
             |                                           |
        Region HK                                    Region SG/US...
   +----------------+                           +----------------+
   | Edge / LB      |                           | Edge / LB      |
   | Gateway shard  |                           | Gateway shard  |
   | Connector dir  |                           | Connector dir  |
   +----------------+                           +----------------+
             |                                           |
       Connector/Phone                             Connector/Phone

 CDN: Website / JS / CSS / images / APK / DMG / public documentation
 Control plane: Account / Binding / Billing / Entitlement / Region assignment
 Data plane: Regional Gateway / live Connector sockets / REST-WSS relay
```

#### CDN 边界

CDN 优先用于静态、可验证和可缓存内容：官网资源、公开图片、文档、APK/DMG 以及带内容哈希的
前端 bundle。用户头像如需 CDN，使用私有源站和短期签名/鉴权缓存策略。以下请求不进入公共缓存：

- Google proof exchange、Token refresh 和 Web Session；
- 账号、设备、订阅、账单和权益；
- Connector/Android WebSocket；
- 支付 Webhook；
- 诊断报告和日志对象。

部分 CDN/Edge 产品能够代理 WebSocket，但“支持 WSS”不等于降低端到端延迟。是否用于实时通道
必须通过目标地区实际测量其 Android → Edge、Edge → home region、home region → Mac 的总 RTT、
抖动、断线恢复和长连接限制后决定，不能只用静态下载测速代替。

#### 稳定入口与区域发现

客户端只内置稳定 bootstrap 域名和信任根。账号认证或 Connector 启动后，discovery 返回：

```json
{
  "region": "hk",
  "gatewayUrl": "wss://<regional-gateway>/v2/connect",
  "apiBaseUrl": "https://<regional-gateway>",
  "routingGeneration": 12,
  "expiresAt": "<rfc3339>",
  "routingToken": "<short-lived-signed-token>"
}
```

区域 URL 是服务端数据，不写入客户端业务常量。Legacy 客户端仍可继续使用当前固定入口；账号模式
客户端逐步采用 discovery，不在同一版本强制迁移全部连接。

#### Home region 与路由

Connector 是长连接状态的持有者。V1 多区域方案为每个 active binding 选择一个 home region，
Connector 和手机最终都路由到该区域：

- Desktop 首次绑定时测量候选区域 RTT；
- Android 可以上报到候选区域的匿名延迟测量；
- Control plane 结合双方测量、区域健康和容量决定 home region；
- 手机漫游时可先进入最近 Edge，再由服务商骨干/内部链路转到 home region；
- 不为单次网络波动频繁迁移 Connector，使用滞回、最短稳定期和明确故障阈值；
- 迁移只在 Connector 可重连边界发生，不尝试搬运已经建立的 socket 对象。

#### 单区域多节点

Gateway 当前把 Connector socket 保存在进程内，因此增加第二节点时必须显式选择：

1. 通过一致性哈希/粘性路由让同一 binding 的 Connector 与手机落到同一 Gateway shard；或
2. 使用共享 Connector directory 记录 `binding -> region/node/generation`，由入口转发到持有
   socket 的节点。

V1 优先选择简单的区域内 shard 归属，不引入跨节点转发总线。`ConnectorRegistry` 从现在开始
使用接口封装，避免业务模块直接持有全局 Map，为以后替换共享目录保留边界。

#### 区域故障与防双活

一条 WSS 无法在区域故障时无缝搬迁。正确承诺是自动发现、快速重连和幂等恢复：

- Control plane 提升 binding routing generation 并签发新区域路由；
- 新区域只接受匹配当前 generation 的 Connector；
- 旧区域/节点持有较低 fencing token 时拒绝新请求并关闭旧连接；
- 手机重新 discovery，连接当前 home region；
- 已提交的生命周期事件依靠 outbox/event ID 重放；
- 在途非幂等请求明确失败，不做不安全的自动重复执行；
- 客户端展示可恢复连接状态，超过策略后使用稳定 `HR-CONN-*`/未来区域错误码。

#### 控制面数据

账号、绑定、订阅和权益数据量较小但一致性要求高。初期采用一个写入主区域和必要只读副本/缓存：

- 所有绑定 generation 和替换事务在主库提交；
- 区域 Gateway 消费带版本的授权/权益快照；
- 控制面短时不可用时按有界 TTL 继续已有连接，不允许创建无法确认归属的新绑定；
- 不在没有冲突解决模型时采用跨区域多主写；
- 统计事件和诊断上传可以区域接收、异步汇总，不参与账号授权事务。

## 7. 逻辑数据模型

```text
accounts
  1 --- n external_identities
  1 --- n account_sessions
  1 --- n installations
  1 --- 1 active connector_binding
  1 --- n historical connector_bindings
  1 --- 1 account_preferences
  1 --- 0..1 account_avatar
  1 --- n lifecycle_events
  1 --- n audit_events

installations
  1 --- n refresh/token families
  1 --- 1 lifecycle_cursor
  1 --- 0..1 push_registration

connector_bindings
  1 --- n binding challenges
  1 --- n replacement requests
  1 --- n health summaries
```

建议补充或收敛的主要表：

- `accounts`
- `external_identities`
- `account_sessions`
- `installations`
- `refresh_tokens`
- `reauthentication_grants`
- `connector_bindings`
- `connector_replacement_requests`
- `account_preferences`
- `account_avatars`
- `lifecycle_events`
- `lifecycle_cursors`
- `account_audit_events`
- `account_idempotency_records`

商业化阶段新增独立 schema 或明确前缀的表：

- `billing_customers`
- `billing_plans`
- `billing_prices`
- `billing_subscriptions`
- `billing_invoices`
- `billing_webhook_receipts`
- `billing_adjustments`
- `account_entitlements`

财务记录不得用账号删除的级联关系直接清空；个人信息删除、法定账单留存和匿名化需要分别处理。

统计与诊断阶段新增独立 schema 或明确前缀的表：

- `analytics_events`
- `analytics_hourly_metrics`
- `analytics_daily_metrics`
- `analytics_cohort_metrics`
- `analytics_event_outbox`
- `diagnostic_reports`
- `diagnostic_upload_grants`
- `diagnostic_access_audit`

`analytics_events` 使用分析专用伪名，不存邮箱、昵称和自由文本。`diagnostic_reports` 只保存元数据
和私有对象 key，不把压缩日志二进制写入 PostgreSQL。

授权查询始终以认证上下文中的 `account_id` 为起点。对不属于当前账号的资源，返回统一的
not-found 语义，避免通过错误差异枚举其他账号。

## 8. API 与协议边界

### 8.1 公网 API 类别

| 类别 | 路径方向 | 身份 |
| --- | --- | --- |
| Legacy Hermes facade | `/api/*`、`/api/ws` | Legacy App Token |
| Legacy Connector | `/v1/connect` | Legacy Connector Token |
| 账号与安装实例 | `/v2/account`、`/v2/installations/*` | Hermes GO Access Token |
| Connector 绑定 | `/v2/connector-binding*`、`/v2/connect` | 账号会话/Connector proof |
| 用户资料（拟新增） | `/v2/account/profile`、`/v2/account/avatar` | Hermes GO Access Token |
| 能力和版本 | `/v2/capabilities` 与受限版本 endpoint | 不含敏感内部信息 |
| 客户端发布 | `/releases/*` | 按发布渠道策略 |
| 官网公开内容 | `/`、产品/价格/下载页面 | 匿名 |
| Web 账号中心 | Web BFF 下的 account/billing API | 浏览器会话 Cookie |
| 支付 Webhook | 独立 provider webhook 路径 | 供应商签名 + 重放防护 |
| 产品统计事件（拟新增） | `/v2/telemetry/events` | 安装实例/服务端身份 |
| 诊断报告（拟新增） | `/v2/diagnostics/reports*` | 安装实例/Connector 身份 |
| 内部统计后台（拟新增） | 独立 Admin BFF 路径 | 内部 SSO + MFA + RBAC |
| 区域发现（未来） | 稳定 bootstrap/discovery endpoint | 账号/Connector 身份 + 签名结果 |

具体路径、输入、幂等和错误以 `ACCOUNT_MODE_API.md` 为准。本文只定义模块边界。

### 8.2 健康与版本端点

- `/healthz`：进程存活，不依赖全部下游；
- `/readyz`：可接收新流量，验证必要配置、数据库和迁移版本；
- `/relay-health`：保留现有运维兼容，逐步改为脱敏汇总；
- `/v2/capabilities`：客户端所需的非敏感功能标志；
- `/internal/version`：受内部访问控制，返回完整构建和迁移信息。

建议版本响应：

```json
{
  "serverVersion": "0.3.0",
  "protocolVersion": 2,
  "databaseSchemaVersion": 5,
  "sourceCommit": "<commit>",
  "builtAt": "<rfc3339>",
  "capabilities": {
    "accountAuth": true,
    "connectorBinding": true,
    "installationSessions": true,
    "lifecycleInbox": true,
    "legacyAuth": true
  }
}
```

公网响应不得包含数据库地址、主机名、内网 IP、证书路径、Secret 来源或详细依赖版本。

### 8.3 权益检查路径（后续）

```text
Payment webhook/reconciliation
             |
       subscription state
             |
       entitlement projection
             |
    short-lived Gateway cache/snapshot
             |
      authorize new operation
```

实时数据面的原则：

- 登录和绑定仍由账号身份决定，权益决定可用额度和功能；
- Gateway 在建立新会话、注册设备或执行受限操作时检查权益；
- 已建立流式传输不逐帧访问 Billing 数据库或支付供应商；
- 短时控制面故障使用最近有效快照和有界宽限期；
- 欠费后的降级顺序、通知频率和最终停用时间必须由产品策略配置，而不是散落在代码条件中。

### 8.4 统计事件合同（后续）

统计事件采用枚举名称和每事件独立 schema，例如：

```json
{
  "eventId": "<uuid>",
  "eventName": "message_submit_accepted",
  "schemaVersion": 1,
  "occurredAt": "<rfc3339>",
  "source": "gateway",
  "platform": "android",
  "appVersion": "<version>",
  "dimensions": {
    "connectionMode": "account"
  },
  "measurements": {
    "count": 1
  }
}
```

服务端必须按 `eventName + schemaVersion` 验证允许的 dimension 和 measurement。请求携带额外
字符串、消息摘要、文件名、URL、异常栈或其他未声明字段时直接拒绝，不把任意 JSON 原样落库。

初始核心口径：

| 指标 | 权威事件/计算 | 排除项 |
| --- | --- | --- |
| 新增账号 | 首次 committed `account_created` | 重复登录、安装注册 |
| 新增手机 | 首次 committed `installation_registered(kind=phone)` | 重装重放、刷新 Token |
| 新增 Desktop | 首次 Desktop installation 注册 | Connector 每次重连 |
| 活跃账号 | 当日发生至少一个有意义事件的唯一分析伪名 | 心跳、轮询、更新检查、自动重连 |
| 发送次数 | committed `message_submit_accepted` 的唯一业务 ID | 客户端点击重试、传输重放 |
| 任务时长 | accepted 到 terminal lifecycle 的有界差值 | 离线空档需标注 incomplete |
| 前台时长 | 客户端授权后批量上报的有界前台区间 | 后台进程存活时间 |
| Connector 在线时长 | 服务端已验证连接区间 | Hermes 任务运行时长 |

后台必须把“任务时长”“前台时长”“在线时长”分别展示。任何指标口径变更均增加指标版本，
不能悄悄重算后与旧数据拼接。

### 8.5 诊断上报合同（后续）

1. 客户端收集固定时间窗口和白名单日志源；
2. 客户端执行 Token/个人信息/内容/路径脱敏并生成 manifest；
3. 主动上报显示类别、时间范围、压缩后大小和隐私说明；自动异常上报检查授权和采样策略；
4. 客户端创建 report，Cloud 返回随机 report ID 和短期单次上传授权；
5. 客户端上传压缩包并提交 SHA-256、字节数和 manifest 摘要；
6. 服务端进行大小、哈希、归档层级、压缩比、文件类型和二次脱敏检查；
7. 合格对象从 quarantine 标记为 ready，不合格对象删除并返回结构化错误；
8. 用户界面展示 report ID，支持人员只能在关联工单范围内查看；
9. 到期清理对象和元数据，保留不含用户内容的删除审计。

建议单个压缩诊断包初始上限为 10 MiB，日志窗口和上限由客户端类型分别配置。上限是产品策略，
不是允许客户端上传任意文件的通用接口。

### 8.6 区域发现合同（未来）

- discovery 请求和响应独立版本化，旧客户端可以继续使用默认区域；
- 返回的 `region`、URL、generation、过期时间和 routing token 由服务端共同签名/保护；
- routing token 绑定账号/Connector binding、目标区域、generation、用途和有效期，不能跨账号或
  从 Connector 端点改用于手机 API；
- 客户端缓存最后一次有效结果，但在 DNS/连接失败、服务端返回 stale generation 或结果过期时
  重新发现；
- bootstrap 域名必须具备高可用和低 TTL/可控流量切换能力，但不得把数据库或内部拓扑暴露给
  客户端；
- capability 中提前预留 `regionalRouting` 和 discovery protocol version，功能默认关闭；
- 区域新增、迁移和撤销均属于服务端配置/控制面操作，不要求发布新客户端。

## 9. 部署与升级架构

### 9.1 环境

至少维护三个逻辑环境：

- `development`：本地和自动化测试；
- `staging`：使用生产同形拓扑、匿名/测试数据和候选制品；
- `production`：唯一正式服务。

同一发布制品必须从 staging 晋级到 production，不允许生产重新构建。

### 9.2 主机布局

```text
/opt/hermes-go/
├── releases/<server-version>/
├── current -> releases/<active-version>
└── previous -> releases/<rollback-version>

/etc/hermes-go/             # 非密钥配置和 Secret 文件引用
/etc/hermes-go/secrets/     # 0600/最小服务组读取
/var/lib/hermes-go/         # 服务持久状态
/var/backups/hermes-go/     # 本机临时备份，不是唯一备份
```

生产域名、服务用户、路径、端口、TLS 文件、数据库 URL 文件和功能开关必须来自经过 schema
校验的环境配置。`mrlgs.net` 可以是当前生产默认配置，但不能继续作为新代码中的固定常量。

### 9.3 内部命令

```text
hermesctl preflight --config <environment>
hermesctl bootstrap --config <environment>
hermesctl deploy <version> --environment <environment>
hermesctl status --environment <environment>
hermesctl doctor --environment <environment>
hermesctl backup --environment <environment>
hermesctl restore <backup-id> --environment <environment>
hermesctl rollback --environment <environment>
```

V1 只需实现当前受控 Ubuntu、systemd 和 Nginx 适配器。命令与配置模型保持通用，未来如需
Docker Compose 或第三方代部署，可新增适配器而不重写发布、迁移和健康检查状态机。

### 9.4 Server Release 清单

每个服务端版本生成不可变清单：

```json
{
  "serverVersion": "0.3.0",
  "sourceCommit": "<commit>",
  "protocolVersion": 2,
  "databaseSchemaVersion": 5,
  "minimumSourceVersion": "0.2.0",
  "minimumAndroidVersion": "<version>",
  "minimumDesktopVersion": "<version>",
  "minimumConnectorVersion": "<version>",
  "maintenanceRequired": true,
  "rollbackSupported": true,
  "artifacts": []
}
```

清单和制品必须包含 SHA-256；生产发布来源应增加数字签名和构建来源证明。Secret 不得进入
清单、制品或 CI 日志。

### 9.5 蓝绿升级状态机

1. 校验操作权限、目标环境、源版本和目标版本；
2. 下载/上传候选制品并验证哈希和签名；
3. 校验配置 schema、磁盘、证书、数据库连接和备份目标；
4. 创建数据库与配置备份，验证备份文件可读；
5. 执行显式、带锁的向前兼容数据库迁移；
6. 在候选私有端口启动新 Gateway；
7. 检查 liveness、readiness、数据库 schema、账号测试请求、Legacy 兼容和 Connector 测试连接；
8. 原子切换 Nginx 上游；
9. 观察错误率和连接恢复，允许旧 Gateway 在有界时间内排空；
10. 成功后记录 `current`/`previous`，停止旧版本；
11. 任一步骤失败，恢复旧 Nginx 上游和旧程序；数据库只允许通过兼容迁移支持程序回滚，
    不在故障路径盲目执行破坏性 down migration。

V1 可以接受短暂 Connector 重连；发布工具必须验证重连成功，不能只验证进程启动。

## 10. 可观测性与诊断

本节的运行指标用于判断服务是否健康；第 4.10 和 8.4 的产品统计用于理解产品使用。二者可以
共享采集基础设施，但不得共享无边界事件 schema、访问权限或保留策略。

### 10.1 指标

至少采集：

- HTTP 请求量、状态码、延迟和超时；
- 当前 Connector 连接数、重连数、心跳超时；
- Android WebSocket、逻辑 tunnel 和在途请求数；
- 按错误码族聚合的失败数；
- 账号登录、刷新失败、重放检测和限流；
- 绑定申请、成功、冲突、替换和回滚；
- PostgreSQL 连接池、查询延迟、事务失败和迁移版本；
- 生命周期事件积压、投递延迟和清理结果；
- CPU、内存、磁盘、文件描述符、证书剩余时间；
- 部署版本、发布时间和最近备份/恢复演练状态；
- 支付 Webhook 延迟/失败、reconciliation 差异和 entitlement 投影滞后（商业化阶段）。
- 分区域/PoP 的连接数、Android → Edge、Edge → region、region → Connector RTT、抖动、丢包、
  discovery 失败、跨区域转发比例和 routing generation 冲突（多区域阶段）。

指标标签不得包含完整 account ID、邮箱、Token、IP 明文或高基数字段。需要关联时使用短期
相关 ID 或不可逆、定期轮换的聚合标识。

### 10.2 日志与审计

- 应用日志采用结构化格式，包含时间、组件、阶段、错误码和安全的 correlation ID；
- 默认不记录请求/响应正文；
- Authorization、Cookie、Token、OAuth proof、私钥和签名 URL 必须集中脱敏；
- 安全审计与调试日志分离；
- 账号替换、撤销、重新认证、管理员发布和恢复操作必须有审计记录；
- 用户可见失败遵守 `ERROR_HANDLING.md`，原始异常只进入受保护的脱敏技术上下文。

### 10.3 告警

V1 最少告警：

- 公网入口或 readiness 连续失败；
- Connector 总体在线比例异常下降；
- 登录/刷新失败率异常；
- PostgreSQL 不可用、连接池耗尽或磁盘逼近阈值；
- 证书即将过期；
- 最近备份失败或超过恢复点目标；
- 发布后错误率、延迟或重连率超过门禁；
- 生命周期事件积压或清理持续失败；
- 支付 Webhook 验签/积压异常、权益与订阅持续不一致（商业化阶段）。
- 单区域故障、区域间 RTT 异常、Connector directory 不一致、stale generation 或跨区域重连
  失败（多区域阶段）。

### 10.4 内部统计页面（后续）

统计后台建议包含五个视图：

1. **总览**：新增账号、DAU/WAU/MAU、发送次数、任务时长、活跃 Connector、数据新鲜度；
2. **增长与留存**：新增账号/手机/Desktop、次日/7 日/30 日留存、登录到首次发送漏斗；
3. **使用情况**：人均发送次数、任务时长分布、前台时长、在线时长、平台和版本分布；
4. **可靠性**：成功率、错误码趋势、Connector 重连、Gateway 延迟、版本崩溃率；
5. **商业化**：试用、付费转换、续费、取消、宽限和权益分布（商业化阶段）。

所有卡片必须显示：指标定义链接、统计时区、更新时间、数据延迟、采样状态和当前口径版本。
筛选器初期仅支持时间、Android/Desktop、App 版本、发布渠道和连接模式；不提供按用户邮箱、昵称
或完整账号 ID 筛选。支持排障使用独立 report ID 页面，不从聚合图表跳转到用户行为轨迹。

## 11. 安全与隐私

### 11.1 信任边界

- Android、Desktop 管理会话、Connector 和内部运维身份是不同主体；
- Google 只证明用户身份，Hermes GO Cloud 自己做授权；
- Connector 机器认证与交互账号登录分离；
- 所有跨账号资源访问由服务端从认证上下文约束；
- Mac 上 Hermes 凭证永不进入 Cloud；
- 公网 Edge 与 Gateway 内部上游均使用受控网络和最小权限。

### 11.2 Secret 管理

- 生产 Secret 使用独立 `*_FILE` 或后续 Secret Manager 注入；
- 源码、普通环境模板、构建制品和发布清单不得包含真实 Secret；
- 数据库中只保存需要持久化 bearer secret 的 keyed hash；
- Connector 私钥和客户端 Refresh Token 分别存入平台安全存储；
- Token hash key、数据库凭证和发布签名密钥必须可独立轮换；
- CI 构建与生产部署权限分离，普通 PR/CI 不获得生产密钥。

### 11.3 用户数据最小化

- 邮箱只用于展示和必要通知，不作为账号连接键；
- 头像只保存标准化小图，不保存原图和 EXIF；
- last seen 和审计时间只保留产品与安全所需精度；
- 诊断默认隐藏个人信息；
- 删除、导出和保留策略应在账号正式开放前形成用户可理解的隐私说明；
- Web 账号中心、支付供应商、客服和运维必须使用不同身份与权限；不得共享超级管理员凭证；
- 支付页面优先使用服务商托管 Checkout/Portal，减少 Hermes GO 处理敏感支付数据的范围。
- 产品统计使用分析专用伪名和白名单枚举/数值字段；分析密钥与业务数据库凭证分开管理；
- 自动异常上报的开关、采样和保留策略必须对用户可见；关闭后不得继续创建新自动报告；
- 诊断对象独立加密、短期保留、按 report ID 授权，客服不能通过统计后台批量浏览日志；
- 统计和诊断的隐私文案、默认授权和删除行为必须根据实际销售地区单独完成法律评审。

## 12. 非功能需求与服务目标

### 12.1 初始服务目标

| 指标 | 内测/迁移期目标 | 正式服务目标 |
| --- | --- | --- |
| 公网 Gateway 月可用性 | 不低于 99.5% | 不低于 99.9% |
| 账号 API 服务端 p95 | 500 ms 内 | 300 ms 内 |
| Relay 附加 p95 延迟 | 100 ms 内，不含用户网络和 Hermes | 75 ms 内 |
| 故障恢复目标 RTO | 4 小时内 | 2 小时内 |
| 数据恢复点 RPO | 24 小时内 | 1 小时内 |
| 发布失败自动恢复 | 旧路由/程序 10 分钟内恢复 | 5 分钟内恢复 |

这些是发布门禁目标，不是当前已达成声明。正式承诺前必须通过压测、故障演练和连续监控验证。

### 12.2 容量与限制

- 一账号最多一台活跃 Connector；
- 手机安装实例、并发连接、请求速率和事件数量设置可配置上限；
- 头像最大 256 KiB；偏好 JSON 设置严格字节上限；
- 文件流继续使用现有有界分块和背压，不因用户数增加而把完整文件装入 Gateway 内存；
- 上线前用目标机器验证全局 Connector、WebSocket、请求和数据库容量，不能直接沿用开发默认值
  作为生产容量承诺。

### 12.3 备份与恢复

- PostgreSQL 至少每日完整备份；正式目标阶段增加持续 WAL/等价增量能力以达到 RPO；
- 备份必须加密并保存到故障域之外，生产机本地副本不能是唯一备份；
- 配置 schema、非密钥配置和服务版本清单纳入恢复材料；
- Secret 使用独立、受控恢复流程，不复制进普通备份日志；
- 每月至少执行一次自动恢复验证，每季度完成一次从新主机恢复的人工演练；
- 恢复验收必须包括账号登录、Connector 重连、两手机隔离、Lifecycle cursor 和 Legacy 路径。

### 12.4 全球加速启用门禁（未来）

新增 CDN/区域必须由至少 30 天目标用户地区数据证明存在收益，并满足：

- 目标地区端到端 p50/p95 RTT、首帧时间或安装包下载时间达到预先批准的改善目标；
- 长连接稳定性、重连率和错误率不劣于当前默认区域；
- APK/DMG 经 CDN 下载后仍通过大小、哈希、身份和签名验证；
- 账号、账单、诊断和其他私人响应通过 cache-control 与实际缓存测试确认不会被公共缓存；
- 区域断电/隔离演练中不存在两个 active Connector，stale generation 被拒绝；
- bootstrap/discovery 故障时客户端能够使用未过期缓存或明确降级到默认区域；
- 新区域下线和回退不要求紧急发布客户端；
- 分区域监控、容量、成本、值班和数据合规检查已经完成。

## 13. 发布、测试与质量门禁

### 13.1 CI 门禁

- Protocol、Gateway、Connector、Release Server 全量构建与测试；
- PostgreSQL 迁移、事务、并发绑定、Token 轮换和重启测试；
- 跨账号、跨手机安装实例隔离测试；
- Legacy REST/WSS、文件流和生命周期兼容测试；
- Secret 扫描、依赖检查和生成制品哈希；
- 统计事件 schema、未知字段拒绝、重放去重、指标口径版本和无内容属性扫描；
- 诊断包客户端/服务端双重脱敏、归档穿越、压缩炸弹、大小、哈希和到期清理测试；
- `git diff --check` 和文档/配置 schema 一致性检查；
- 候选制品在一次性环境完成 bootstrap、upgrade、rollback、backup、restore 测试。

### 13.2 staging 门禁

- 从上一个生产版本使用正式升级路径升级；
- 真实 Nginx、systemd、PostgreSQL 和 TLS 拓扑检查；
- Android、Desktop、Connector 最低/当前版本兼容；
- Connector 休眠、断网、Gateway 重启和数据库短暂不可用恢复；
- 两手机独立登录与撤销；
- 第二 Mac 冲突及替换失败回滚；
- 生产同路径回滚演练；
- 指标、日志、告警和诊断包脱敏检查；
- 统计离线补发/重复事件/迟到事件、后台聚合一致性和诊断对象越权访问检查。

### 13.3 生产发布门禁

- 明确发布授权、目标版本和变更窗口；
- 最近成功备份和恢复验证未过期；
- staging 使用的是同一不可变制品；
- 数据库迁移和程序回滚兼容性经评审；
- Nginx、旧服务和数据库备份目标已解析并记录；
- 发布后验证公网健康、账号、Connector、Hermes 端到端和客户端发布路径；
- 观察期内未超过错误率、延迟和重连阈值后才完成发布。

## 14. 实施路线

账号功能继续按照 `ACCOUNT_MODE_IMPLEMENTATION_PLAN.md` 的 I1-I6 推进；Cloud 产品化新增以下
并行工作流：

| 阶段 | 范围 | 主要产物 | 退出条件 |
| --- | --- | --- | --- |
| C0 | 产品与合同冻结 | 本文、Server 版本和配置 schema | 范围、数据边界、SLO 评审通过 |
| C1 | 统一 Server Release | 构建制品、manifest、版本/capability API | 任意环境使用同一制品启动 |
| C2 | 内部安装与诊断 | `preflight/bootstrap/status/doctor` | 新 staging 主机可重复安装并通过 smoke |
| C3 | 安全升级与回滚 | `deploy/rollback`、迁移锁、蓝绿切换 | 注入每个失败点均能恢复旧服务 |
| C4 | 数据收敛 | Lifecycle JSON 迁 PostgreSQL、头像/偏好 | PostgreSQL 成为控制面唯一权威存储 |
| C5 | 备份与可观测性 | off-host 备份、告警、运维诊断包 | 达到内测 RPO/RTO 并完成恢复演练 |
| C6 | 分阶段生产启用 | account/binding capability 灰度 | 两手机、第二 Mac、重启和回滚门禁通过 |
| C7 | 产品统计与诊断 | Analytics schema/worker、统计后台、诊断上报 | 指标口径、无内容审计、权限、上传和清理通过 |
| C8 | 官网与商业化控制面 | Website、Web BFF、Billing Adapter、Entitlement | 购买、续费、取消、账单、Webhook 与宽限策略通过 |
| C9 | CDN 与区域化 | CDN、discovery、home region、节点归属和故障迁移 | 延迟收益、缓存安全、无双活和回退门禁通过 |

不建议在 C0-C7 中引入 Kubernetes、公开自建版或服务器运维后台。C8 的用户官网不拥有服务器
运维权限。出现多节点容量、区域级可用性或
真实第三方交付需求后再立项。

## 15. V1 验收标准

产品和架构 V1 完成必须同时满足：

1. Android 和 Desktop 使用同一 Google 账号可发现同一绑定，无需默认输入 URL/Token；
2. 一个账号的一台活跃 Connector 约束在并发竞争下仍成立；
3. 两台真实手机可以独立登录、接收事件、退出和撤销；
4. 换手机恢复安全资料和允许同步的偏好，不获得 Mac 本地 Secret；
5. 第二台 Mac 未确认前不能影响旧 Connector，失败替换自动保留旧绑定；
6. Gateway/数据库重启后 Connector 和客户端自动恢复；
7. Cloud 不持久化完整聊天内容、流式正文、Hermes 凭证和用户文件；
8. 生命周期数据从 JSON 文件迁至 PostgreSQL，并按安装实例隔离游标；
9. 标准化头像可保存、缓存、更新和删除，原图/EXIF 不进入持久存储；
10. 全新 staging 主机可由内部工具重复 bootstrap，无需手工改源码；
11. 从上一个稳定 Server 版本升级成功，且每个受测失败点均能恢复旧路由和程序；
12. PostgreSQL 备份可以在新环境恢复，并通过账号、绑定和端到端 smoke；
13. capability 与兼容矩阵允许 Server、Android、Desktop、Connector 独立发布；
14. 日志、错误、指标和诊断包通过 Secret/个人信息脱敏检查；
15. 当前 Legacy 客户端在迁移窗口内保持兼容；
16. 生产启用和部署仍需独立、明确授权，不由代码合并自动触发。

### 15.1 C7 产品统计与诊断验收

1. 固定测试数据下，新增账号、DAU/WAU/MAU、发送次数和三类时长结果符合版本化口径；
2. 重试、离线补发、重复 Webhook/事件和服务重启不会重复计数；
3. 统计事件 schema 拒绝 Prompt、回复、附件名、文件路径、邮箱和任意自由文本；
4. 统计后台默认只能查询聚合结果，未授权角色无法打开页面或导出数据；
5. 页面明确显示统计时区、更新时间、数据延迟、采样和口径版本；
6. 用户主动上报能在发送前看到类别/范围/大小，取消后不创建远端报告；
7. 自动异常上报遵守用户开关、采样、去重、速率和本地空间限制；
8. 包含测试 Token、Cookie、邮箱、消息内容和绝对路径的诊断包在客户端被清理或服务端拒绝；
9. report ID 不能枚举或跨账号读取，支持人员每次查看/下载/删除均产生审计；
10. 到期诊断对象按策略清理，统计或诊断服务不可用不影响登录、绑定和消息转发。

### 15.2 C9 CDN 与区域化验收

1. 官网静态资源和版本化客户端制品正确命中 CDN，登录态/账单/诊断响应不会被公共缓存；
2. CDN 返回的 APK/DMG 在完整下载和 Range 恢复后均通过客户端现有校验；
3. 新旧客户端分别通过 discovery 和 Legacy 默认入口正常连接；
4. Connector 与同账号手机最终路由到同一 home region，跨账号 routing token 无效；
5. 增加、迁移或撤销区域不需要发布新客户端；
6. 节点/区域故障演练中，旧 generation 被 fencing，任意时刻不存在两个可路由 Connector；
7. 在途请求失败具有明确可重试性，生命周期 outbox 重放不产生重复事件；
8. bootstrap 暂时不可用时，未过期缓存可用；缓存过期后安全失败，不接受伪造区域 URL；
9. 目标地区实际 RTT、首帧、稳定性和下载指标达到 C9 预先批准的上线门槛；
10. 分区域指标和诊断不包含用户内容、完整 IP、邮箱或其他不必要个人信息。

## 16. 延后决策

以下议题不应阻塞 V1，但架构评审时必须确认没有被当前实现封死：

- 由服务提供方代第三方部署的专属实例；
- Docker Compose 部署适配器；
- 面向头像/业务媒体的通用对象存储和 CDN；
- Gateway 多实例与共享连接目录；
- Redis 限流/临时状态；
- 多区域入口和数据库高可用；
- 团队/组织账号；
- 用户数据导出自动化；
- 离线环境、私有 CA 和第三方自主运维；
- 服务器运维 Web 后台（内部统计后台已列入 C7）。

官网、统计/诊断和商业化控制面不属于延后决策：它们是已知后续方向；仍需在 C8 立项前确定销售地区、
支付供应商、订阅/买断模式、退款、税务、发票、试用、优惠和欠费宽限规则。

CDN 和多区域也是已知后续方向，但启用地区、Edge/CDN 供应商、home-region 算法、跨区域骨干、
数据库只读副本和容灾等级必须由真实用户分布与链路测量决定，不能在 V1 预先锁定供应商。

未来引入上述能力时，应复用本文件定义的不可变制品、配置 schema、显式迁移、健康门禁、
数据最小化和账号授权边界，而不是为新部署模式重写 Gateway 核心业务。

## 17. 技术债务控制线

未来业务扩展本身不会必然造成重写；真正的技术债来自边界被破坏。以下规则从现在开始执行：

1. **账号不等于订阅**：账号停用、订阅失效和 Connector 离线必须是不同状态；
2. **支付不进入 Relay 热路径**：Gateway 不调用支付供应商，不逐帧查询权益；
3. **控制面不操作连接对象**：Website/Billing 通过稳定接口或权益投影影响 Gateway；
4. **数据库可共用、schema/角色不混用**：早期避免多数据库运维，保留未来物理拆分能力；
5. **浏览器与客户端凭证分离**：Web Cookie、Android/Desktop Token、Connector 机器凭证各自撤销；
6. **价格和财务事件不可变**：金额使用整数，Webhook 幂等，已售价格版本不可覆盖；
7. **用户数据和法定账单分开删除**：账号删除不能错误删除必须留存的财务凭证，也不能借留存
   名义无限保留非必要个人数据；
8. **官网与数据通道独立发布**：官网故障或营销改版不应重启 Gateway；
9. **先模块化、后按压力拆服务**：不要现在为“未来可能”引入微服务，也不要继续扩张单个
   `gateway/src/index.ts` 的职责；
10. **所有外部供应商使用 Adapter**：Google、支付、邮件、对象存储和未来税务/发票服务都不能
    把专有 ID 和状态机泄漏到核心授权模型。
11. **统计事件必须强类型**：禁止把任意 `properties`、日志文本或异常栈直接写入分析表；
12. **指标口径必须版本化**：新增、活跃、发送和时长的定义变化必须可追溯，不能只改 SQL；
13. **诊断日志不是产品分析**：诊断包独立授权和短期存储，不能被当成用户行为数据源；
14. **分析不阻塞核心行为**：统计后端故障不能导致登录、绑定或消息转发失败；未来按量计费另建
    强一致 metering ledger。
15. **CDN 不等于实时加速**：静态资源与 WSS 分开评估，不能因供应商支持 WebSocket 就默认接入；
16. **客户端不硬编码区域**：只固定稳定 bootstrap 信任入口，区域端点和 generation 来自签名发现；
17. **连接归属必须可替换**：业务逻辑依赖 `ConnectorRegistry` 接口，不直接依赖单进程 Map；
18. **多区域先防双活**：所有迁移/故障切换使用 binding generation 和 fencing，不能依赖 DNS
    最终一致性保证唯一 Connector；
19. **控制面优先单写一致性**：在业务确实需要前不引入多主数据库，区域数据面通过授权快照扩展。

只要守住这些控制线，当前“Gateway + PostgreSQL”的简单方案可以支撑账号阶段；官网到来时
新增 Web BFF、Billing 和 Entitlement，而无需重写 Connector、Relay 协议或 Android/Desktop
主链路。
