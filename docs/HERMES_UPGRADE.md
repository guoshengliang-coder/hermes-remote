# Hermes 持续升级（HG-199）

## 本机管理记录与版本检测边界（2026-10-24）

本机Hermes页独立读取升级journal，显示固定目标、最后意图阶段/时间，并区分待恢复和历史终态；
不将其当当前运行/健康或完成证明。重复键、嵌套未来字段、超预算或读取期间身份变化拒绝，保留
HR-HERMESUPDATE-003且不改写记录。上游检查使用无继承凭据/cookie的临时会话、精确官方URL、
拒绝重定向、接收中512KiB JSON上限及同一30秒单调时限；错误沿用HR-HERMESUPDATE-001。
此处仍无执行/恢复按钮，GO推荐和certified path继续为空。
2026-10-24独立实际Core fetcher只访问公共GitHub，已解析官方v0.22.5到固定
bf0203a794a07aac3ff764972ab9087baa4e4f4e；没有读取/修改现用安装或认证此目标。既有0.21.3→
0.21.6矩阵是固定历史实验，不会因检测到新发布而自动扩展适用范围。

新增独立原生uv/本地PEP517实验用实际固定PM PythonEnvironment、uv0.12.3/Python3.14.7：
调用方中止自建uv并关闭FD、监督方再中止调用方后，backend新心跳与真实双域锁验证raw丢锁/
proposal持锁直到backend退出。该构建有意中止，没有durable intent/实际官方锁或完整sync，不能
将独立通过的worker和native边界拼成全树认证。复现及sandbox差异见原型README。

## PM worker 持锁实验（隔离，2026-10-24）

新增窄proposed spawn与真实固定PM启动参数对照。默认_request worker在最后调用方死亡后丢失维护/
官方锁，受控传递实际FD后能继续持锁；保留close_fds、固定private home/cwd/解释器和原计划，拒绝
任意命令或继承环境。普通writer仍由持久意图阻断，未知started状态不自动重跑，也不运行验证解释器。

实际Mac两份新夹具的PM runtime bootstrap/选中Python3.14.7、raw/proposed闸门及各一次自建调用方
SIGKILL通过；PM worker业务代码未执行。后续uv/build进程及其父方死亡、同步恢复仍未证明。
此proposal还没有接入GO或默认joint同步，不能由单个worker持锁推断整棵进程树或产品升级安全。

## 同事务的源码与 PM 准备（隔离原型，2026-10-24）

新增source_pm策略将原源码计划、目标PM输入/Python版本与真实同步的准备检查点连接起来，
不再把两个独立实验拼成联合证据。实际上游更新锁覆盖源码移动和PM；started事件早于真实同步。
新进程需要外部原计划及generation SHA，必须重新读取实际源码/依赖与facts，部分或未知同步状态
不能被保存的成功布尔值放行。只发布准备观察，不结束维护或宣称升级完成。

本机新夹具已完成真实源码移动、Python3.14.7/packaging26.0同步/重查、完整重查后的自建协调方死亡，
以及错误generation/facts漂移拒绝；原intent/history持续禁入writer。底层sync没有完整激活输入，
未补造戳。PM执行中子孙进程custody与恢复、监督方死亡授权及完整启动/业务仍未认证，继续实现。
具体复现和限制见upstream-maintenance-prototype/README.md；生产GO尚不导入此模块。

## Desktop entry point — 2026-10-24

HG-199's read-only “检查 Hermes 版本” and “检查升级条件” now live in the dedicated “本机 Hermes”
page's “版本与升级” card, beneath local installation/usability. Settings and unified Desktop updates
manage GO app/background components only. Future confirmed upstream upgrade/progress/recovery
belongs to this same Local Hermes surface, using the existing journal/error/evidence boundaries.
This relocation does not enable execution or certify an upstream target. Main's account-only
navigation, original default home/profile and installed-layout update routing remain in use.

HG-199 保持进行中，按三个阶段交付。本文记录已实现的边界与剩余验收，不能用来宣称
0.21.6 已获得 GO 自动升级推荐。Hermes 核心、Desktop app 和 Node/Connector 组件有各自的更新来源。

## 阶段一：兼容基础与版本检查

- Desktop 同时识别旧 `venv/bin/hermes` 和新版 `.hermes/bin/hermes`，新版根据
  `~/.hermes/installs/<sha256(canonical checkout)[:16]>/facts.json` 找到提交的环境代次。
  所选代次必须在本安装的 environments 内；新版布局坏掉时不回退猜测旧 venv。
- 匹配当前提交的安装 stamp 或精确 release tag 提供版本；HEAD、入口、home、环境代次和
  源输入构成运行记录的身份。相同版本/提交但环境变化也会使旧记录失效。PM 激活输入的
  纳秒 mtime 必须和源文件一致，缺少输入则等待依赖完成。Launcher 不向 PM 注入旧 VIRTUAL_ENV。
- 旧 v1 更新标记遵循 20 分钟过期；v2 按实际 PID 创建时间与 delegate 存活判断，长时间同步
  不会被误当作过期。这里识别官方更新状态，不是 GO 升级事务的锁或恢复日志。
- Connector 暴露有期限、按 profile/连接代次隔离的被动能力证据；不探测写操作、不删参数重试。
  多接口功能只有在所有相关路径均被证实不存在时才为 unsupported。跨端按功能禁用仍属阶段三。
- GO 的 history display schema 1 增量附着在现有可选预览接口，保留 legacy 行。Android、Web、
  Connector 共用显示样本；0.21.6 独立技能压缩提示、混合真实输入与未知内容块不会悄悄丢失。
- Desktop “本机 Hermes”页面的“版本与升级”卡片显示安装版本、提交、上游稳定版本、GO 验证推荐和检查时间。
  官方 release tag 解引用到固定 commit，不能用 `target_commitish: main` 当升级目标。
  检查失败清除成功报告；GO 索引没有配置仍可单独检查 Hermes。没有完整验证结果时不提供执行升级。

