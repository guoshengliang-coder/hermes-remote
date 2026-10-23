# HG-199：跨进程维护的上游协作需求与隔离原型

用户于 2026-10-15 确认准备上游跨进程维护原型，先做隔离验证，不创建上游 PR、
不操作现用安装。原型保存在本仓库兼容实验目录；未修改上游文件或采用线上协议，
没有停止现用服务。现有自动升级认证路径继续为空。

## 已核实的阻断

固定旧版 `17b5df02`（0.21.3）与候选 `818c13be`（0.21.6）的
`hermes_cli/backend_retirement.py` 都以进程内对象关闭 admission；官方 checkout/update lock
串行化 updater，不是普通 writer 的共享接入协议。ESTOP sentinel 暂停部分新 dispatch，
不覆盖全部 CLI/数据库写入，也不保证已接入工作排空。

`scripts/compat/hermes-maintenance-scope.py` 对两份真实模块各运行两个独立 Python 进程：
第一个 prepare/commit 后拒绝 acquire，第二个使用同一源码和一次性 HERMES_HOME 仍能 acquire。
idle ledger 在这个实验中注入为空；实验只证明锁作用域，未启动 Hermes 服务或业务任务。
结果明确保留 `globalAdmissionProven=false`，不能用于升级认证。该反例已加入固定源 CI。

所以不能把更多 PID 扫描、Connector ACK、单个 serve idle/retirement 或 `hermes pause` 拼成
全局停写证明。0.21.3 → 0.21.6 自动升级仍卡在这一门槛；这不是再补一个本地探针就能解决的缺口。

## 已授权的隔离原型

[`maintenance_lease.py`](../scripts/compat/upstream-maintenance-prototype/maintenance_lease.py)
提供 POSIX 协作锁参考实现；详细状态、复现命令、测试和接入缺口见
[`原型说明`](../scripts/compat/upstream-maintenance-prototype/README.md)。它没有接入 GO 或真实 Hermes 入口。

- home 与 checkout 各有稳定的准入/生命周期锁。维护请求先关闭准入，再非阻塞排空；
  不强杀已有 writer。取消释放自身描述符，继承的子进程继续持锁。
- 独立私有 registry 显式登记规范路径、目录及锁 inode；缺失/替换/不安全元数据拒绝使用。
  绑定 fleet home 由调用者负责，原型不从 profile 名称猜范围。
- 13 项真实进程/文件测试覆盖并发、不同 checkout/home、取消、父进程死亡/主动 close 后
  子进程持锁及身份替换。故意加入 LOCK_UN 后，两个父进程主动 close 的持锁用例失败。
- 固定 0.21.6 的真实 retirement 模块在隔离副本中验证：采用原型的入口被禁入，
  未采用原型的入口仍能 acquire。另有实际旧 writer 无视协作锁仍能写入的反例。
  `globalAdmissionProven=false`、`certifiesUpgrade=false` 保持不变。

这还不能证明真实 CLI/serve/gateway/cron、profile、后台任务或所有写入点覆盖。
跨重启耐久交接、真实 idle/排空、PM/服务恢复、首次旧版切换仍未实现/认证。

### CLI 启动前缀接入实验

`scripts/compat/hermes-maintenance-entry.py` 对固定候选的四个源文件先校验 hash，
仅在一次性副本中注入准入调用，比较 bootstrap 导入前与 `main()` 内两个位置。
三种代码加载形式 × 四组 argv，共12组成对场景：前置调用在维护中阻断且无 home 写入尝试；
`main()` 内调用尚未执行时，真实 bootstrap 已创建 `cache/scratch`。故意把前置调用后移后，
矩阵拒绝结果。获准启动的前缀在 probe 保留许可期间使维护方无法排空，退出后才能排空。

此实验止于真实 scratch hook 返回；PM准备/激活与中断恢复被注入为空操作，未运行完整入口、
服务、业务或实际 profile 选择。命名 profile 的 HERMES_HOME 由实验显式提供。
输出保留 `pmRecoveryInjected=true`、`fullStartupVerified=false`、`profileSelectionVerified=false`，
两项全局/升级认证 flag 仍为 false。新增7项 harness回归和源码入口路由表见原型说明。
没有可安装的上游补丁、launcher 或线上准入配置；真正 bootstrap/恢复交接和旁路覆盖仍待接入。

