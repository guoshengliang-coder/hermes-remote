# Hermes 兼容管理控制面合同

对应 HC-03。本文冻结首版实现边界；接口尚未部署，不能据本文授予权限或认为版本已经受支持。

## 授权和所有权

兼容管理角色绑定 `accounts.id`，不绑定客户端邮箱、设备 owner 或 installation。每次管理请求先验证现有账号 session，再查询数据库中的有效角色；修改在同一事务内重新验证 session、账号和角色。角色撤销与修改共用兼容管理事务锁，撤销提交之后旧 session 的新管理操作被拒绝。

初始授予工具要求显式 account ID 和期望的已验证邮箱；二者必须在服务端现有 `external_identities` 中匹配且账号有效。邮箱仅用于核对授予对象，不能持续自动授予角色。授予、撤销写审计；Desktop 不提供自行提权 API。首次生产授予仍是单独的受控启用步骤。

默认关闭 `ACCOUNT_HERMES_COMPATIBILITY_ENABLED`。关闭时不显示管理入口，保留本机已有版本检查。启用后 `GET /v2/hermes-compatibility/access` 对已登录账号返回是否有管理权限；其他 `/v2/hermes-compatibility/*` 管理路由逐请求鉴权。Android/Web 没有新增调用，原有账号和设备合同保持兼容。

## 固定身份与证据

候选仅来自官方稳定 release，保存版本、tag、tag 对象及解引用 commit。相同 tag 指向发生变化时记录异常，不能原地替换已验证目标。

验证输入固定：候选 ID、源 Hermes commit、目标 commit、GO 源 commit、测试套件摘要、平台/架构/系统版本、安装路径类型和组件产物组合。首版不接受 shell、任意仓库、路径、workflow 名称或脚本参数。

测试报告分开保存基础设施失败、检查失败和未执行。通过的检查必须有证据摘要；报告与 run 的固定输入完全匹配。结果只能由受信验证执行边界提交，管理员 API 不能填写检查结果。每个 run 最多接受一份最终报告，重复相同结果幂等，不同结果冲突。

首版核心验收项：安装身份、鉴权、REST、RPC、历史、配置、Connector、服务恢复、升级、恢复、外部更新互斥、进程中断、Desktop 退出重开、客户端核心业务和新版数据保护。所有项都通过才可审批。某项未执行仍是未验证，不能用“有限支持”绕过。范围扩展必须新建验证输入并重验。

组件组合使用已测试的精确版本及产物摘要，首版不推断未来版本兼容。源 commit、架构、系统和安装类型也精确匹配。已安装更高 Hermes 不自动降级。

## 状态与并发

候选、验证任务、决策、发布快照分别保存。验证任务状态为 queued/running/passed/failed/infrastructure_failed/cancelled；审批不修改任务状态。决策绑定固定报告摘要和范围摘要，不能在审批后修改内容。

所有管理副作用需要 UUID 操作 ID 和预期修订号。服务端在一个事务内校验权限、操作摘要、修订号、业务门禁，写入结果和审计。同一操作 ID、相同输入返回原结果；不同输入拒绝；旧修订拒绝，不自动覆盖。

批准产生待发布决策；发布服务拿到审批快照后再次校验证据。签名系统与运行上游代码的验证系统分离。公网回读验签和内容摘要成功后才标为 published。撤回产生更高修订的快照，不修改旧快照、不强制降级已安装版本。

## 推荐消费

兼容清单是独立的 Ed25519 签名 envelope，复用已有 payload/keyId/algorithm/signature 形式，不混用 app 或组件更新清单。签名载荷必须包含专用类型、schema、单调修订、有效期和精确决策记录，防止跨用途替换。

客户端先验签、检查类型/修订/期限，再排除撤回目标并匹配固定环境与组件组合。开始变更前重新取有效清单；网络不可用时可查看缓存，但不能新启动升级。目标升版排序只在已经通过所有匹配条件的候选间进行。