本阶段的 verifiedTargets 默认空。源码兼容检查或样本数据库检查通过，均不足以加入推荐列表。

## 阶段二：升级执行和恢复（事务基础已实现，执行适配未开放）

跨进程门槛已有两份固定真实模块的独立进程反例：单个进程 commit retirement 后，使用相同
源码/home 的另一个进程仍能 acquire。实验只注入 idle ledger，不运行 Hermes 服务/业务；
`scripts/compat/hermes-maintenance-scope.py` 及固定源 CI 保留 `globalAdmissionProven=false`。
上游协作需求、首次从不参与协议版本切换的缺口及后续范围建议见
[`HERMES_MAINTENANCE_REQUIREMENTS.md`](HERMES_MAINTENANCE_REQUIREMENTS.md)。用户已授权隔离原型；
POSIX 双域准入/生命周期锁和子进程持锁已有实验实现，未接入真实入口/GO、未创建上游 PR。
固定真实模块的未参与入口仍可接入，原型不认证首次旧版切换。尚未采用新的线上协议。
隔离 CLI 前缀实验进一步证实准入必须早于 bootstrap 导入：12组成对场景中，
`main()` 内 guard 来不及阻止真实 scratch 写入，前置 guard 才能无 home 写入地拒绝。
PM/恢复为注入空操作，实验在 scratch hook 返回时停止；不是完整启动或 profile/全 writer 覆盖。
后续共享bootstrap实验覆盖CLI/legacy/ACP/gateway的12个前缀，对照main单点漏掉9个。
显式继承排他FD的恢复角色可跨父进程close保持封闭，避免重取共享许可造成自阻塞；
它只是测试launcher内核原语，不提供run/source/role认证与跨exec/重启身份。
独立活进程交接实验再通过匿名socketpair、只读私有context FD和实际锁，绑定run/source/home/
入口/恢复角色及11份变换后源输入；5个获准前缀和13个导入前拒绝场景验证确认与持锁边界。
这只验证可信测试launcher，不代表整个Git/runtime闭包或生产授权；
`liveBindingChecksVerified=true`与`productionAuthorityVerified=false`、`durableHandoffVerified=false`
分别记录。后续故障实验已覆盖四入口的8个commit未送达/最终确认丢失前缀：
父方在两种情况均保留不确定状态与原锁，子方真正退出后仍阻挡外来请求，不能按子方状态释放。
9个合成SIGKILL场景验证四个握手检查点的存活方持锁；所有持锁者死亡后writer仍可进入，
明确暴露纯内核锁没有耐久事务记忆。合成交接确认也不等于服务boot成功。
跨exec/重启身份与不确定状态的耐久恢复仍待实现，不能靠JSON回执接管或空锁自动解除维护。
新增持久意图实验使用schema 2预登记的双域固定槽，writer在共享准入锁内只接受精确空闲记录。
维护方已排空并同步全部事务上下文后被SIGKILL，新writer仍拒绝；4个新进程可在独立预期上下文
与排他锁核验后进入scratch，28个上下文不符和竞争恢复拒绝，部分发布也不自动修补。
恢复退出保留槽，`durable_intent`没有清除/finish API。`processRestartAdmissionVerified=true`仅为这一进程级
阻断证据；用户授权的耐久来源、真正更新后的源/依赖代次、终态清除、OS重启/断电和服务恢复
仍未认证，`durableHandoffVerified=false`不变。完整边界与复现见上游协作需求及原型README。
原生预加载、cron包初始化和真实PM/恢复仍未覆盖，全部升级认证flag保持false。

后续`hermes-maintenance-transition.py`仅在显式临时fixture内追加计划与测试终态，保留原意图，
验证一次源码注释/合成依赖标记的代次转换。目标摘要在变更前固定；真实独立子进程必须完成活通道
核验、执行scratch前缀且退出0，监督方复核目标后产生5秒内一次性内存witness，才可提交测试终态。
布尔值、保存的JSON、过期/跨持锁者的证明不能完成；部分追加、目标漂移或旧源码/依赖标记仍阻断。
四入口完成后writer可接入，8次旧输入拒绝、10次协调方SIGKILL由Linux/macOS隔离矩阵验证。
`fixtureCompletionPolicyVerified=true`不代表真实依赖环境、服务boot/业务、生产授权或OS重启安全。
此策略不提供下一轮epoch、部分日志修复或监督方死亡后的授权恢复；`productionCompletionVerified`
和完整耐久/全局/升级认证保持false，未启用Desktop执行入口。复现与边界见原型README。

`hermes-maintenance-pm.py`进一步提供真实PM代次的独立观察：仅新建标记临时安装，在已同步的双域
持久意图下执行官方底层sync，再实际运行选中Python和选中site中的packaging，检查SSL/SQLite/SHA，
并对facts/激活输入/环境/Python base内容和身份做两次独立观察。显式故障矩阵保持mtime地改变
测试依赖、facts及lock输入，旧观察均须失效；不运行服务或提交终态。底层sync没有发布完整CLI
激活输入戳，因此明确`activationInputsCurrent=false`，不补造、不推成激活就绪。
14项合成单测与两端固定PM选择器的1正例/3拒绝独立运行；真实安装/解释器证据仅来自Mac opt-in，
不是CI合成记录的结论。完整限制、预算与复现见原型README。旧转换策略的真实依赖/生产完成认证不变。
2026-10-16全新fixture `hg199-pm-generation-n0r7sgc8`的`--provision --fault-matrix`完整通过：
实际Python 3.14.7/packaging 26.0、8477项环境/2013项Python base（240323588字节）、3次漂移拒绝/
恢复自有字节后复核，退出全部观察锁后普通writer仍被持久意图阻断。激活戳明确false，未提交终态；
这不是完整Hermes冷安装、激活、服务或业务验收。本机完整兼容单测110项通过。

