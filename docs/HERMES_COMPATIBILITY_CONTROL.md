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

Swift 消费者只在验签后生成内部构造的 verified 对象，精确匹配源 commit、平台、系统版本、安装类型和五个组件的版本/产物摘要，在匹配结果里选择最高稳定版。更高已装版本、缺失组件、未知环境、旧修订、过期及跨用途清单都不产生升级推荐。公开 HTTP 客户端每次 refresh 都联网，无账号凭据或缓存回退；验签后持久保存每个 origin 的最高修订和载荷摘要，同修订不同内容也拒绝。该客户端仍需接入普通页面和 I4 开始变更前复核，单独调用纯匹配器不能授权执行。

## 实施测试门禁

I2 执行边界：使用固定 GitHub workflow 和 macOS 26 arm64 托管测试机。准备/结果回收在独立的受信任务里用 GitHub OIDC 认证，实际运行上游代码的 macOS 任务没有 OIDC 写权限或发布密钥。固定官方 issuer/JWKS、audience、稳定 repository ID、workflow 路径、main ref、精确 workflow/GO commit、run ID 和 run attempt 都要核验。管理员账号 token 不能作为 runner 身份。派发仅传 run UUID，完整测试输入从受信控制面取得，禁止客户端提供脚本、checkout 路径、Gateway URL 或 workflow。

新增验收：错误签名/issuer/audience/repository/workflow/commit/run 被拒绝；过期结果、跨 run 回传、重复不同报告不能覆盖；故障环境与明确检查失败分别记录。GitHub 未启动任务、超时或报告不齐保持可解释的非通过状态，不能由管理员强制通过。

工作流文件为 `.github/workflows/hermes-compatibility.yml`，三个 job 分别 prepare / validate / report；只接收 `run_id`。仓库变量 `HERMES_COMPATIBILITY_GATEWAY_URL` 指向受控 HTTPS origin，不能由任务输入替换。运行时读取 `ACCOUNT_HERMES_COMPATIBILITY_CI_CONFIG_FILE` 和最小权限的 GitHub 派发凭据（`ACCOUNT_HERMES_COMPATIBILITY_GITHUB_TOKEN` 或其受保护文件），无配置不提供验证配方。配置格式见 `hermes-compatibility-config.ts`，GO commit 必须与 main 和实际工作流完全一致。

队列最多派发三次、间隔五分钟；running 保留 120 分钟，覆盖最长 85 分钟验证及前后任务开销。超时只有 infrastructure_failed 状态，不构造通过报告。报告只发布字段白名单与证据摘要，不上传原始安装日志、HOME 路径或凭据。当前 Mac runner 执行完整升级/恢复、依赖落盘失败恢复及真实更新锁探针。四项可通过，并发更新在已测版本存在失败，其余十项仍未验收；这是开发中的覆盖状态，不是支持声明。

参考：[GitHub 托管执行器](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)、[OIDC claims](https://docs.github.com/en/actions/reference/security/oidc)。执行器系统版本仍从实际环境采集，不能只凭 `macos-26` 标签认证所有 26.x。

- 授权：普通账号、伪造邮箱、角色撤销后的旧 session、不同 account ID、账号删除或 session 撤销。
- 数据库：真实 PostgreSQL 18 验证原子幂等、修订冲突、并发撤销和审计；模拟 store 测试不代替此项。
- 证据：目标/源/组件/套件任一变化拒绝，未知字段/检查拒绝，失败或未执行不能审批，跨 run 和重复不同结果拒绝。
- 发布消费：坏签名、错用途、撤回、过期、旧修订、未知组件、陈旧审批和重复操作。
- 用户错误：新增 HR 码先登记，包含中英文摘要、恢复动作、retryable；不回显原始输入、凭据或 CI 日志。