I3 载荷使用 `purpose=hermes-go.compatibility.recommendations.v1`，绑定 HTTPS origin、最多 100 条精确决策、24 小时有效期和单调 revision。准备发布在一个事务内重新验证通过报告、组件证据和候选身份，保存不可变未签名载荷及 pending 任务。受保护工作流签名回传后，公开 `snapshots/:revision` 回读验签/摘要通过且决策和组件证据仍有效，事务才更新 published。`recommendations` 只返回已发布快照。撤回立即禁止提供含该决策的旧快照；随后发布更高修订的清单移除它，不改写旧签名字节。

签名复用 `.github/workflows/hermes-compatibility-publish.yml` 中现有 `desktop-release` 环境及审批门禁；Gateway、Desktop 和验证 job 都不配置签名私钥。工作流先检查现有 Desktop 签名信任连续性，再用专用 OIDC 身份领取固定 task；私钥只注入最后签名步骤。服务端必须核实发布 workflow、main 精确 commit、task UUID、`desktop-release` environment/sub 和首次 run attempt；验证 workflow 不能冒充发布 workflow。成功派发后等待环境审批，不重复派发；24 小时未完成或三次派发失败记为发布失败。

Gateway 的 `ACCOUNT_HERMES_COMPATIBILITY_PUBLICATION_CONFIG_FILE` 只包含 schemaVersion=1、origin、goCommit、signingKeys/releaseKeys（id/publicKeyPem）和 inventoryFile，不含私钥。组件发布证据是另一用途 `hermes-go.compatibility.released-components.v1` 的受信签名清单，包含 origin、issuedAt/expiresAt 和精确 combinations；不能拿推荐清单替代，不能由管理员直接填写。该证据的真实发布流水线产出与续期仍属 I3/I5 待联调项。

Web 组件的 `artifactSha256` 固定为 `package-web-app.mjs` 生成的原始 tar.gz 摘要，不能用首页、Git commit 或重新压缩后的文件替代。`hermes-compatibility-web-evidence.mjs` 接收发布流水线保留的包、清单和待验证的精确组件身份，先核对归档及每个条目，再从受信部署 origin 的 `/app/` 回读全部文件并复核首页。输出是未签名的时间点观察，不是发布权限、兼容批准或未来持续有效的保证；后续受保护 inventory 任务仍需核实输入来源、汇总另外四类组件并完成签名/续期。普通管理员 API 不接收该输出。只读调用示例：

```bash
node scripts/hermes-compatibility-web-evidence.mjs \
  --manifest /path/to/original-web.manifest.json \
  --archive /path/to/original-web.tar.gz \
  --origin https://deployment.example \
  --version 0.1.1 --sha256 <expected-original-archive-sha256>
```

上面的路径、origin 和摘要由受信发布配置及验证身份提供；不能仅对管理员手填的包签名。工具不上传文件、不发送账号凭据、不跟随跳转，归档/响应均有大小上限；失败使用 `HR-HERMESCOMPAT-004`，不输出正文或异常原文。现有 Web 发布脚本和生产服务未因该工具改变。

Swift 消费者只在验签后生成内部构造的 verified 对象，精确匹配源 commit、平台、系统版本、安装类型和五个组件的版本/产物摘要，在匹配结果里选择最高稳定版。更高已装版本、缺失组件、未知环境、旧修订、过期及跨用途清单都不产生升级推荐。公开 HTTP 客户端每次 refresh 都联网，无账号凭据或缓存回退；验签后持久保存每个 origin 的最高修订和载荷摘要，同修订不同内容也拒绝。该客户端仍需接入普通页面和 I4 开始变更前复核，单独调用纯匹配器不能授权执行。

## 实施测试门禁

I2 执行边界：使用固定 GitHub workflow 和 macOS 26 arm64 托管测试机。准备/结果回收在独立的受信任务里用 GitHub OIDC 认证，实际运行上游代码的 macOS 任务没有 OIDC 写权限或发布密钥。固定官方 issuer/JWKS、audience、稳定 repository ID、workflow 路径、main ref、精确 workflow/GO commit、run ID 和 run attempt 都要核验。管理员账号 token 不能作为 runner 身份。派发仅传 run UUID，完整测试输入从受信控制面取得，禁止客户端提供脚本、checkout 路径、Gateway URL 或 workflow。