### 共享 bootstrap 与恢复角色

`scripts/compat/hermes-maintenance-bootstrap.py` 校验11份固定输入，在一次性副本中对比
main单点与共享bootstrap准入。CLI/legacy CLI/ACP/gateway × 三种加载形式共12组成对场景，
共享guard均在真实scratch前拒绝且home写入尝试为0；main单点漏掉9个其他入口前缀。
另有4个普通writer前缀和4个继承排他许可的恢复角色前缀，通过实际内核锁验证退出边界。

原型新增显式维护FD继承：复制描述符、核对当前域/锁对象与写访问，再验证排他flock。
恢复子进程沿原open-file description持锁，不在父进程排他门禁下重新申请共享writer许可；
父进程关闭自身副本后，普通writer和另一维护方仍被挡住，子进程退出后才解除。
只比inode而不验证flock、或把继承换成共享许可申请的错误实现均被回归拒绝。
新增8项测试；该方法是可信测试launcher的内核原语，不是已认证的事务交接。

此内核原语实验的PM/恢复仍为空操作，`authenticatedHandoffVerified=false`；它不校验
run/source/home/role交接，跨exec/重启持久身份也未实现。另已核对agent包在bootstrap前尝试原生预加载，
cron worker包初始化可在scheduler bootstrap前激活PM；`nativePreloadVerified=false`、
`cronPackageBootVerified=false`明确保留。共享bootstrap也不是所有入口的全局屏障。

### 活进程私有通道绑定实验

`scripts/compat/hermes-maintenance-handoff.py` 在上述11份固定输入的一次性副本中，
验证可信测试父进程与恢复子进程间的绑定。父进程显式传入匿名socketpair、关闭写端并unlink后
只读的私有context FD及真实排他锁FD；不是环境变量、可发现socket或保存的JSON回执授权。
context绑定run UUID、固定候选标识、实际home/checkout目录身份、入口、恢复角色、完整域记录及
11份变换后输入的实际hash。这里的候选标识不等于验证整个Git/runtime闭包，私有FD也不是OS密封文件。

子进程在导入任何复制包之前验证context和继承锁，以新challenge和父方transfer值完成
ready/grant/accepted/commit/committed。父方在确认前不释放原锁；子方在收到commit前不得运行前缀。
4个入口及显式命名profile共5个正例在父方close后继续排除两类外来请求。
13个拒绝场景覆盖run/source/home/role/入口/域不符、许可生成后和hello后源码变化、
challenge不符、accepted丢失/不符、缺失锁及同inode独立open；均未导入复制包、无home写入，
父方仍实际排除writer和另一维护方。accepted丢失由父方丢弃确认并关闭通道模拟；
challenge不符由修改本次challenge模拟，不宣称捕获了旧包或验证重启重放防护。

新增9项测试覆盖上述矩阵、包初始化前拒绝、实际文件校验的错误实现、私有只读FD、
文件/目录替换与链接、规范JSON/重复键/类型、消息上限/半帧/EOF/截止和双语错误脱敏。
Linux/macOS CI均运行测试及固定真实源矩阵。
`liveBindingChecksVerified=true`只表示活进程实验通过；`productionAuthorityVerified=false`、
`durableHandoffVerified=false`及全局/升级认证false保持不变。PM仍为空操作。
这不解决任意同UID恶意进程、Python启动钩子、完整入口/原生/cron/PM、服务交接、
跨exec/重启持久恢复或首次旧版切换。commit发出后若最终确认丢失，父方保留锁也不能推断
子方没有工作；这类不确定状态的耐久协议还需单独验证。详细复现与信任边界见原型说明。

### commit 不确定状态与进程死亡

`scripts/compat/hermes-maintenance-failures.py` 增加独立故障实验。发出commit前的失败与
尝试发送commit后的失败分别记录为`rejected-before-commit`和`commit-uncertain`；
即使send失败也可能只发送了部分字节，因此从第一次尝试发送之前就进入不确定范围。
最终确认丢失不能降级成“没有执行”，子方的成功状态也不能替代父方确认来释放父锁。
父方只在完整交接确认后执行原有close；不确定状态持续持锁到实验受控清理，不自动重试、取消或接管。

