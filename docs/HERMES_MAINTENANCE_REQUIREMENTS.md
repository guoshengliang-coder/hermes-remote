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