新增验收：错误签名/issuer/audience/repository/workflow/commit/run 被拒绝；过期结果、跨 run 回传、重复不同报告不能覆盖；故障环境与明确检查失败分别记录。GitHub 未启动任务、超时或报告不齐保持可解释的非通过状态，不能由管理员强制通过。

工作流文件为 `.github/workflows/hermes-compatibility.yml`，三个 job 分别 prepare / validate / report；只接收 `run_id`。仓库变量 `HERMES_COMPATIBILITY_GATEWAY_URL` 指向受控 HTTPS origin，不能由任务输入替换。运行时读取 `ACCOUNT_HERMES_COMPATIBILITY_CI_CONFIG_FILE` 和最小权限的 GitHub 派发凭据（`ACCOUNT_HERMES_COMPATIBILITY_GITHUB_TOKEN` 或其受保护文件），无配置不提供验证配方。配置格式见 `hermes-compatibility-config.ts`，GO commit 必须与 main 和实际工作流完全一致。

队列最多派发三次、间隔五分钟；running 保留 150 分钟，覆盖最长 105 分钟验证及前后任务/队列开销。超时只有 infrastructure_failed 状态，不构造通过报告。报告只发布字段白名单与证据摘要，不上传原始安装日志、HOME 路径或凭据。当前 Mac runner 执行完整升级/恢复、依赖落盘失败恢复、真实更新锁和运行接口探针。六项可通过（含 Desktop 托管 loopback token 鉴权及下述核心 REST 读取），并发更新在已测版本存在失败，其余八项仍未验收。四条核心 REST 读取和 active_list RPC 的局部证据不能直接认证完整 RPC、Connector 或客户端业务；这是开发中的覆盖状态，不是支持声明。

