# HG-258：Desktop 更新闭环

状态：已批准的源码实施，尚未升版、发布或安装到用户 Mac。固定方案为
[第 1 轮 v1](https://missiongo.mrlgs.net/?planDocument=afdc504a-eefe-45d7-81c6-aef0094ad927)。

## 行为与边界

| 对象 | 变化 | 边界 |
|---|---|---|
| 检查更新 | 应用、组件并行检查，各有结果和时间；所有手动入口展示同一结果 | 一源失败不能被另一源的“最新”掩盖 |
| 发现与安装 | 发现只读公共索引，安装重新核验账号、绑定、能力、布局和内容 | 发现不代表获准安装，错误账号不能接管本机安装 |
| 应用身份 | 比较版本及 build，发行配置与来源进入校验 | 同版修复需身份确有异常、显式选择和确认，禁止降级 |
| Node | 身份由版本、架构、二进制摘要与打包格式决定 | 不改已发布归档；旧 GO 提交身份过渡可能需要一次下载 |
| 应用替换 | 包内 helper、持久记录、精确新版启动确认、备份与恢复 | `open` 返回成功不等于新版启动；超时不自动覆盖未知进程 |
| 组件修复 | 只修复当前签名引用的 Node/Connector 内容，确认后隔离旧字节再替换 | 不改 Hermes、安装版本、布局、绑定、凭据或令牌 |
| 运行证明 | 核对 launchd 声明、存活 PID、内核 argv、Node 与 Connector 内容路径 | 缓存云健康、磁盘版本和已载入启动项不单独构成成功 |
| 兼容证据 | 精确 app/组件/Hermes 组合与真实上游隔离语义检查绑定 | 不能宣称真实手机、生产会话、未来 Hermes 已验证 |

旧 bundled 布局仍走既有独立迁移确认；不能因服务器支持新布局就普通替换。
旧 app index 保持 schema 1 和既有版本化文件名。没有有效更新配置的旧开发包无法通过自身
发现完整修复入口，仍需另获本机安装授权后使用可信发行包；本次不追溯猜测旧包安装来源。

## 开发与发行

`desktop:app` 默认产生 `HermesGoBuildPurpose=development` 的本地 app，设置展示开发标记与
build；它可以无更新配置，但不能通过发行验证。`desktop:dmg` 使用 `distribution`，要求干净源码、
完整配置及 `HermesGoSourceCommit`。源码已改动的本地构建不是可分发的发行物。

`DesktopDistributionConfiguration` 同时供构建、发行 CLI、正式/内测发布和安装接收端使用。
发行包要求应用、组件、托管配置都启用且具有一致的规范 HTTPS origin、channel、架构、有效
Ed25519 trust set 和 `hermes-serve-v1`。更新相关运行时环境覆盖只保留给开发包。安装还复核
版本/build/source、最低系统、架构、严格签名、冻结来源以及公钥轮换连续性；commit 再验 staged 内容。

新的内测发布命令增加 `--compatibility <exact-combination.json>`；正式发布命令增加
`DESKTOP_COMPATIBILITY_EVIDENCE`。在打包后的真实 app 与签名组件归档都已准备好时，用：

```sh
node scripts/verify-desktop-update-combination.mjs --config /absolute/inputs.json --output /absolute/new-evidence.json
```

配置必须恰有以下六个字段；除了提交号，其余都是操作者明确指定的本机绝对路径：

```json
{
  "appBundle": "/absolute/distribution/Hermes Go Desktop.app",
  "componentManifest": "/absolute/components/signed.manifest.json",
  "componentArtifacts": "/absolute/components",
  "hermesRoot": "/absolute/installed/hermes-agent",
  "hermesPython": "/absolute/installed/python",
  "hermesCommit": "<exact 40-character upstream commit>"
}
```

工具验证 app 配置及来源、Ed25519 manifest、完整本地归档的大小/摘要/内容身份和精确依赖，
再运行既有 `check-hermes-archive.py` 的隔离真实上游语义检查。它不启动另一份 Hermes、不读凭据、
不操作用户数据库或生产端点。证据记录检查结果、输入摘要及尚缺的真机验收；任何失败不生成通过证据。
发布端在上传前从实际 DMG 取回 app 再验，并要求其 executable/build/source 和当前组件索引的
manifest 摘要与证据一致。指针、包或源码变化必须重做验证；发布权限仍由用户另行授予。

只有已完成公开回读的历史发行回执、版本不高于 0.2.52，才能原样重试已有不可变包的 legacy 路径。
新候选不能借用该路径，也不能跳过新版门禁。隔离组合证据本身不证明 Apple 公证或真实安装。

## 应用事务与恢复

`Updates/active-update.json` 是 owner-private 的单活动事务。记录精确目标、staged/backup 路径、
前后内容摘要、目标版本/build/source、旧 PID 与状态；后续更新前保留历史结果。辅助锁跨进程互斥。

1. 下载、哈希、只读挂载及 bundle 校验在替换前完成，取消或失败清理暂存。
2. commit 重验前后内容，写入事务，然后由包内 helper 等待旧进程退出。
3. 原应用移入按事务 UUID 唯一命名的备份；替换后进入 `awaitingStartup`。
4. UI 模型初始化后、联网检查前，新应用仅为自身的精确路径、身份和事务写入启动确认。
5. helper 核对确认 PID 的存活、可执行路径、启动时间以及目标内容，之后才删除原备份。
6. 启动失败、超时、外部替换或中断保持 `recoveryRequired`，不会忽略错误后假报完成。

设置可显式恢复原应用；候选保留，结果只表示文件恢复/安排启动，业务健康仍要重新检查。
如果新版 GUI 无法启动，在确认目标应用没有运行后，操作者可使用该发行包的
`Contents/Helpers/HermesGoUpdateHelper <Updates绝对目录> <原事务UUID> restore`。
helper 不会强杀未知进程，不覆盖无法匹配前后摘要的外部改动。没有发生替换时，可以取消原暂存事务。
不要手工删除 journal/backup 或编辑 JSON 来绕过状态。

新应用自身的启动确认与后台组件升级结果独立；前者不能代替后者。

## 同版组件内容修复

此路径与普通升级/旧布局迁移分开，仅接受 account-active、component-store 的当前版本和签名
manifest，且 kind/version/content 与已提交 reference 对应。准备在私有 cache 的第二个临时 store
执行完整下载/解包/内容与收据验证，不移动活动目录。健康组件不会被列为需要修复。

确认时取得迁移操作锁，重新核验原账号、设备私钥指纹、凭据/令牌摘要、journal、原启动项与前后
文件身份；停止并证明原 Connector 已退出后，才把受损 hash 目录移入 UUID 隔离目录，并移入可信内容。
不会重启或安装 Hermes，不改账号绑定，不清除外置盘/限速等受支持配置。只有精确新运行身份及
原绑定的严格更新健康时间戳都成立才记成功，隔离旧内容仍保留。

`Managed/state/component-content-repair.json` 留存中断步骤和原签名 envelope。存在它时，普通迁移、
升级与自动恢复不能覆盖现场。再次显式修复会重验原 envelope、账号和步骤，不依赖更新指针仍指向
旧版本；已完成的精确步骤保持幂等。签名不再受信、外部内容变化、未知服务、符号链接/不安全内容或
缺乏新鲜健康证明时保持阻断；不会启动受损旧内容来伪造回滚成功。

## 自动验证与剩余验收

| 场景 | 预期结果 | 方法 |
|---|---|---|
| 两个来源的任一故障 | 有效更新保留，失败来源单独可恢复 | Swift transport/state 回归 |
| 同版高 build 与显式修复 | 普通发现/修复分开，不降级 | Swift identity 测试 |
| 发行配置、来源/架构/信任变化 | 发布和接收拒绝不匹配包 | 共用 verifier 与 fixture |
| Node 未变、GO commit 改变 | Node 归档/身份相同，Connector 不同 | Node 双构建回归 |
| 旧 PID 存活、启动失败、确认超时 | 不删除备份或覆盖未知内容 | 隔离 app 事务测试 |
| 组件受损、替换后中断、外部改动 | 隔离保留，严格续修/拒绝覆盖 | 故障注入与准备/确认集成测试 |
| 配置/凭据/版本保留 | 修复前后 journal、启动项、凭据和令牌不变 | 隔离完整流程比较 |
| 精确组合变化或漏测业务 | 不复用旧证据，不生成通过结果 | Node evidence 测试 |

最终结果由本轮交付评论记录。完整 Swift、Node、资源和 app 构建需要按实际运行情况报告；
macOS 沙箱会阻止部分测试所需的回环监听、嵌套只读 sandbox-exec 和 CoreImage，不能把这类失败
静默当通过。本机既有恢复加密 fixture 需要 OpenSSL 3，使用命令级 PATH，不把本机路径提交为共享配置。

本次不执行生产替换。真实旧客户端发现/下载/替换/重启、旧布局迁移、故障恢复及真实 Mac 上
专用测试会话的归档、恢复、改名、官方删除和项目移动仍需另获安装/服务重启授权后验收。
官方标题语法、活跃会话删除限制、authority workspace 限制及旧写操作非 CAS 边界保持不变。


## 本轮本地验证结果（2026-11-16）

- Swift 完整基线：765 项通过；沙箱外运行测试所需的本地回环、只读嵌套 sandbox 和 CoreImage。
- Node 构建通过；protocol 27、Connector 209、Gateway 237、release-server 41、scripts 552 项通过；Gateway 32 项及 scripts 1 项依环境跳过。恢复加密 fixture 使用命令级 OpenSSL 3 PATH。
- website check 与 30 项测试通过；本地 HTTP fixture 需要沙箱外监听权限。
- Desktop 资源校验与开发 app 构建通过；没有启动、安装或分发该开发构建。
- 真实上游 bf0203a794a07aac3ff764972ab9087baa4e4f4e 的隔离 handler 验证 38 项通过；上游保持未修改。精确组合证据单元回归 3 项通过。
- 发布门禁核对的是生成证据时及发布预检时的精确组合，不承诺未来组件指针兼容。现有组件与应用发布事务未共用跨渠道全局锁；操作者必须串行发布并在指针变化后重跑组合证据，不能并行发布两个渠道来复用预检。
- 未生成本轮发行包或发行组合通过凭证；未执行真实旧客户端升级、安装失败恢复、UI 观感和专用真实会话业务验收。当前构建不代表已通过这些验收。
- 改动仍在独立分支，未提交、推送、创建 PR、合并、升版或发布；本机旧应用和服务保持原样。上述真实安装及服务操作需独立授权。


## 用户追加的三轮审计与补修（2026-11-16）

沿用原实施批准和本次“最多 3 轮独立审计，有问题补修”授权，按不同审计目标逐轮检查；
这是当前会话的三轮复核，没有引入另一个审计者，也不把自测称为独立人工验收。

| 轮次 | 发现 | 补修与验证 |
|---|---|---|
| 1：事务与故障现场 | 恢复文件已移回、状态未落盘时无法重试；restoring 无 UI 续办入口 | 按前后摘要恢复各中断点；保留候选，未知文件仍阻断；新增回归先失败后通过 |
| 1：停止期间的变化 | 异步停止 Connector 后未再次核验凭据及启动项 | 停止后重新核对原绑定、journal、凭据、令牌与启动项；变更即保留现场，不替换内容 |
| 2：组合及运行证据 | 仅主程序摘要不能覆盖 helper/配置；argv 不等于实际执行文件 | 精确组合新增完整 bundleSHA256；同时核对内核可执行路径和 argv；新增拒绝回归 |
| 3：发布契约一致性 | 索引与包内渠道、架构、最低系统版本未在共用入口逐项相等 | 共用发布预检核对五项身份；发行最低系统版本须有效；新增不匹配回归 |

应用恢复现在支持在两个重命名之间、以及旧文件已恢复但结果尚未保存时显式续办。
每次 helper 执行在等待旧 PID 退出后还检查目标路径没有运行中的应用；不强杀或覆盖未知进程。
`restored` 仍只证明文件恢复和启动安排，不证明恢复后的业务健康。
组合证据必须带整个应用包内容摘要，旧的仅主程序证据须重新生成；未扩大历史发行回放的版本边界。

最终回归：Swift 全量 769 项通过；精确组合与发布身份 5 项通过；隔离真实 verifier 验证 helper 内容
变化会改变整个 bundle 摘要；Desktop 资源校验及开发 app 重建通过。scripts 全量 555 项中
554 项通过、1 项因未配置一次性 PostgreSQL 而跳过；先前沙箱内跳过的 3 项回环测试已在沙箱外补跑通过。
真实安装/恢复、UI 与专用真实会话验收缺口，以及串行发布和未提交/未发布边界保持不变。