四个固定源入口各有两种故障，共8个前缀：commit未送达时子方未写入，最终确认被丢弃时
子方已执行真实scratch；两者在父方均为不确定，父锁在子方真正退出后仍实际挡住外来请求。
一次性错误实现改回按子方成功状态close后，这个“子方退出后”的检查实际发现锁空窗，正常实现通过。

另有9个真实SIGKILL场景使用纯合成父子actor，不执行Hermes源码：在ready、accepted、
commit接收后但确认前、获准并写入合成marker后，分别终止父方/子方；另在获准后先后终止双方。
监督进程不持有维护锁，保留独立控制管道。父死后子方持锁，子死后父方持锁；
发送最终确认失败的子方不执行合成前缀。已确认的子方也可立即死亡，所以交接确认不等于服务启动成功。
所有持锁者死亡后，真实writer可重新进入：`allCustodiansGoneAdmitsWriter=true`是耐久性缺口的反例。

新增6项测试覆盖合成矩阵、父方误释放的错误实现、commit发送失败分类、早期传输错误、
控制帧截止/角色范围和双语脱敏；Linux/macOS均执行固定源故障矩阵及合成死亡实验。
本轮不提供耐久journal/重启接管，也没有真实PM、原生、cron、服务、业务或OS重启验证。
`durableHandoffVerified`、`productionAuthorityVerified`及全局/升级认证仍为false；
后续必须让所有参与writer在重启后也遵守耐久维护意图，不能只保存FD号或从空锁推断事务已完成。

### 持久维护意图与新进程准入

`scripts/compat/hermes-maintenance-durable.py` 验证上述缺口的一项隔离修正。原型登记格式升为schema 2，
为home与checkout各预建固定inode、owner-only的intent槽并将身份写入登记记录。
该基础实验只有槽内容精确等于预登记的空闲记录才允许writer；空文件、部分/超长/损坏内容均阻断，
缺失、替换、链接或异常权限拒绝使用。不静默迁移旧schema或重新登记。稳定槽不unlink/替换/重置。
writer在全部共享准入锁内检查槽，再取得生命周期锁，避免在检查与维护发布之间越过门禁。

`durable_intent.begin`要求双域全部排他且已排空、当前控制对象和实际锁有效，
重新核对完整的run/source/home/checkout/入口/恢复角色/双域及11份变换后输入hash上下文。
仅当所有槽都空闲才逐槽写同一有摘要的规范记录，fsync每个槽和registry目录，
复核上下文/内容后返回。任何数据副作用必须在返回之后；中途失败无权执行。
部分发布可让精确双域writer继续被挡住，但不是原子多域提交：不包含已写槽的其他组合
可能继续接入，此时尚未授权任何数据变更。部分/损坏发布不能自动修补或接管。

发布进程在全部同步后被SIGKILL，监督方不持维护FD，新writer仍在真实scratch前阻断。
四个固定源入口各验证一次；新恢复进程重新取得双域排他锁并排空，以独立传入的私有context FD
核对同一记录，再同步/复核后进入scratch前缀。run/source/home/入口/角色/范围/源内容不符共28例
在复制包初始化前拒绝，竞争恢复也拒绝。恢复退出不清除意图，之后writer继续被挡住。
另在首槽同步后SIGKILL，writer被挡住、恢复拒绝且槽字节保持原状，共5次发布方死亡场景。

新增10项测试覆盖上述矩阵、跳过writer检查的错误实现、排空前置条件、精确已有意图、
空/半写/损坏/超长/替换/链接/权限/旧登记拒绝、partial write与fsync失败、共享home/checkout和
无关安装隔离、无效上下文及双语脱敏。跳过意图检查的临时错误实现允许writer重新写scratch，
正常实现阻断。Linux/macOS均运行单测与固定源矩阵；旧“没有发布意图时全持锁者死亡可进入”的
内核反例仍保留，不能把原始锁原语与本次持久准入机制混为一谈。