外部恢复材料已增加独立 `DesktopHermesUpgradeDependencyStore`：调用者明确选择 home 外的
最多16个不重叠目录，保存私有 run/source/home/OS build/architecture manifest、文件 hash、
owner 执行权限及内部相对链接。只读 FD-relative/no-follow 遍历限制总量65536项/2 GiB/64层；
拒绝外部/绝对/悬空/环路链接、硬链接、特殊文件、异常权限与源变化。停写证明必须在复制
前后由调用者独立提供，根目录不会靠扫描整个用户 home 猜测。
重入只能复用同一内容/身份材料；校验不依赖原目录仍存在。导出复制到新私有目录后以
exclusive rename 发布，拒绝已存在/竞态出现的目标、live home/原工具目录及其当前别名，
不覆盖后来文件、不改 live venv/绝对引用、不执行解释器。原始权限的 owner 位被保留，
group/world 位从副本移除；材料不携带原目录 ACL/xattr。公共 receipt 仅含身份和 manifest hash。
自动枚举完整依赖、Mach-O/dlopen/动态插件和 OS/ABI/签名兼容证明、journal 持久引用与
Machine 的独立 evidence 接入仍待完成；不能用“已备份指定目录”替换 requireDependencyClosure。

2026-10-15 隔离实验中，自建原生工具在原临时源删除后从导出副本运行通过。另对已安装的
standalone CPython 3.11.15 仅只读取材，导出一次性副本、再次归档、删除该一次性源，再导出
运行 `-I -S -B` 的 ssl/sqlite3/hashlib/json 验证通过；原安装没有执行/删除/修改。
该实验没有完整旧 Hermes venv/业务/绑定/全依赖闭包或生产服务。复制系统 `/bin/echo` 的早期
实验被系统 SIGKILL，说明相同字节不保证平台程序可异位运行；未通过重签或修改程序规避。
因此只把实际通过的特定 runtime 实验记作材料恢复证据，不作任意工具/升级认证。

目前产品增加只读“检查升级条件”：识别 dirty/unknown 工作树、官方更新进行中、未完成或
不可读的 GO 升级日志，检查过程中不修改 Git index。它不是任务退休/停写证明，不授权执行。

2026-10-14 补入单个 serve 进程的真实只读活动采集：验证 GO 私有 launcher/token 合约，以
无代理/重定向/cookie/cache 的固定回环 GET 读取 host identity、启动 version/full commit 和
idle ledger，再复查 host PID、内核 birth/argv、令牌/launcher 与安装身份。每响应接收上限
4 KiB、每请求资源上限一秒、整个快照新鲜窗口五秒；忙碌/未知/不完整/身份变化阻断条件检查。
只保存状态及 admission/session/delegation/cron/humanInput 类别，不保存 ledger IDs/原文或凭据。
它不发送 retirement POST，不暂停任何任务；观察到 idle 也不构成全 profile 停写证明。

固定 0.21.3（17b5df02）已有 idle/retirement，但缺少 `/api/host/identity` 与 health commit；
仅有 idle=true 仍未知。固定 0.21.6（818c13be）的退休 permit 为进程私有 30 秒 prepare，
commit 后不可 cancel；RPC、session workers、进程内 cron 与 delegation 使用其 admission。
这不是 checkout/home 级禁入锁，独立 CLI/另一个 gateway/外部 scheduler 或新进程不会共享它。
gateway 的 pause-for-update 自有 drain/exit 协议，也不是全机 writer 禁入。因此后续 collector
必须提供独立的完整 writer 覆盖/未来接入关闭，不能拿这一诊断或 Connector ACK 构造肯定证明。
源码出处与字段差异见 `HERMES_CONTRACT.md` 的单进程活动诊断小节。

已实现的核心边界：

- `DesktopHermesUpgradeCoordinator` 串行化点击，并在每个副作用前持久化意图；来源 commit →
  目标 version/commit 必须属于已验证升级路径，不能仅凭目标版本兼容就允许任意来源升级。
  实际操作由 `DesktopHermesUpgradeOperating` 提供；当前没有启用的生产适配器或已认证路径。
- owner-only `hermes-upgrade-state.json` 采用有界严格解码、原子替换和 fsync。阶段表示可能已经
  开始的工作。重启后按同一 run/目标续作，适配器须重查 receipt custody、工作树和未知新写入；
  不能把失败步骤直接当成未发生。损坏/未知 schema/软链日志同样阻止自动修复。
  开始下一次升级前，上一终态日志先归档到私有 run 目录，保留目标、服务状态和备份引用。
- 共用 migration operation lease；未完成日志在 lease 获取及运行协调的最前面阻止迁移、组件更新、
  服务修复、token/PATH 重启、bundled 未加载时的拉起，以及安装 Hermes。关闭本机运行设置也不绕过。
- 取消只在停写/备份阶段接受，恢复原服务后才记录 cancelled。源码变更后保留固定目标继续收尾；
  关闭 UI 取消等待任务不会取消事务子任务。进程真正死亡则留下恢复游标。
- `DesktopHermesUpgradeBackupStore` 需要调用方持有锁并在前后证明 writers 停止。完整 home 包含
  checkout、venv/PM tools、profiles/config/cron；软链只保存链接本身。SQLite 用 backup API 合并
  已提交 WAL 数据并 quick_check，输出不依赖 WAL/SHM。源指纹与归档 hash 复核、空间检查、0700/0600
  私有权限及 fsync 后才出具 receipt。同一 run 的已完成备份可验证后复用，源数据变化则拒绝。
  SQLite 恢复只导出到新目录，绝不覆盖原 home 或已存在的恢复目录；新增会话留在原库。
- 完成判定同时要求当前 run 的官方终态 receipt、无 followups/未恢复服务、PM 同步成功、
  输入有效、精确磁盘/boot commit、原 loaded/unloaded 状态及无 completion/fleet 欠账。
  不能仅凭 exit 0、success 字段或“Update complete”文案宣称成功。
  receipt 的 correlation_id 绑定 GO launcher run，update_id 绑定官方/PM 工作链；两者分别核对，
  不要求它们相同。适配器必须在执行前记录上游 update_id 并传递 HERMES_UPDATE_CORRELATION_ID。
