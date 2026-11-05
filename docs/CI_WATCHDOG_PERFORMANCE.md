# CI 安全验证提速与 SDK 缓存核对 · 2026-11-05

采用 watchdog 安全验证的有限并行，保留全部真实测试与原预算；不加入 SDK 缓存目录预创建。
本轮只改构建验证调度，不改客户端代码、普通单测 fork/heap、600 秒硬终止、版本或发布授权。

## SDK 目录：新 runner 原本就能命中

[run 42008033094](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/42008033094)
在两个独立 Ubuntu runner 上复核配置缓存。复用普通 CI 原有的加密配置，只读恢复既有 main
缓存；密钥通过 setup-gradle 的既有参数使用，不读取、输出或上传密钥/配置缓存。
缓存位置由项目实际 common 32.1.1 的 AndroidLocationsSingleton API 计算，没有猜宿主目录。

使用与普通 CI 相同的测试任务请求，加 `--dry-run`，**不执行任何构建任务或测试**。
本地最小 Gradle 9.6.1 夹具先证明 dry-run 使用同一配置缓存键；两个 GitHub runner 也真实命中
既有 main 配置。每个阶段都是同样的 49 个 dry-run task，不替代任何常规检查。

| 阶段 | runner 1 | runner 2 |
|---|---|---|
| 全新 runner、SDK cache 不存在 | **复用**，10.759s | **复用**，9.106s |
| 随后显式创建 SDK cache | 失效，15.398s | 失效，14.736s |
| 同一 runner 再运行 | **复用**，6.998s | **复用**，6.393s |

此前的目录创建失效是同一 runner 从“目录不存在”变为“存在”的状态变化。新 runner 恢复的
条目记录的是初始不存在状态，仍可正常匹配；不能将同机第二次构建的失效直接当作新 job 的
瓶颈。预建目录还会使现有条目失效。本轮不加入额外步骤或偏好目录重定向；时间包含启动与
状态预热差异，不把阶段时间差当作稳定提速率。新 runner 的两次命中也不是所有未来缓存的保证。

缓存加密与还原边界见 [setup-gradle v4.4.3 官方说明](https://github.com/gradle/actions/blob/v4.4.3/docs/setup-gradle.md#saving-configuration-cache-data)。

## 安全验证：同一 runner 六轮完整对比

[run 42009376228](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/42009376228)
固定源码 `6ad8a08c381da4414d56e3a8d11182bd273b4799`，Ubuntu runner、4 个可用 CPU、JDK 21。
顺序为串行、并行、并行、串行、串行、并行；每轮都真实执行相同的六个用例，没有缓存结果。
串行每轮编译一次夹具；并行每个子进程独立编译夹具，额外编译成本同样计入。

| 执行方式 | 三次完整耗时 | 中位数 |
|---|---|---:|
| 原串行 | 17.752s、14.649s、14.747s | **14.747s** |
| 有限并行 | 8.111s、8.100s、8.105s | **8.105s** |

该验证步骤的中位数减少 **6.642s / 45.04%**。这是单个步骤的一台 runner、每组三个样本，
不声称整个合并/发布流程同比例提速。首个串行样本较慢，保留原始数据，不删除冷启动成本。
六轮均为六项成功、零跳过；检查每个公开用例身份及 unittest 完成状态，失败/不完整样本不计收益。
没有缩短 3/6 秒合成测试预算，实际 app worker 的 600 秒预算也完全保留。

`run_tests.py` 从原模块的标准 unittest suite 发现所有用例，包含未来新类，保留重复身份；
最多 `min(3, 可用 CPU, 用例数)` 个独立子进程，长计时用例优先启动。子进程使用 argv，
不是 shell；每个仍通过原 `test_watchdog.py` 执行原测试。任何失败、缺失摘要、跳过或用例数
不完整都阻断 Gradle gate。单核走原完整串行套件，不承担逐例编译开销。

Gradle 的 `verifyTestWatchdog` 仍无 outputs/跳过条件，每次 JVM 测试前执行；没有缓存验证结果。
独立进程减少 fixture 共享，额外 JVM/编译资源成本由三并发上限约束；小宿主不承诺同等收益。
原串行命令继续可用于人工诊断。

## 验证

工作流接线回归先在原命令下失败，再在新命令下通过。调度回归覆盖完整发现（含新增类）、
并发上限、单核串行路径，以及子进程失败/缺失摘要/跳过不能被成功退出码掩盖。
单并发回退还真实执行了全部六个 JVM 用例并成功，不只做模拟。

本机通过新 Gradle 接线真实执行六项安全验证及全部 **2,934 项 Android JVM 测试**，零失败/
错误/跳过，身份哈希与 #735 一致。profile 中安全验证 7.397s、测试任务 65.000s；本机资源、
缓存与 fork 数不同，不能拿来与 GitHub 或旧本机总构建直接计算提速。
本地网络启用 Node 基线 1,040 通过 / 21 条件跳过；聚焦调度/工作流 16 项通过。
仅 L1 JVM/渲染；没有 L2/L3、assemble/package gate、签名、APK 分发或生产发布。

两份临时工作流已从最终分支移除，原始日志保留七天。永久汇总见
[ci-evidence/2026-11-05-watchdog-performance.json](ci-evidence/2026-11-05-watchdog-performance.json)。