这只提供可信测试launcher下的进程级持久准入阻断；独立预期上下文由仍存活的监督harness保留，
不是从落盘记录反推用户授权，也未解决Desktop自身死亡后的授权来源。`durable_intent`没有clear/finish/reset API，
该基础实验未解决终态证明、部分记录修复、源码/依赖代次推进或跨exec的实际服务交接。
fsync成功不等于物理断电/OS重启验收；也不防同UID恶意重写控制面或回滚为空闲记录。
`processRestartAdmissionVerified=true`与`durableHandoffVerified=false`、`productionAuthorityVerified=false`、
`osRebootVerified=false`分别记录；native/cron/fullStartup/globalAdmission/certifiesUpgrade仍false。
生产认证列表为空，自动升级/恢复继续禁用。

### 仅限fixture的代次转换与前缀终态策略

`scripts/compat/hermes-maintenance-transition.py`进一步隔离验证一次已规划的转换：在已发布的意图后
追加旧/新上下文计划，再改变副本内容，最后追加测试终态。只允许`hg199-durable-*`临时根内
`control`、`data`、`checkout`的精确双域；策略固定为`scratch-prefix-fixture-v1`。
原意图、计划和终态保留为三条规范记录，双槽内容必须完全一致，不unlink、重置或修补。
新source是合成标签，源码变化只是副本中的注释；依赖变化只是显式hash绑定的文本标记，
不是新Hermes版本、真实PM同步、环境激活或完整依赖闭包。

计划在改变输入前以旧上下文和实际排他锁核验，逐槽追加并同步；改变后必须匹配计划内的新摘要，
不能靠改写旧context绕过源身份。测试launcher随后持原排他权启动独立前缀子进程，以私有计划FD、
新上下文及活通道核验后才导入复制包。监督方必须实际观察到bootstrap、一次准入、scratch写入及
子进程正常退出0，再复核目标，才能取得绑定当前持锁对象、同一计划、5秒内一次性的内存witness。
`finish_fixture`拒绝布尔值、保存的JSON、过期或别的持锁者的证明；一次提交尝试就消耗witness。
它只授权这个测试策略，不是生产终态接口。终态落盘后，新writer仍需在全部共享门禁内匹配
两槽完整历史、策略和当前目标输入，并自行同步双槽/目录后复核；可见记录不代表发布方完成过同步。
同步失败、恢复旧源码或旧依赖标记都会拒绝。共享其中一域的其他组合
不能借用这个完成记录。可信registry内的hash/nonce不是签名，也不防同UID恶意伪造或控制面回滚。

固定源四入口各验证计划完成后协调方SIGKILL、目标副本变更、重新核验前缀/提交终态后再SIGKILL，
之后普通writer才可运行；旧源码/标记回退拒绝8例。另在计划和终态首槽同步后分别SIGKILL，
部分记录继续阻断且不修复，合计10个协调方死亡场景。新增10项单测还覆盖无witness、过期/跨持锁者/
重放、错误子进程结果/非零退出、计划外变动、损坏历史、共享域阻断及双语脱敏。
删除witness检查的临时错误实现会在没有前缀验证时开放writer，正常实现拒绝。
Linux/macOS均运行这些单测和固定源矩阵。

本实验仅处理一轮fixture转换，不提供下一轮epoch/日志归档协议。完整计划提交后仍存活的监督harness
独立保存预期计划；尚未解决Desktop与监督方同时死亡后的授权恢复。验证后、终态前中断须重新观察
前缀，部分记录则保持阻断并待独立恢复设计。磁盘同步不等于断电/OS重启证明。
`fixtureCompletionPolicyVerified=true`与`realDependencyGenerationVerified=false`、
`productionCompletionVerified=false`分别报告；生产授权、完整耐久交接、真实PM/native/cron/service/
business/global/升级认证均未完成。一键执行和恢复继续关闭，不操作现用安装、不创建上游PR。

### 真实PM代次的独立观察（不提交终态）