- 新增随 app 打包的 `hermes-upgrade-worker.py` 和 Swift IPC 驱动。驱动清空继承环境，只传
  明确的 home、缓存、关联身份及选定代理；私有配置先落盘，同一 run 不允许替换配置。
  worker 验证固定 Git 源快照全部 Python blob，拒绝隐藏 index 修改、额外 Python 模块、可执行
  Git filter/include/URL 重写，然后调用目标源码的真实 `UpdateLock`。冻结源快照仍拒绝 partial-clone
  元数据；实际安装仅接受 origin 的单一 `promisor=true` / `partialclonefilter=blob:none` 配对
  （旧 Git 的 `extensions.partialclone=origin` 可同时存在）。其他 filter、remote、重复/缺失键、
  自定义 VCS helper 均拒绝。固定当前版本的 commit、所有 tree 和全部文件 blob 必须在本地，
  清单最多 32768 项；历史旧 blob 可缺失。GO Git 查询清除继承的 Git 环境，禁用 lazy fetch
  并禁止所有传输，缺对象保持恢复状态，不能自动下载；仅已校验本地快照的显式 fetch 允许 file。
  官方 PM currency 子进程也禁止 Git 传输；该临时限制不传入执行显式历史补全的 finish 环境。
  获取锁只写官方锁记录；停写和备份由机器适配器提供，写入 updating 意图后才可执行。
  源码移动使用固定对象的 fast-forward/精确 detached checkout，先持久化官方 completion、fleet
  和 interrupted-pull 义务，再调用官方 historical takeover → PM → finish。没有 pip/reset/stash 回退。
  深度一的旧克隆只从验证过的本地快照有界补齐祖先；缺少对象或不能证明 fast-forward 则拒绝。
  固定 release tag 必须解引用到同一目标 commit，随对象导入而不强制覆盖已有 tag；缺失、漂移或
  本机同名 tag 冲突均拒绝。仅有 commit 的源码快照会使官方 finish stamp 的 version 成为 unknown，
  不能作为完整版本结果。完成检查使用 `version_info.get_code_identity`，不依赖旧 lazy `__version__`。
  从独立快照加载的 `update_host_obligation` 以 `__file__` 推导安装 key；worker 在预写义务时临时
  将模块 origin 指向确认的 live checkout，并在 finally 恢复，确保预写与收尾记录同一安装。
  不修改任何上游源码；此私有内部约定必须随每个认证目标重新审计，实际矩阵验证 key 与 origin 恢复。
  普通 update 对已经位于目标提交的重试仍拒绝，不用新空白 receipt 覆盖上次上下文。
  独立 `resume-finish` 仅接受同一 run/官方 update_id 的完整 PM handoff：checkout/tag/全部源码
  Python blob 仍为固定目标，PM 回执已成功，私有 `official-finish-state.json` 处于 ready 且绑定
  原始上下文 hash/安装/目标/两种 ID。若本次私有 archive 存在，必须唯一且与上下文一致，官方回执
  尚未终结且尚无 finish 步骤/阶段、服务效果、followups 或人工恢复义务。查询本次 archive 而非
  latest.json；checkpoint 缺失、重复 archive、链接、已有 result、PM/身份/plan 不符及探测期间变化全部拒绝。
  使用官方只读 `venv_is_current` 核验当前依赖，官方 `resolve_store_python` 和
  `activation_environment` 选择解释器（隔离子进程从 live checkout 导入，不能用快照 origin 查安装
  registry/facts），再以继承 checkout custody 的官方 `update_finish.py` 续作，
  不重跑 takeover/PM，不改写原始 handoff，子进程禁止 lazy install。返回码仍须独立完成判定。
  PM 提交后 handoff 尚未落盘、finish 已产生步骤/副作用、或终态 receipt 仍欠 fleet 恢复的情况
  尚未接入自动续作；不能删除回执/债务使其重新变成可重试。具体机器适配器仍须先证明所有
  writers 停止、备份及外部解释器闭包有效；此 backend seam 未启用产品按钮。
  正常升级的受锁子进程调用官方 `prepare`/finish helper；preparing 在 PM 前落盘，完整 handoff
  fsync 后写 ready，正常执行及恢复执行均先写 finishing 才启动 finish。历史 `-S` 解释器可能缺少
  ruamel，导致官方 best-effort running archive 未落盘：只有 GO 的 ready 记录才能证明这种缺失
  不代表 finish 已执行，不能给旧日志补造 ready。若 preparing/finishing 时中断，仍需专门恢复。
- 上游 update_id 随第一份 GO journal 固化；重启续作不能换用另一官方工作链。
  Swift 等待任务取消不会杀死 worker，提交后的 takeover/finish 继承上游 checkout 锁，
  Desktop 退出只关闭控制管道，不向已提交的子进程发送信号。进程退出码仍交给独立完成判定。
