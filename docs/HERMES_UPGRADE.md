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

2026-10-11 隔离 macOS 演练：在一次性 HOME/HERMES_HOME、清空继承凭据、固定 818c13be 源码下，
实际执行官方 `_update_takeover.prepare`（PM）与 `update_finish`。PM 选中 Python 3.14.7 managed
环境并完成同步；finish 完成构建/维护后 exit 0、receipt outcome=success，但 gateway_restart.incomplete
及 gateway_restart followup 仍存在。沙箱显式禁止所有信号、launchctl/osascript 及临时目录外写入，
所以服务恢复被按预期拒绝；这验证欠账识别，不证明真实服务恢复成功。首次使用了不匹配的 PM
correlation id 被官方收尾拒绝；修正为同一 update_id 后完成该演练。该临时 home 没有真实旧 venv
或用户数据，不能称为完整 0.21.3 → 0.21.6 升级验收，也没有认证该目标或执行现用安装升级。

以下执行适配与验收仍待实现/验证，HG-199 保持进行中：

备份工具当前覆盖 Hermes home 内的文件，完整恢复点还须由适配器保存原 LaunchAgent/启动状态，
并验证或另存 home 外的解释器依赖闭包（旧 venv 可能链接到用户 uv 的 Python）。保存链接本身
不能保证外部解释器以后仍存在。因此单独的 home 备份不构成代码/依赖/服务联合回退认证。

1. 用户确认精确目标 version + commit；继续前重查身份、工作树、运行中请求、未知服务/布局、
   空间和并发操作。默认发现 stable，保留用户已有更新频道设置；远程发起另行授权。
2. 用现有 Desktop operation lease 与上游 checkout/update lock 串行化；写 owner-only 的耐久日志。
   未完成日志必须先阻止所有自动启动/重启，包括未加载的 bundled agent。
3. 暂停 Connector 接入，验证所有 Hermes writer 真正空闲并停止。备份整个数据/config/cron/profile
   及启动状态；SQLite backup 必须包含 WAL 已提交数据，独立校验 hash/quick_check/会话数量。
4. 旧 0.21.3 CLI 没有安全的精确目标参数。固定官方 source commit 后，0.21.6 的 historical
   takeover/PM/completion 路径是需要演练的适配候选，不能替换成 pip + Git。官方 exit 0 可能仍欠
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
```

脚本拒绝当前用户 `~/.hermes` 内的源码路径。数据库只在随机临时 home 中创建，所有子进程清除
继承凭据/配置；备份恢复使用另一个文件，不覆盖迁移后的样本。源码检查覆盖 42 个 REST 路径、
29 个 RPC 参数集合与两个显示常量；它不启动服务器，不证明实际服务、业务语义或全量真实数据安全。