`hermes-maintenance-pm.py`增加显式opt-in的macOS隔离实验。固定完整Git源码经既有worker校验后，
新建私有临时clone/home/tools/cache；发布双域持久意图后运行官方`sync_venv([], explicit=True)`。
不使用旧演练seed、现用安装或真实数据，不运行updater/finish/服务；内核沙箱拒绝目录外写入、
信号和服务工具。同步允许下载，观察阶段禁止网络；意图不清除、不提交终态。

观察器在真实排他权内校验三份固定PM选择源码，通过独立子进程读取实际committed_venv/facts，
限制安装key/代次/site/Python路径。再运行选中解释器`-I -S -B`，显式加入选中site-packages，
实际加载packaging.version并运行SSL/SQLite/hashlib；不执行.pth、不启动Hermes。
绑定facts、pyvenv.cfg、源激活输入、实际binary与环境/Python base两棵树的内容/元数据摘要，
限制65536项/2GiB/单文件512MiB/64层，拒绝外部链接、特殊文件、漂移和异常权限。
仅私有0700根内已知空uv .lock允许原始0666/nlink1且仍纳入摘要，不改权限求通过。

实际源码与演练确认底层sync不发布激活输入戳，完整CLI安装才负责该步骤。
全无inputs时明确`activationInputsCurrent=false`，不补造；已有戳不完整/陈旧或属于别的checkout则拒绝。
所以“选中代次实际可观察”不等于激活就绪。每次revalidate重新执行/读取，比对完整结果；
保存的JSON仅可比较，不是用户授权、完成witness或服务证明。显式故障矩阵在独立fixture内
改变测试依赖文件、facts空白与lock输入（保持mtime），三者均须使旧观察失效，恢复自有字节后再新验。

新增14项单测使用合成选择器/运行结果，真实PM不是CI的隐含前置条件。Linux/macOS另用固定真实
PM选择源码验证1个合成选择与3个拒绝，明确标记未安装PM/未执行选中解释器；实际证据仅来自Mac opt-in。
`selectedPMGenerationObserved`独立于旧fixture终态策略，后者的真实依赖/生产完成认证仍false。
本观察器不发布维护终态，不接入Operating Machine，不自动恢复；完整依赖闭包、PM子进程许可/
锁序与同步中死亡、独立目标计划及验收策略、所有writer/服务/业务和耐久授权仍待实现。

## 上游接入必须提供的保证

仓库既有边界仍是 GO 管进程、用户拥有 Hermes 代码；当前授权仅为隔离原型。
上游采纳、真正入口接入和现用安装操作不包含在此次范围内。以下保证不能仅凭原型测试代替。

1. **共享 home 的所有 writer 协作。** 在配置、迁移、SQLite、cron/profile 等任何写入前，
   CLI、serve、gateway、后台/子进程及外部 scheduler 都必须取得同一维护域的共享许可。
   不同 checkout 和 profile 指向同一规范 home 时也须共享；checkout 更新锁另行保留。
2. **排空后取得排他维护权。** 暂停新任务后确认所有已接入工作结束、相关进程退出或交还许可，
   updater 才能持有排他权。未来进程必须在启动写入前等候/拒绝，不能在探测与备份间越过门禁。
3. **许可贯穿写入生命周期。** 子进程/委派工作必须独立持有或明确继承许可；父进程退出、
   UI 取消、PID 再用或重启不能让活跃 writer 丢失登记。稳定锁对象不能靠删除文件解除。
4. **精确事务与恢复。** 维护权关联固定安装/home/源码及同一次 run；中断恢复沿原身份续作。
   官方 updater 子进程和被授权恢复的服务如何继承/交接维护权必须明确，不能为健康检查提前
   向所有独立 CLI 开门，也不能只有 GO 存活时才安全。
5. **旧版本不能被假定参与。** 必须识别协议能力和旧进程。未知/旧 writer 继续阻断。
   引入协议本身不能反过来证明首次从不协作版本升级已经安全；首次切换需另有受控维护范围。
6. **产品边界。** 不安装第二个生产 Hermes，不建立新生产数据库，不替换用户 launcher 或
   修改目录权限来模拟停写。现场维护、升级、服务恢复和发布仍走独立授权与验收。