参考：[GitHub 托管执行器](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)、[OIDC claims](https://docs.github.com/en/actions/reference/security/oidc)。执行器系统版本仍从实际环境采集，不能只凭 `macos-26` 标签认证所有 26.x。

- 授权：普通账号、伪造邮箱、角色撤销后的旧 session、不同 account ID、账号删除或 session 撤销。
- 数据库：真实 PostgreSQL 18 验证原子幂等、修订冲突、并发撤销和审计；模拟 store 测试不代替此项。
- 证据：目标/源/组件/套件任一变化拒绝，未知字段/检查拒绝，失败或未执行不能审批，跨 run 和重复不同结果拒绝。
- 发布消费：坏签名、错用途、撤回、过期、旧修订、未知组件、陈旧审批和重复操作。
- 用户错误：新增 HR 码先登记，包含中英文摘要、恢复动作、retryable；不回显原始输入、凭据或 CI 日志。

Gateway 组件的 `artifactSha256` 固定为 `package-gateway-bundle.sh` 保留的原始 OCI tar 摘要。
`scripts/hermes-compatibility-gateway-evidence.mjs` 复用 schema-3 bundle 校验和 OCI 身份解析：
在有界私有副本中关联 Docker config ID、containerd descriptor ID、平台、版本和源 commit，然后
只读核对指定容器的镜像、启动配置、只读根文件系统、代码路径挂载、加载覆盖及两次观察间的稳定性。
Docker 格式化只返回必要字段、挂载目的地和加载覆盖标记，不输出完整环境或宿主挂载源；格式语法
遵循 [Docker 官方说明](https://docs.docker.com/engine/cli/formatting/)。采集命令不加载镜像、执行容器
内命令、创建容器、重启服务或访问生产凭据。

```sh
node scripts/hermes-compatibility-gateway-evidence.mjs \
  --manifest /canonical/release/Hermes-Gateway-VERSION-COMMIT-linux-amd64.manifest.json \
  --container hermes-gateway-blue --origin https://gateway.example \
  --version VERSION --sha256 ORIGINAL_ARCHIVE_SHA256
```

输入的路径必须满足既有 ops 无符号链接、不可被组/其他用户写入的要求。输出仍是未签名的时间点
观察；`publicRouteVerified=false` 明确表示尚未证明公网反向代理选择了此容器，origin 是受控调用者
提供的关联范围，不能当作网络回读证据。该输出不会被管理员 API 直接接受，也不会自动满足普通
Desktop 的 Gateway 身份。受保护发布任务仍需绑定部署选择/公网回读、另外四组件并签名及续期。
无法使用 schema-3 包、Docker、标准启动布局或满足运行约束时，输出 HR-HERMESCOMPAT-004。


## I2 核心 REST 读取证据范围

`required-read-surfaces-v1` 对应 Connector 合同的四个必需 GET 路由：`/api/status`、
`/api/sessions`、`/api/profiles/sessions`、`/api/sessions/{id}/messages`。在 source、candidate、
restored 三阶段检查实际版本/状态类型、合成会话 ID 和消费字段类型、数值秒级消息时间戳、原始文本，
以及历史 `limit=1&offset=0/1&order=oldest` 的结果和分页元数据。验证使用真实 Connector 鉴权适配器。
这是默认 profile 的单条文本合成历史范围；多模态内容、其他 profile、可选端点、写操作和真实客户端
业务继续由其他验收覆盖，不能从这个 `rest=passed` 推断已支持全部 REST 功能。

报告必须在三个阶段声明同一范围，包含五项明确布尔结果，并绑定实际 suite/probe 摘要；任一失败
使 REST 失败，缺项、未知范围或范围混用拒收。旧的无范围报告仍可验证原鉴权证据，但 REST 保持
`not_run`。REST 检查引用接口证据摘要，不能借用安装成功的摘要。历史证据不因测试代码更新而升级。


## I2 会话 RPC 局部证据

`synthetic-session-lifecycle-v1` 在同一 WebSocket 上等待 `gateway.ready` 后先声明客户端能力，再
按真实 Web 客户端的参数形状创建空会话、恢复合成历史、读取恢复句柄的 reasoning 配置，最后用
真实 Connector 活动列表解析器核对 live handle 与 stored ID 的关系。恢复请求使用
`omit_messages=true`，不得收到完整历史；此局部范围不包含 `prompt.submit`、附件或模型推理，对话验证见下节。

连接有 45 秒总期限和 1 MiB 单帧上限，匹配 JSON-RPC 版本和响应 ID，断连会失败当前等待；结果
只保留固定布尔检查，不记录句柄、原始响应或错误正文。三个阶段必须声明相同范围和全部六项
布尔结果；缺项、混合范围或状态矛盾拒收。局部全部通过仍不把总 `rpc` 项标为通过，因为尚未
覆盖完整 prompt 流式事件、工具/审批、生成中断重放与完整客户端协议；任何已测路径失败则明确记作
`rpc=failed` 并引用接口证据，不能隐藏成未执行。


## I2 本地模型文本对话证据

`loopback-model-text-turn-v1` 在上述会话范围之外新增 `prompt.submit`，要求 streaming 接受结果、
匹配新建会话的 `message.delta` 拼接和 `message.complete` 正文，以及历史接口中真实落盘的
user/assistant 内容、行 ID 和秒级时间戳。模型是固定回复的本地 HTTP fixture；Hermes 安装、
模型客户端、RPC 处理、流式事件和数据库仍使用固定官方原版。

执行器在每个合成运行环境里临时替换默认配置，使用 loopback custom provider 和无效于任何
真实服务的合成凭据；在后端进程停止后恢复原配置字节/权限。OS sandbox 只允许 loopback 外连，
先验证保留测试地址连接因权限被拒绝才创建 fixture 或启动 Hermes。原始外网访问、生产模型
凭据及日常安装不参与。配置恢复是正常/异常收尾保证，不认证进程被强杀后的恢复门槛。

每阶段新增六项：提交接受、流式回复、历史落盘、本地模型请求实际到达、配置恢复、外网隔离。
报告绑定 fixture SHA-256 与既有 suite/probe 摘要，缺项、混合范围、错误摘要或状态矛盾拒收。
80 秒连接总期限、1 MiB 帧上限、最多 256 条/64 KiB 文本事件限制测试资源；保存必要字段，
不上传原始请求或模型响应。只接受预定合成提示，本地 HTTP 请求体最多 4 MiB。

CI 验证 worker 使用 `--prompt --relay --restart --launchagent` 执行此范围及下述中继、后端重启和托管服务验证。局部通过仍不能认证完整 RPC 或客户端业务，
工具调用/审批、生成中断重放、真实模型、账号模式中继与手机/Web 端到端仍待验收。
任何已测对话路径失败明确记为 RPC 失败，不因它是模拟模型而忽略。


## I2 已完成文本对话的重连证据

`completed-text-turn-reconnect-v1` 必须附着于上述本地模型文本对话范围。首次连接关闭后才新建
独立 WebSocket；新连接重新等待 ready、声明能力，用刚完成对话的 stored ID 恢复，再核对
live/stored 映射并回读历史。恢复不能夹带完整历史；两条预期消息的行 ID、角色、正文和数值
时间戳必须与断开前一致。缺失、重复或被重写的预期消息均不通过。运行句柄可按上游规则变化，
不能假设句柄字符串相同就代表恢复成功，也不重新提交原提示。

新增五项布尔检查在 source/candidate/restored 都必需完整，范围缺失或混用拒收；任何失败
明确记为 RPC 失败。重连连接有 15 秒期限和 1 MiB 帧上限，错误和句柄不进入公开证据。
该范围不认证服务进程重启、生成中断/重放、Desktop 生命周期、升级恢复覆盖新版数据或手机/Web
业务；这些独立检查仍保持未执行。完整 RPC 也不能由这份局部成功提升为通过。

## I2 真实 legacy 中继局部证据

`legacy-source-build-relay-v1` 在三个阶段分别启动一次性 Gateway/Connector 进程，复用同一套
REST、会话、文本对话和完成后重连检查。使用编译后的真实入口、live Hermes 和启用的 observer；
Gateway 只监听 loopback，两个进程采用相同的外网隔离 sandbox、独立合成凭据与私有日志。
Gateway 不收到 Hermes 凭据，Connector 不收到 App 凭据；还实际交换两类凭据确认请求被拒绝。
Connector-owned contract endpoint 替代直接访问 Hermes OpenAPI，保留中继真实鉴权和契约判断。

worker 默认使用 `--prompt --relay --restart --launchagent`，合同实验期限为 30 分钟。报告保留直接访问和中继访问的
独立结果；分别观察本地模型收到请求，不能用直接访问成功替中继背书。每阶段绑定 fixture、
Node 二进制、三个组件 dist 树与 lockfile 摘要及 Gateway/Connector 源码版本。它们是本次源码
构建的身份记录，不是正式发行物验签，也不证明所有 node_modules 文件的完整性。

三阶段范围、版本、构建摘要必须一致，缺项、错用 direct transport、结果与状态矛盾均拒收。
白名单只输出检查和摘要，不输出地址、令牌、请求正文或私有日志。任一已测中继检查、凭据隔离
或清理失败，明确令 `connector=failed`。全部通过仍保持总 Connector 项 `not_run`：账号模式、
正式发布组件、文件通道、服务恢复及 Android/Web 客户端业务仍需各自验收。

## I3 Connector 正式发布输入

`scripts/hermes-compatibility-connector-evidence.mjs` 为受保护 inventory 产出端核验 Connector
的公开发布记录。调用方提供固定 origin、发布包版本、bundled/components 布局、架构、通道、
既有受信公钥与精确目标版本/归档摘要；这些输入应由发布配置提供，不能接受普通管理员提交的
公钥或任意“通过”报告。公开采集结果仍是未签名观察，不直接作为 Gateway 的 release inventory。

采集器构造版本化清单 URL，先验证 Ed25519 原始 payload 签名，拒绝重复 JSON key，再复用
既有 v1/v2 payload 结构、组件图和 URL 校验；额外拒绝过期、未来创建时间、错目标或跨发布目录。
只对目标 Connector 归档进行完整公共回读，流式核对签名长度与 SHA-256；结束时再次读取清单，
原始字节变化即失败。请求不携带账号凭据、不跟随重定向，清单最多 256 KiB，归档大小受签名
字段及既有 2 GiB 上限约束，整个观察有 120 秒期限。低层异常仅输出固定阶段，沿用双语
HR-HERMESCOMPAT-004，不公开响应正文或传输错误。

现用发布包 0.4.12 中，bundled 与 components 均声明 Connector 0.1.17，但归档分别为
`a8733c…e524a` 与 `95db00…63475`。因此两个组合不能仅按版本号互换，也不能把发布包版本
0.4.12 当成 Connector 版本。这项检查证明特定签名记录及对应归档在观察时公开可读，不证明
v2 Node 依赖已安装、当前进程身份、归档可执行性或 Hermes 兼容。五组件汇总、签名及续期仍待
受保护产出路径联调；新签名库存的有效期也不得超过其发布依据的有效期。

## I3 五组件库存生成原语

`scripts/hermes-compatibility-inventory.mjs prepare <protected-policy.json> <new-draft.json>` 读取
受控发布输入，重新调用五类采集器，生成未签名的库存载荷、策略摘要及证据摘要。CLI 没有
签名密钥、上传或管理员接口；不能把管理员提交的 JSON 报告当成受控策略或采集结果。
策略为 `schemaVersion:1`、固定 HTTPS `origin` 和最多 100 个 `combinations`；每项有严格排序的
`components`（desktop/connector/gateway/android/web 的 name/version/artifactSha256）及同名
`sources`。重复组合、未知字段、缺组件或源失败拒绝整份输出；空组合列表可准备清空库存的
载荷，但不会自行激活撤回。

| source | 受控发布输入 |
|---|---|
| desktop | 原始 app 更新 `manifest`，含精确 DMG、版本、build 和源 commit |
| connector | releaseVersion/layout/architecture/channel/keyId/publicKey，使用既有签名发布信任 |
| gateway | 原始 `manifestPath` 和可信发布主机上要核验的 `containerName` |
| android | 原始 APK 发布 `manifest`，沿用现有包名/通道/证书元数据合同 |
| web | 原始 `manifestPath` 和 `archivePath`，完整回读公开文件 |

Desktop/Android 采集器按受控原始记录读取版本化公开归档，完整核对长度和固定摘要，无账号
凭据、无跳转、有大小/时间上限。不执行或安装包，不重新声明 DMG notarization、APK 签名或
运行身份；发布时的 package gate 与原始记录来源仍是前提，公共索引本身不能创造发布信任。
Gateway 的运行镜像观察仍不证明公网路由。依赖注入仅供受信代码和测试使用，不是 CLI/API 参数。

同一次准备可复用完全相同的组件/来源观察；再次准备必须重新采集，不能续签旧报告。所有观察
在结束时须不超过 15 分钟且不能超前 30 秒。载荷有效期取结束时 24 小时、各观察时间 24 小时、
Connector 签名发布依据到期时间的最小值，按整秒向下取整；过期/未知依据无法生成库存。
公开 draft 只保留组件、来源类型、时间及摘要，不保留本机路径、原始发布元数据或采集诊断。

`parseReleaseInventory` 为生成和消费共用格式验证，拒绝重复组合；`CompatibilitySigner.signInventory`
只签专用 released-components 用途并验证实际序列化签名。受保护调用方仍须保证输入来自本次
完整采集，而非管理员替换的载荷。库存不能作为 Hermes 测试通过或推荐批准，推荐验收仍要求
全部核心检查。这里没有启用正式签名、生产 Gateway 库存交付或定时续期；这些生产接线
以及正式五组件原始资料/受信发布主机配置仍须完成。

## I3 签名库存的原子交付

受信主机使用以下入口安装已经签名的库存；该入口没有签名私钥、网络请求或服务重启：

```sh
python3 -I -B scripts/hermes-compatibility-inventory-install.py \
  initialize /canonical/protected/publication.json /canonical/protected/candidate.json
python3 -I -B scripts/hermes-compatibility-inventory-install.py \
  update /canonical/protected/publication.json /canonical/protected/candidate.json
```

配置沿用 Gateway publication config，目标为其 `inventoryFile`。输入和目标父目录必须是
规范绝对路径、当前执行账号所有且不可被组或其他用户写入；拒绝符号链接和硬链接文件。
Node 构建依赖须已准备好。Python 入口取得持久 `.目标文件名.lock` 的内核排他锁后，直接
exec Node 写入器并继承锁描述符；实际写入进程持锁，不按时间抢占，不删除锁文件。
写权限与受控主机是信任边界；描述符检查不是针对能任意改写目录的特权调用者的授权机制。

写入前验证专用用途、origin、有效期及 `releaseKeys` 签名。初始化必须显式使用 initialize；
update 不自动修复缺失库存。已安装内容必须仍可用受信公钥认证；过期库存只作为顺序记录，
不能继续授权。不同载荷的 issuedAt 必须严格递增，同一时间不同载荷或旧载荷拒绝；完全相同
载荷可幂等重试。密钥轮换期间应保留旧库存的验证公钥，不能通过删文件绕过顺序约束。

新内容写到同目录独占临时文件，设为公开可读的 0644，完成文件 fsync，再复核配置、目录、
锁身份、旧内容及有效期后 rename，随后目录 fsync 并回读验签。Gateway 已打开的旧文件即使
因替换失去目录链接，仍可读完其完整快照；签名和期限检查继续生效，多重硬链接仍拒绝。
替换前失败保留旧库存；替换后响应不确定时用同一签名载荷重试。强杀可能留下随机 `.next`
文件，它不被消费或自动提升；内核释放锁后允许重试。测试覆盖进程强杀，不宣称设备掉电认证。

新故障沿用库存读取端的双语 HR-HERMESCOMPAT-006，不回显输入、路径或异常原文。隔离测试
包含真实并发排斥、强杀前后恢复和空库存更新；正式受保护签名/交付任务、配置及续期尚未接通。

## I2 完成文本对话后的后端重启

`completed-text-turn-backend-restart-v1` 在同一隔离安装的 source/candidate/restored 阶段各执行
一次真实后端停止和重新启动。先通过直接连接完成合成文本对话，将 stored ID 和已核对的两条
消息保存在父进程内存；确认自有旧后端进程退出后，以新进程、新凭据、原 loopback 端口启动。
用真实 Connector 鉴权适配器核对运行版本、升级前种子历史、旧 HTTP 凭据拒绝，然后建立新的
RPC 连接恢复刚完成的对话，核对 live/stored 关系和两条消息的 ID、正文、角色及秒级时间戳。
不重新提交提示；本地模型请求计数必须不增加。重启前后的进程均由实验收尾，配置在 fixture
退出后恢复。端口/PID/凭据/会话 ID/正文仅在私有内存和子进程 stdin/stdout 管道传递，不进入报告。

已执行的重启包含 15 项明确布尔检查，三阶段缺项、错版本/范围或状态矛盾拒收。只有前置
promptHistory 失败、未产生 checkpoint 时，才允许 `attempted=false` 且 checks 为空；保留原
RPC 失败，不误报基础设施异常或已执行的重启失败，前置成功不能用此方式跳过。任何已测失败使
`serviceRecovery=failed`；全部通过仍不认证完整服务恢复，因为 LaunchAgent、Desktop 退出重开、
运行中断、账号模式和客户端业务尚需独立验收。已有 legacy relay 检查保持原范围；重启回读
本身走直接连接，不能声称验证了中继重连。worker 默认加入 `--restart`，合同实验期限为 30 分钟。

## I2 隔离 Desktop LaunchAgent

`temporary-desktop-launchagent-v1` 在 macOS GUI 用户域里验证真实 launchd。实验编译并链接当前
Desktop Core，复用 `DesktopLocalHermesLauncher`、`DesktopLocalHermesLaunchAgent` 和
`DesktopLaunchAgentController`。仅在受限临时目录生成文件；适配器把正式标签映射为随机
`com.hermesgo.compat-lab.<UUID>`，把 9119 映射为随机 loopback 端口，并加上既有 sandbox 和
干净环境。正式标签永不传给 launchctl，正式端口拒绝作为测试端口。重复标签在 bootstrap 前拒绝。

token 只存 owner-only 的 0600 文件，真实 Desktop launcher 读取它后 exec 官方 Hermes；plist、
命令参数及报告不含 token。清除继承环境和代理变量后再进入 sandbox，上游仍无日常 HOME
读写、外网或 launchctl 权限。Native 控制器执行 bootstrap/bootout；实际 launchctl 命令有
10 秒期限，首次启动取得该随机 job 的 PID，停止后核对 job 卸载、进程退出及端口可重新绑定。
若停止状态不确定，保存 owner-only 的私有清理记录并冻结该实验目录；不移动可能仍被写入的
候选路径，不自动重跑。人工核实并清理记录中的精确随机 job 后才可恢复此实验，不扫描或
停止任意同名 Hermes 进程；该记录不上传管理台。

source/candidate/restored 的初始与重启阶段共有六个循环，各记录 loaded/runningOwned/stopped/
portReleased。报告绑定 helper 源码/二进制、Core 源码树和 fixture 摘要；验证端重新计算受信
源码摘要。缺循环、缺布尔结果、身份或范围混用、状态矛盾拒收；明确失败使 serviceRecovery
失败。前置对话失败而未尝试重启时不伪造第二个循环。

这验证隔离标签/端口下真实 Desktop 生成和控制逻辑，不是日常正式标签的升级，也不证明
Desktop 退出重开、中继重连、生产凭据、账号模式或新版数据恢复。无 GUI launchd 域、Swift
工具链或源码构建失败属于基础设施未完成，不能代替通过。没有修改产品 UI、正式 plist 或
上游代码；首次完整受保护 workflow 仍须独立验收。

## I2 后端重启后的 legacy 中继恢复

`legacy-relay-completed-turn-recovery-v1` 扩展上述隔离合同实验。每个阶段先通过真实源码构建的
Gateway/Connector 完成一轮合成对话，保留其 checkpoint；Gateway 跨 Hermes 停止/重启保持
运行，端口、设备身份、app/connector 凭据不变。确认旧 Connector 退出且 Gateway 观察到离线，
再仅替换 Connector 进程，使用新 Hermes 本机凭据，等待同一设备重新在线。原 app 凭据经中继
读取版本、种子历史，并恢复重启前同一 stored ID；两条消息的 ID、正文、角色和时间戳必须
完全一致，本地模型请求数不得增加。此处不会创建新对话或发送新提示。

三阶段各有 16 项布尔检查。证据与既有中继 build/Node/fixture、lab/probe 和 Native 身份绑定；
缺阶段、缺检查、错误范围/版本、失败却标通过都拒收。只有前置直接或中继对话失败，才可跳过
并记录 attempted=false/空 checks；已测失败使 connector 和 serviceRecovery 失败。即使全部
通过，这两个完整门禁仍保持 not_run：这是 legacy 源码构建、已完成文本对话的恢复范围，
不是已发布组件、账号模式、实际客户端、运行中断或升级执行器的完整认证。

ExitStack 保证异常时先清理本任务中继，再恢复模型配置；只停止由 fixture 创建的进程组。
凭据、checkpoint、进程号和路径仅在本机私有状态，不进入公开报告。现有 worker 选项组合
`--prompt --relay --restart --launchagent` 自动执行本范围；无新增产品错误码或管理授权。
