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

GO 可继续完善版本/能力检查、恢复材料、独立备份导出和受控维护说明；自动执行保持关闭。
需要把“旧版可检测/可辅助维护”与“已经支持安全一键升级”区分，不能将人工确认当作内核停写证明。