原型和后续真实入口仍需分别验证以下矩阵；不向现用上游服务发送维护请求：

| 场景 | 必须观察到的结果 |
|---|---|
| CLI/serve/gateway/cron/不同 profile/不同 checkout 共用 home | 同一维护域，不漏掉旁路 |
| 工作排队、等待人工输入、异步委派和子进程仍活跃 | 排他维护不能成立，任务不被强杀 |
| 维护已取得时再启动一个 writer | 写入前阻断，不能只在启动后被扫描发现 |
| owner 被终止、子进程仍活跃、PID 再用 | 许可/身份仍可核对，不误报已排空 |
| 取消、超时、恢复原服务、业务验收失败 | 按原事务交接或继续阻断，不覆盖后来历史 |
| 同机还存在不参与协议的旧入口 | 显式未知/不支持，认证不通过 |

上游提案/原型不等于上游采纳；未发布且未验证的能力不能放入 GO 推荐或认证列表。

## 当前产品行为

2026-10-20 隔离原型新增“已准备目标计划诊断”：调用方单独提供规范计划、预期 run/source 和
计划摘要，核对双域当前意图、11 份原入口输入、6 份 PM 选择/锁输入、精确环境/Python base
身份与内容摘要以及版本；实际观察两次后复核事务。固定策略分别检查已准备代次、或额外要求
真实激活输入完整。缺少计划、错事务/错代次/内容漂移和激活证据缺失均拒绝；不补造激活戳。
真实临时 PM 夹具上准备策略匹配，激活策略及其他四个错误预期拒绝，意图字节保持不变，
退出所有检查锁后普通 writer 仍阻断。其计划基线来自已准备测试夹具，不证明生产计划的独立
可信来源或 Desktop 死亡后的授权；也没有执行真实旧/新代码转换、终态发布、服务/业务验收。
该结果不接入旧合成 finish、Operating Machine 或产品执行资格，所有完成/授权/认证标志仍为 false。

同日新增检查进程中断重查矩阵：存活监督方保留原外部计划及预期 run/source/SHA，自身不持
维护锁；三个检查点（计划核对后、首次观察后、完整重查后）各终止一个自建检查进程，确认
其锁已释放、双槽意图未变、普通 writer 仍阻断，再由新的解释器按原计划重新观察。真实 PM
临时夹具完成 3 次 SIGKILL、3 次重查匹配、3 次错误预期拒绝。10 项可移植测试使用真实锁/
进程与合成运行时报告，另验证完整检查进程死后发生的依赖变化会使重查失败；旧成功报告的
形状校验会漏掉该变化。完整结果仍只是诊断，不能凭记录或退出 0 开放准入。
监督方在此矩阵中始终存活，检查点没有活跃的 PM/观察子进程；未验证官方 updater/PM 同步
中断、真实旧→新源码切换、监督方死亡后的耐久授权或服务/业务恢复。激活输入仍为 false，
updaterRecoveryVerified/完成/授权/交接/升级认证仍为 false。详见原型 README 的复现命令。

GO 可继续完善版本/能力检查、恢复材料、独立备份导出和受控维护说明；自动执行保持关闭。
需要把“旧版可检测/可辅助维护”与“已经支持安全一键升级”区分，不能将人工确认当作内核停写证明。

2026-10-23 新增固定 Git 源码切换实验：从独立验证的 0.21.3/0.21.6 Git 输入预先生成双上下文计划，
绑定相同 run/home/checkout/域及真实 commit/tree、四份共享入口 hash；未来 hash 必须先与目标
Git blob 相符。原意图后追加计划并同步，不改写旧记录；真实 detached checkout 后新协调方匹配
原历史和目标源码。完整计划、完整切换及首槽部分发布三个点验证自建进程死亡；错误身份、旧
阶段复用及目标篡改拒绝。部分计划保留、不修补，普通 writer 保持阻断。八项合成 Git 单测另有
跳过实际文件核对会漏检的反例。该实验不运行上游 Python/PM/updater/服务，不证明切换途中
恢复、监督方死亡后的授权、全 writer 覆盖或升级完成；没有终态/清除/准入 API，认证仍 false。