- 官方收尾隔离演练使用暖工具缓存和合成 journal/backup：SIGKILL 于 ready 后的同一事务
  `eb39aa59-aec7-4f7c-902d-7810aac7ab61`（官方 ID `295aca08-62fc-4ce9-adbe-de57a1989151`）
  经 `resume-finish` 进入真实官方 finish，原 handoff 字节和身份保留。另一个合成 checkout 的
  普通 update `64b9f2c1-757b-4eed-881d-e3fdfb0dd64c`（官方 ID
  `fbc10770-831c-405e-ab97-6c850dcff0b5`）实际执行固定 17b5df02→818c13be 移动及新的官方
  prepare/finish 子进程入口，均 exit 0、版本 0.21.6，但 sandbox-denied gateway_restart 仍欠账。
  finishing checkpoint 独立拒绝重放；两次 CLI 重试还被 Git 配置检查拒绝，官方维护的历史
  扩展尝试留下 partial-clone 元数据。当时的旧 guard 拒绝该配置；后续只读验证已接受上述
  标准形态，不删除 flag，也不允许校验阶段补对象。完整升级认证仍待完成。
  这些新演练没有真实旧 venv、实际一致备份、用户历史、GO 服务或业务验收；注入备份 hash
  只测试 worker 的 journal 绑定，不是有效恢复点。原完整旧 venv/合成历史演练见下文。
  partial-clone 增量后的两个新隔离 run `e0ffce7a-d439-430a-87d7-b6cf5911258a`
  （官方 `2845d36d-23d0-41f3-86bb-0d78377c73d1`，SIGKILL→resume）和
  `c798cc95-04bd-4517-b9d7-e82a3e86fe73`（官方 `c8e3c98a-f2fc-4d7a-9837-1bf0eed48bde`，
  普通 update）均完成真实官方 PM/finish；收尾实际留下 origin/blob:none 配置。随后源码离线
  校验通过且配置原样保留，真实 CLI 重试到达 `finish_checkpoint_not_ready`，原上下文/回执
  和 gateway_restart 欠账保留。仍采用上述暖缓存/注入 journal/backup 边界，不认证机器恢复。
- 真实 macOS 进程矩阵已验证：GO bridge 排斥另一个官方 updater，同 checkout 的不同 home
  及共享 Git common dir 的 linked worktree 也不能绕过；真实锁主进程被终止后，官方 post-commit 子进程仍持有 checkout/delegate custody，
  子进程退出后锁可重新领取。CI 将同一矩阵运行在 Ubuntu 和 macOS；这不等于完整升级终止矩阵。
- 退休后的 WAL 数据库可能没有侧文件，但主库头仍标记 WAL。Apple SQLite 只读 backup 曾因此
  失败；仅当 WAL/rollback journal 均无内容时使用 immutable 只读 URI，非空 WAL 仍用正常 backup。
  专项回归复现旧行为失败，原有非空 WAL 合并及后续写入保护测试继续通过。
- Connector 现有入口已接入可选的本机私有维护 mailbox：HTTP、上传 start、WebSocket open 和
  legacy chat command 在每次接入前检查同一 pause；已接收的 HTTP/上传/连接继续排空，取消和 ACK
  仍可送达，不重放请求。observer 停止并在 release 后重连，状态/契约后台探测也受维护门禁控制。
  请求 0600、目录 0700；软链/硬链/重复键/未知 schema/不可读记录拒绝接入，不以超时或删除解除。
  损坏记录使当前 Connector 进程保持拒绝接入；纠正私有记录后还需明确恢复/重启该受管进程，不能
  靠替换 release 文件隐式解除故障。本轮没有为此执行现用服务操作。
  ACK 固化 PID、进程 instance UUID、run/source identity、请求字节 hash 和活动数量；Desktop 要求
  同次 run、同一进程和 5 秒内的零活动 ACK。ACK 仅证明 Connector 已排空，不证明 CLI/cron 空闲。
  `HERMES_GO_MAINTENANCE_DIRECTORY` 只在显式准备的新版 Connector LaunchAgent 中配置；默认 nil，
  现用 plists 不被自动改写或重启。旧 Connector 没有 ACK 时升级必须阻断，不能按版本猜支持。
- 新服务恢复模块在 home 外按 run 保存原两个 GO LaunchAgent 的完整私有字节、digest、原加载状态、
  PID 与 kernel birth；同次备份不可替换。具体 launchd driver 只操作预先验证的两个 GO label，
  `print` 未知失败不视作 unloaded，拒绝 PID 再用、外部 job、legacy 重复 Connector 和修改过的 plist。
  停止前逐次要求全 profile 的新鲜 idle/独立 writer admission 证据，停止后仍要求退休证明；没有
  实际清点适配器时这些证据不能由版本、源码或 ACK 代替。恢复保留四种 loaded/unloaded 组合，
  Hermes 先恢复并独立核实实际 boot commit，再恢复原已加载 Connector。已恢复的服务续作不重复 bootstrap；
  恢复期间新出现的原 unloaded job 或最终加载集合变化会保留恢复游标，不接管、不停止该新服务。
  本模块不覆盖上游自建服务、服务定义变化或 home 外解释器闭包，已由后端 Machine Operating 编排连接；已增加原生只读观察，完整活体接入关闭/任务/退休证据仍未实现，app 不创建此执行适配器。
- 本地恢复/PM/boot 与无欠账证据齐全后，verifying 阶段可显式 release 接入以获取新鲜 Cloud/业务
  验收；验收失败需重新 pause 同一 run，并保留期间的新写入。取消必须先证明原源码/依赖/boot/加载集合，再 release 做业务验收，成功后才记 cancelled；
  失败重新 pause 同一 run，保留新写入与 cancelling 游标；关闭窗口、过期或删除文件都不是 release。维护拒绝使用已登记双语 `HR-HERMESUPDATE-005`，
  Android/Web 的历史、新建和发送入口识别它并不提供立即重试；Android 上传保留结构化 code，日志不记录原响应正文。

- Machine Operating 编排把实际私有备份、服务快照/恢复、mailbox 与官方 worker 连接起来；worker
  lease 由协调器持有，适配器只保留弱引用，释放不发进程信号。持锁后再次核对官方 plan；重启时严格
  读取同次 run 的私有配置与原上游 ID，不生成替代收尾身份。源码仍在原 commit 走普通 update，已经
  在目标则由 worker 判断能否 ready-only resume-finish；其他源码拒绝。备份前后独立退休证明、home 外
  解释器/工具恢复闭包、本地完成证据和新鲜业务验收都是强制接口，没有默认“安全”证据。真实 collector、
  完整尾部恢复与生产认证仍未完成，默认认证路径为空，app 不启用执行。

