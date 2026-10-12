# SSH 笔记本与 Hermes GO 手机同时访问：排查与恢复

HG-212，2026-10-12。客户确认笔记本走 SSH、手机是 iPhone 网页/PWA，具体 SSH 启动配置尚未取得。
客户已按本会话恢复步骤操作，并反馈重启 Connector 后 Desktop 正常。该反馈不等于 iPhone/PWA
与 SSH 同时使用已验收；本仓库迭代没有发布，也没有由代理直接修改客户机器。

## 后续已核实的恢复证据

客户当前源码为 `51609d827bb744f36c56adfd0f3249332ff946ec`，报告版本
`0.21.6+153.g51609d8`，源码无改动；旧 venv 仍是 Python 3.11.15。通过上游 PM 安装依赖后，
发布入口使用 Python 3.14.7，但 GO 原启动配置仍指向旧 `venv/bin/hermes`。备份后只替换 GO
受管入口为同一 checkout 的 `.hermes/bin/hermes`、移除旧 VIRTUAL_ENV 并保留 token/home/9119，
服务恢复监听。直接本机 `health`、`status` 和使用同一私有 token 的 `profiles/active`、
`sessions?limit=1` 均返回 200。账号链仍显示旧失败；客户重启 Connector 后反馈 Desktop 正常。

因此已确认“当前源码/PM运行环境与 GO 固定旧入口不一致”，以及恢复本机后 Connector 重启使
显示恢复。究竟哪次安装/更新触发、重启前 Connector 内部为何未恢复，仍没有足够证据。
`lsof` 的截断进程名 `python3.1` 不能用来判断是 Python 3.11 还是 3.14。

后续 Desktop 源码优先使用 PM 发布入口，安装后验证真实命令、激活时验证认证接口；诊断同时
刷新本机和账号证据，并提供身份校验后的 GO Connector 受控重连。发布此迭代前，现有客户端
仍需受控人工恢复；不要修改上游源码、PM facts、环境 generation 或另装一份 Hermes。

## 证据与限制

| 证据 | 结论 |
|---|---|
| 图片 1：`HR-CONN-006`；图片 2/3：Connector/Gateway 在线、Hermes 不可访问 | 故障在 Mac 本机后端路径；Connector 在线不代表 Hermes 健康 |
| `connector.log` 第 184 行起，10 月 9 日 06:20 UTC 开始持续拒绝连接 `127.0.0.1:9119` | GO 目标没有接受连接；不是端口被另一监听者占用的证据 |
| `hermes-server.error.log`：Python 3.11、`Generator` 参数不足、`Application startup failed. Exiting.` | 至少一次上游导入崩溃；日志无时间戳，不能把它与上述起点完全对齐 |
| 同一日志：更新仍在运行、依赖锁、旧 gateway 未重启的警告 | 更新完整性待核对；警告本身不证明后端必然崩溃 |
| 最后一次成功合同检查为 0.21.4 | 是历史健康版本，不是故障时版本的证明 |

附件已全量读取；客户实际提交、Python、SSH 入口及双端实测仍待核对。

## 兼容目标

遵守 [每台 Mac 同一份 Hermes](MANAGED_HERMES_STRATEGY.md)：共享标准 checkout、用户的
`HERMES_HOME` 和会话数据库，不另装代码或另建 GO 数据库。这不等于两端只能使用同一进程或端口。

- GO 的 `com.hermesgo.hermes-server` 运行标准 checkout 的
  `venv/bin/hermes serve --host 127.0.0.1 --port 9119`，Connector 转发到手机。
- 所核对的上游 SSH Desktop 在 `apps/desktop/electron/remote-lifecycle.ts` 的 `buildSpawnCommand`
  使用 `serve --isolated --host 127.0.0.1 --port 0`，通过 SSH 隧道访问动态端口，另有进程归属和
  token 文件。**这不是客户安装版本和配置的证明**，应以客户客户端为准。
- 两种入口可以共存。不要把 SSH 后端改成占用 GO 的 9119，也不要让它接管 GO 的 LaunchAgent。
  GO 的私有 Hermes token 留在 Mac，不交给笔记本或手机，不开放 9119 到公网。

