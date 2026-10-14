# Hermes 持续升级（HG-199）

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
- Desktop 设置的“本机 Hermes”卡片显示安装版本、提交、上游稳定版本、GO 验证推荐和检查时间。
  官方 release tag 解引用到固定 commit，不能用 `target_commitish: main` 当升级目标。
  检查失败清除成功报告；GO 索引没有配置仍可单独检查 Hermes。没有完整验证结果时不提供执行升级。

本阶段的 verifiedTargets 默认空。源码兼容检查或样本数据库检查通过，均不足以加入推荐列表。

## 阶段二：升级执行和恢复（事务基础已实现，执行适配未开放）

目前产品增加只读“检查升级条件”：识别 dirty/unknown 工作树、官方更新进行中、未完成或
不可读的 GO 升级日志，检查过程中不修改 Git index。它不是任务退休/停写证明，不授权执行。

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
并验证或另存 home 外的解释器依赖闭包（旧 venv 可能链接到用户 uv 的 Python）。保存链接本身
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