编排增量新增 16 项自动测试，实际使用临时 journal/mailbox/服务快照/完整 home 备份，worker、进程、
服务命令、官方 plan/receipt、boot、依赖闭包和业务证据均为合成。覆盖上一终态归档后的新 run、计划变化/无效 profile、ACK 不能
代替 writer 证明、闭包缺失、原安装取消/失败续验、目标收尾续作、业务失败重新暂停并保留后来历史、
回执欠账和损坏备份阻止恢复。另 3 项测试检查私有配置完整往返与 schema/字段/目录/重复键/权限/链接
拒绝。这不等于活体机器流程或服务恢复认证，现用 Hermes 未被停止、更新或重启。

2026-10-14 原生只读检查增量：真实 sysctl 两次清点当前 real/effective 用户的 PID、parent 和 kernel
birth。KERN_PROCARGS2 只解析 argc 参数，不解析环境尾部；原始命令/环境不进入报告。按 checkout
路径、Hermes 入口/模块保守识别候选及其子孙，不能定位 home/profile 的也保留为未知候选，不猜 default。
同一来源/新鲜度、PID/birth、完整可读且稳定的采样，是额外阻断检查的条件。已有“检查升级条件”
后台采集观察，更新/恢复中跳过；Machine 的生产构造默认接入，逐次 stop 前核对候选是否仅为原服务，
独立退休证明之后及备份前后/源码移动前再拒绝残留/新候选，不能用此观察替代原独立证明。
profile 目录只读、非递归、最多 128 条；链接、异常名称/权限或目录身份变化标为未知。目录包含 ghost/
已删除壳，不是官方 live fleet，不读配置/cron/数据库正文。旧 Python 链接只观察内外位置，PM 仍未知。
全部任务空闲、未来 writer 禁入、orphan/custom-home/其他用户/FD 覆盖与外部依赖恢复闭包仍未证明；
报告的 admission/task-inactivity/dependency-recovery proof 全为 false，不认证目标、不开放执行。
新增 19 项测试含一项真实一次性 Python 进程：kernel 身份/参数关联、退出后消失、报告不保留合成
命令/环境值通过；其余使用注入 kernel/服务证据与实际临时目录。专项合计 69 项，不代表真实 Hermes
任务退休或服务升级验收。

维护增量自动验证使用真实临时文件和真实 Connector 子进程/loopback Relay/HTTP：启动时暂停四类新
请求，维护开始后已接收的流式响应继续完成且 ACK 在结束前非零；原入口回归在 73746f52 上失败，
新入口通过。Swift/Node 共享同一请求 fixture 校对字节，验证过期/跨 run/跨进程 ACK、私有权限、
全部 profile/cron/后台任务/未知 writer 阻断，以及各原加载组合、取消、服务恢复失败续作和错误 boot。
launchd 操作与 writer/boot 证据使用注入 runner/适配，不是对真实服务的停止、重启或联合回退验收。

2026-10-11 隔离 macOS 演练：在一次性 HOME/HERMES_HOME、清空继承凭据、固定 818c13be 源码下，
实际执行官方 `_update_takeover.prepare`（PM）与 `update_finish`。PM 选中 Python 3.14.7 managed
环境并完成同步；finish 完成构建/维护后 exit 0、receipt outcome=success，但 gateway_restart.incomplete
及 gateway_restart followup 仍存在。prepare 使用清空后的环境和临时 home；finish 子进程额外使用
内核沙箱，显式禁止所有信号、launchctl/osascript 及临时目录外写入，
所以服务恢复被按预期拒绝；这验证欠账识别，不证明真实服务恢复成功。首次使用了不匹配的 PM
correlation id 被官方收尾拒绝；修正为同一 update_id 后完成该演练。该临时 home 没有真实旧 venv
或用户数据，不能称为完整 0.21.3 → 0.21.6 升级验收，也没有认证该目标或执行现用安装升级。
完成回执测试保留此次演练的字段样本，验证缺少 GO correlation_id 和补齐关联后的恢复欠账均被拒绝。

随后完成真实旧安装的隔离源码/依赖/历史演练：官方旧 installer 在一次性 home 中创建 0.21.3
venv（Python 3.11），仅加入合成会话；旧安装先经本仓库完整 home 备份，再由 worker 移至
818c13be，保留 `hg199-fixture-stable` 分支，官方 takeover 选中 Python 3.14.7 并完成 PM/finish。
首轮 tagless 快照产生 unknown 版本，修复后从官方精确 tag 补齐版本身份。修复后的重跑使用
已封存的真实旧安装备份及公开工具缓存，不是冷缓存安装或外部解释器联合恢复认证。
GO run `61676BC5-EFE5-40FB-A8A8-6B75C64C794D`、官方 update
`5D560FC1-B77B-44D9-A493-00EE464789CC` 的终态回执版本/提交/PM 均匹配。
升级后的首个检查脚本因遗漏新版 `hermes_bootstrap` 环境激活而失败；补上官方激活入口后，
`HG199_REHEARSAL_VERIFY_ONLY=1` 沿用同一已提交事务、回执和密封备份继续验收，没有重跑或替换
官方上下文。旧历史读取、新历史写入、独立目录备份导出及原库新增会话保留均通过。
这两段执行验证了源码/依赖/历史路径，不是一次从头全绿的冷安装测试。整个更新链都处于
禁止信号、服务工具及 home 外写入的沙箱；gateway_restart 欠账和 GO updating 日志明确保留。
服务恢复、boot/业务链路仍未认证，生产认证列表继续为空，现用 Hermes 没有被升级或重启。

以下执行适配与验收仍待实现/验证，HG-199 保持进行中：

备份工具当前覆盖 Hermes home 内的文件；原 GO LaunchAgent/加载状态的私有快照与恢复模块已具备，
完整恢复点仍须由机器适配器接入并验证所有实际运行服务，
并验证 home 外的完整解释器依赖闭包（显式目录的独立实物归档/导出已实现，旧 venv 仍可能链接到用户 uv 的 Python）。保存链接本身
不能保证外部解释器以后仍存在。因此单独的 home 备份不构成代码/依赖/服务联合回退认证。