上游参考：[SSH 后端实现](https://github.com/NousResearch/hermes-agent/blob/main/apps/desktop/electron/remote-lifecycle.ts)。

## 只读核对（客户 Mac mini 的安装用户）

记录笔记本客户端版本、SSH 选择的 Hermes 路径、profile、home、远端端口。只分享这些脱敏字段，
不提交私钥、密码、token、`.env` 或完整连接字符串。以下检查针对 GO 标准入口：

```bash
HR_HERMES_ROOT="$HOME/.hermes/hermes-agent"
git -C "$HR_HERMES_ROOT" rev-parse HEAD
git -C "$HR_HERMES_ROOT" status --short
"$HR_HERMES_ROOT/venv/bin/python" -c 'import sys; print(sys.version.split()[0]); print(sys.executable)'
rg -n '^Prelude =|^from typing import' "$HR_HERMES_ROOT/agent/turn_scripted_prelude.py"
launchctl print "gui/$(id -u)/com.hermesgo.hermes-server" \
  | awk '/^[[:space:]]*(state|pid|last exit code) =/ {print}'
lsof -nP -iTCP:9119 -sTCP:LISTEN
curl --noproxy '*' --connect-timeout 2 --max-time 5 -s -o /dev/null \
  -w 'loopback HTTP=%{http_code}\n' http://127.0.0.1:9119/api/status
```

入口或文件不存在就记录缺失，不猜另一个路径执行。对 SSH 实际入口也核对提交、Python 和 home，
避免终端入口与 launchd 的绝对路径不同。对外分享时遮去用户名和个人路径。
不要输出完整 plist 或 `launchctl print`；旧版可能含明文 token。

- 无监听且连接拒绝：查 GO 本次启动失败，不据此停止笔记本进程。
- HTTP 401/403：只能证明服务存在，不能证明 GO 认证及端到端健康通过。
- 9119 有未知监听者：明确归属后由客户决定，不 `killall`、不停止未知任务。
- SSH home/profile 不同：先确认用户需要的会话范围，不移动数据库或强行合并配置。

默认日志在 `~/Library/Application Support/Hermes Go/Managed/logs/`：`desktop-runtime.log`、
`hermes-server.log`、`hermes-server.error.log`、`connector.log`。按本次启动和同一时段核对。
Hermes 原始日志可能含对话和秘密，留在客户机器，不整份复制进聊天或代码仓库。

## 恢复步骤（须客户授权及服务窗口）

1. 记录上述状态，安排两端无正在执行对话的窗口，按客户备份规则保护配置、数据、启动配置；
   私有备份由客户保管。
2. 更新进程仍在运行时等待它退出。不要删除 update marker、依赖锁或绕过锁；遗留锁先核对进程归属。
   GO 的 marker 时间判断不等于更新已完成。
3. 用已核实的标准 Hermes 入口完成官方 `hermes update`，核对所选上游版本含修复。
   当前上游类型别名为 `Generator[tuple[str, str, dict], Optional[str], None]`。
   不强制把 Python 升到 3.13，不在 GO 中复制或热补丁修改 Hermes 源码。
4. 完成依赖安装，核对两端环境。包版本相同不证明导入正常，需要实际后端启动和请求；升级时运行
   [上游合同升级检查](HERMES_CONTRACT.md#upgrade-checklist)。
5. 受管服务操作前退出 Hermes Go Desktop（或持有受管 operation lease），避免它中途自动加载任务。
   只重启已确认归属的 GO 服务；官方更新也可能重启其他后端，应安排 SSH 重连。
   GO 不负责停止笔记本的任务或 SSH 会话。
6. 重开 Desktop，要求本次全新的 `HERMES_BACKEND_READY port=9119`、本机、Cloud、端到端全部健康，
   再在手机重试。只见 Connector 在线不算恢复。

参考：[修复后的上游类型别名](https://github.com/NousResearch/hermes-agent/blob/main/agent/turn_scripted_prelude.py)、
[Python Generator 规则](https://docs.python.org/3.13/library/typing.html#typing.Generator)。
第三个参数的默认值在 Python 3.13 才加入；客户日志证明两参数形式在其 Python 3.11 环境发生了错误。

## 失败与回滚边界

导入仍失败时检查实际代码、venv、更新结果，不反复手工重启或另装一份 Hermes。
端口冲突时保持未知任务不动；SSH 动态端口不需要改成 9119。
恢复配置失败时保留原始及恢复失败，恢复已验证且匹配账户/token 契约的启动配置；不手改 token 或删除 journal。
上游升级可能已迁移数据库，不任意降级源码、退回旧内置 Hermes或替换 `state.db`。
数据恢复遵循客户已验证的备份方案，与恢复 LaunchAgent 配置区分开。

## 仓库修复与客户验收

readiness checker 同时记录受管 stdout/stderr 的起始偏移，只识别本次新增的完整终止标记。
详情提供固定原因 `python-generator-parameters`、`application-startup-failed`、`port-in-use`；
常规 runtime 恢复仍用现有 `HR-MIGRATE-009`，外层恢复失败保留其原码。
不带原始 traceback、用户路径、provider 名称或 token；旧错误与非致命更新警告不污染正常新启动。
原有退避、回滚和 ready+health 双重证明保持有效。这是诊断改进，不会自行更新客户的上游代码/venv。

自动化覆盖 Python/generic 崩溃、端口标记、历史错误、非致命警告、半行写入、轮转、软链接、增长上限。
已有运行管理测试覆盖更新期间等待、占用端口拒绝启动、精确受管标签及回滚边界。

以下客户双端验收 **尚未执行**，记录客户端版本、提交、时间和结果：

1. SSH 和 PWA 分别连接同一 Mac，均能列出会话。
2. 各自新建不同测试会话并取得完整回答；关闭/重连一个客户端不影响另一个。
3. 同时连接重复测试，并检查同一 home 的历史可见；同一会话的跨客户端占用保护不等于连接失败。
4. 授权窗口内只重启 GO 受管后端，SSH 仍可用，GO 自动恢复且端到端健康。
5. 单独重连 SSH 后手机仍可用；另在授权窗口完整重启 Mac，检查两端恢复。
6. 失败时收集同一时段脱敏原因、服务状态、入口差异，不宣称 HG-212 已验收。