1. 用户确认精确目标 version + commit；继续前重查身份、工作树、运行中请求、未知服务/布局、
   空间和并发操作。默认发现 stable，保留用户已有更新频道设置；远程发起另行授权。
2. 用现有 Desktop operation lease 与上游 checkout/update lock 串行化；写 owner-only 的耐久日志。
   未完成日志必须先阻止所有自动启动/重启，包括未加载的 bundled agent。
3. 暂停 Connector 接入，验证所有 Hermes writer 真正空闲并停止。备份整个数据/config/cron/profile
   及启动状态；SQLite backup 必须包含 WAL 已提交数据，独立校验 hash/quick_check/会话数量。
4. 旧 0.21.3 CLI 没有安全的精确目标参数。固定官方 source commit 后，0.21.6 的 historical
   takeover/PM/completion worker 已实现，隔离源码/依赖/历史路径已演练，仍须完整机器适配认证，不能替换成 pip + Git。官方 exit 0 可能仍欠
   completion/fleet followups，必须核验 receipt、激活代次、boot commit 与兼容验收。
5. 恢复原先加载的已识别服务，验证历史、发送/流式/取消、附件、approval/clarify、cron 和端到端
   连接。按安全阶段取消；中断后从同一日志恢复。代码或数据库已变更后不自动覆盖新写入；旧代码
   回退和数据恢复分别决策，保留备份与诊断。

需要真实官方更新器演练：旧版 → 固定目标、干净/脏工作树、活跃会话、并发更新、PM 同步失败、
各提交边界终止进程、收尾欠账、服务文件恢复和更新后的新写入保护。未通过前继续禁用自动升级。

## 阶段三：持续矩阵与跨端验收

已增加 pinned 0.21.3/0.21.6 源契约 + 样本数据库矩阵及定时 upstream main 的源码预警。
后续补目标稳定版自动发现、实际 updater/进程恢复矩阵、真实 API/Q&A/流式/附件链路，
Android/Web 按 profile 消费能力并逐项降级，及 Mac/手机验收。主分支预警不自动升级或推荐版本。

本地只读源/离线样本检查（Python 3.11+，测试 venv 依赖在 scripts/compat/requirements.txt 固定）：

```bash
python -I -B scripts/compat/hermes-matrix.py --old-root <downloaded-old-source> --candidate-root <downloaded-target-source>
python -I -B -m unittest discover -s scripts/compat -p 'test_*.py'
python -I -B scripts/compat/hermes-update-lock-matrix.py --candidate-root <pinned-target-git-checkout>
```

脚本拒绝当前用户 `~/.hermes` 内的源码路径。数据库只在随机临时 home 中创建，所有子进程清除
继承凭据/配置；备份恢复使用另一个文件，不覆盖迁移后的样本。源码检查覆盖 42 个 REST 路径、
29 个 RPC 参数集合与两个显示常量；它不启动服务器，不证明实际服务、业务语义或全量真实数据安全。

维护期间，已连接的 JSON-RPC 通道也拒绝新任务（Connector `-32002` / `HR-HERMESUPDATE-005`）。
既有任务的回答、审批和中断仍可继续；整个通道到实际关闭前一直计入 busy，不会因“当前没有请求”就
宣称已排空。不能解析或关联的帧不转发，保留通道以接收既有任务事件；正式升级适配器仍需让客户端
结束连接并独立确认全部 Hermes 写入者已经退出。

### 2026-10-14 主干兼容整合

草稿与主干 HG-212 等改动冲突曾使 GitHub 不调度 PR 检查。本轮在任务分支整合已全绿的
main e473bec3713294ecf29cff374970394bd80f478b，保留主干已发布版本值，不分配新版本。
保留 PM 发布入口的有限启动行为；未知/过期 PM 元数据不阻断正常启动，但
`upgradeDependenciesVerified` 要求严格依赖证据，不能由启动资格替代。未知源码身份仍阻断。
主干 Connector 的限时/可取消轻量健康探测保留，并纳入维护接入计数：暂停拒绝新健康读，
已接收但未读完的响应仍计 busy，到真实完成/取消才释放。测试 mailbox 改为与 Desktop 相同的
原子 rename 发布，避免另一个进程观察到测试夹具的半写入，未放宽生产私有文件校验。

### 2026-10-20 已准备 PM 目标的计划绑定诊断

仅兼容 harness 新增 `hermes-maintenance-target.py` / `pm_target.py`，不导入 Desktop。调用方必须
单独提供私有规范计划及预期 run/source/计划 SHA；不能把当前观察、布尔值或日志当作授权。
检查当前双域意图、源输入、精确环境/Python base 身份与内容、版本和固定策略，并实际重查。
真实隔离 PM 夹具的准备策略通过；缺激活戳的激活策略、错版本、错内容摘要、错计划 SHA、错 run
共五次拒绝。意图未变，释放全部检查锁后 writer 仍被阻断。已准备夹具的测试基线不证明生产
计划来源、同步前目标决策或耐久恢复授权；没有新装 PM、实际旧/新源码转换或服务/业务验收。
详细格式、命令及边界见 `scripts/compat/upstream-maintenance-prototype/README.md`。

本轮新增 13 项合成回归，CI Linux 全量发现/macOS 专项均纳入。目标诊断仅改 harness/CI/文档，
不改产品行为/协议/UI；后续主干整合基线单独记录如下。未分配版本、交付应用、合入 main、
发布或操作现用 Hermes。
升级执行继续关闭，完成/授权/耐久交接/认证结果均为 false。

同日为恢复草稿 PR 的检查调度，在任务分支整合主干 e45917a1（其 CI/SAST/OCI 均已成功；
9 项检查中 5 项成功、4 项按组件选择跳过）。唯一文本冲突为 Connector import，保留 HG-199
维护准入及主干 HG-219 心跳诊断两个模块；自动合并部分保留 HG-215 历史准备与 HG-216 账号
同步，并核对 Android/错误码/Web 的维护行为仍在。Connector 沿用主干 0.1.15，不新分配版本。
整合测试暴露 health 夹具先删除 mailbox 再停止子进程的 ENOTEMPTY/遗留进程竞态；改为单个
清理 hook 先等待自建子进程/服务器结束，再删除目录，原失败用例已转绿。新增检查不改变生产
Connector 行为。Node build/test 通过：Protocol 16、Connector 175、Gateway 248（31 跳过）、
release-server 37、scripts 429（1 跳过）；脚本加密恢复测试需本机已有 OpenSSL 3，系统 LibreSSL
导致的两项解密失败在仅对测试命令设置 PATH 后转绿，未修改全局环境。Android 2534 项 JVM/
assembleDebug 和 Web typecheck/943 测试/build 已通过；L2
A5DN9X4708H02629 未授权，L3 Pixel_API_37 可用但未运行，本次没有新布局或平台行为修改。
Desktop 本地基线未重跑，仍需当前 PR 的 CI 门禁；无安装、服务重启、APK/app 交付或发布。

### 2026-10-20 检查进程中断后的原计划重查

新增仅限隔离夹具的 inspection-recovery harness。监督方保留原外部计划、关闭全部维护 FD，
在三个检查点分别终止自建检查进程；重新取得检查锁，复核意图不变及普通 writer 仍阻断，再
启动新解释器，使用私有只读/unlinked 计划 FD 重新读取真实 PM 环境。检查返回必须形状完整、
退出 0 且无额外输出；旧成功 JSON 不作为重查输入，错误 run/独立 SHA/代次摘要均拒绝。
真实 Mac 夹具完成 3 次 SIGKILL、3 次新进程匹配、3 次拒绝；Python 3.14.7/packaging 26.0 的
已准备代次未改动，未再安装 PM，激活输入仍 false。新增 10 项测试的运行时报告为合成数据，
但锁和死亡进程真实；包含死后依赖漂移拒绝及保存结果会漏检的反例。全量兼容基线为 133 项。
固定输入与命令见 `scripts/compat/upstream-maintenance-prototype/README.md`。

该矩阵只验证只读检查中断、监督方一直存活、检查点的观察子进程均已退出；不代表官方 updater/
PM 同步中断恢复、真实旧→新转换、耐久生产授权、服务/业务或依赖闭包通过。所有授权/完成/
交接/认证以及 updaterRecoveryVerified 仍 false。此次只改 harness/CI/文档，本地 Desktop、
Node、Web、Android L1/L2/L3 未重复运行；未升版、交付应用、合入 main、发布或操作现用安装。

### 2026-10-23 固定源码计划与真实 Git 切换

新增 `hermes-maintenance-source.py` / `source_transition.py` 与测试 actor，在新建标记夹具中
验证真实固定 17b5df02→818c13be Git checkout。变更前从两份独立干净源固定完整 commit/tree
身份和四份共享入口摘要；目标摘要必须与目标 Git blob 匹配，再向原双槽意图追加计划并同步。
切换后新协调方验证原意图+计划及真实目标，不把旧记录替换成新源码记录。完整计划、完整
切换后和首槽部分发布后的进程死亡分别验证；六种错误预期/旧阶段/目标篡改拒绝，部分记录
保留。没有 finish/clear/repair/开放准入，全部普通 writer 仍阻断。

新增八项合成 Git/真实锁与进程单测，完整兼容基线为 141 项；目标 hash 不一致回归先在旧实现
失败，再加入变更前对象核对后通过。Linux/macOS CI 另使用两份固定真实上游 checkout 跑矩阵。
此实验执行 Git 与自有原型，未执行上游 Python、PM、官方 updater 或服务；不认证中途 checkout
恢复、真实运行时/依赖代次、跨监督方死亡授权、原服务/业务/全 writer 或升级完成。实验只改
harness/CI/文档；后续主干整合基线单独记录如下。无现用安装操作、升版、应用交付或发布。

本机固定源矩阵实际通过：3 次自建协调进程 SIGKILL、6 次错误身份/阶段/内容拒绝，原意图历史/
inode 保持、部分计划保留、普通 writer 始终阻断。旧临时 Git 缓存已经失效，因此在新的私有
目录准备固定 commit 与完整 tip 对象；实际使用的两份源码均经 worker 完整源码守卫通过。
没有用残缺缓存降低校验，也没有执行上游代码/PM/updater 或服务。

同日为恢复草稿检查，在任务分支整合主干 2d15e2e7：当前 9 项检查中 7 项成功、2 项按组件跳过，
CI/SAST/Website 三工作流成功。保留主干已有版本值与下载/视频/历史功能，未分配版本。五处
文本冲突保留维护错误及主干分页错误映射、两套历史测试和文档；Android/Web 内容选择遵循
主干的 `display_content`（空串亦有效）优先，再用 GO body/raw 回退，Connector 从同一显示
内容推导回退，避免旧压缩载体复活。三端扩展回归通过；Connector 新回归先在旧投影实现失败，
调整后 11 项专项通过。未改变 wire schema/错误码意义或视觉设计。

整合后本地 Node build/test 通过（已有 OpenSSL 3）：Protocol18、Connector179、Gateway254
（32 环境跳过）、release-server41、scripts436（1 环境跳过）；Web typecheck/946 tests/build；
Android2634 JVM/assembleDebug。L1 JVM 已跑，L2 A5DN9X4708H02629 未授权，L3 Pixel_API_37
可用未跑，无新增布局或 targetSdk 变化。Desktop 源码未改，本地未重跑；全草稿 CI 另核对。
独立下载通道的维护/排空未做新增专项，不能把保留其主干行为算作全 writer 证明。无应用交付。
