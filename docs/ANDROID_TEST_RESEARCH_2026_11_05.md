# Android 单测耗时与稳定性研究 · 2026-11-05

结论：**保留当前半核数并行策略，不采用 4 worker**。同一 GitHub runner 的完整 ABBA 对比中，
测试任务中位数从 132.35s 增至 153.62s，慢约 16.1%。不删测试、不缩短业务边界等待、
不调整堆或 watchdog。没有升版、生成分发 APK、调用签名或发布。

## 实验方法

`scripts/ci/benchmark-android-tests.py` 仅用于隔离、无凭据的 Linux runner。
先关闭任务输出缓存和 configuration cache 编译一次，再依次执行完整
`:app:testDebugUnitTest --rerun`：原配置、候选配置、候选配置、原配置（ABBA）。
同一 job 中源码、依赖、CPU、内存和 JDK 保持一致。原配置为核数的一半，候选最多四核；
仅在至少 12 GiB 内存且候选确实增加 worker 的机器上比较。每个 worker 仍为 1 GB 堆。

每轮记录实际 fork/heap/watchdog 配置；KSP、主 Kotlin、测试 Kotlin 都必须 `UP-TO-DATE`，
测试任务自身必须真实执行。JUnit 的测试、失败、错误、跳过数量与所有用例身份（含重复）
必须一致，才允许比较。类耗时是 JUnit suite 时间，不能把并行类耗时相加当作墙钟时间。
失败或不完整轮次不计入提速。

`--rerun` 只强制指定测试任务重跑；`--rerun-tasks` 会重跑整张任务图，不适合隔离此处测试成本。
参见 [Gradle CLI](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:rerun_tasks)
与 [Test.maxParallelForks](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html#org.gradle.api.tasks.testing.Test:maxParallelForks)。

## 第一轮：原配置卡顿

[run 41988699636](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41988699636)，
源码 `9dfa3028f9e1d5a122292456efb6de79c8f1c0dd`，Android tree
`721c7d383da68d14965dfc6b3472b748172663ae`。Ubuntu 26.04、4 CPU、约 15.6 GiB 内存、
Temurin 21.0.13+9、Gradle 9.6.1。

编译阶段完成（102.692s），但第一轮原配置的 2 worker / 每个 1 GB 测试卡住，
600 秒 watchdog 报 `HR-TEST-001` 并终止对应 worker；该次 Gradle 报告 10m9s。
候选 4 worker 尚未执行，完整样本为零，因此没有提速估计。
日志中的 exit 137 不能单独证明 OOM：这里有 watchdog 的显式终止证据。

诊断轮另外记录测试开始/结束的类名与方法哈希，并为约第 3、7 分钟仍未结束的单轮任务
采集仅测试 worker 的线程栈。
不修改原 watchdog 的 600 秒上限；`jcmd` 不可用不应中断测试。
临时工作流只有本会话分支的 push 触发，read-only 权限、不保留 checkout 凭据、
不使用受保护环境、发布 secret 或外部 Build Scan。实验原始 artifact 保留七天。
临时工作流在最终分支移除，不成为常规 main 工作流。

## 四轮 GitHub 完整对比

[run 41990529007](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41990529007)
成功，源码 `d15366dc8988957822ac4297600efca652f52765`；Android tree 与首轮相同。
仍为 Ubuntu 26.04、4 CPU、约 15.6 GiB、Temurin 21.0.13+9、Gradle 9.6.1。
首轮卡顿在这次没有重现，不能视作已经修复；四轮均在采样线程栈的三分钟阈值前结束，
所以没有抓到卡顿栈，根因仍未确认。之后若再发生，保留进度和超时前栈再定点诊断。

| 顺序 | worker | 测试任务 | Gradle 命令总耗时 |
|---|---:|---:|---:|
| 原配置 | 2 | 132.340s | 141.993s |
| 候选 | 4 | 151.290s | 157.445s |
| 候选 | 4 | 155.950s | 162.345s |
| 原配置 | 2 | 132.360s | 138.482s |
| 原配置中位数 | 2 | **132.350s** | **140.237s** |
| 候选中位数 | 4 | **153.620s** | **159.895s** |

测试任务慢 **21.270s / 16.07%**，整个命令慢 **19.657s / 14.02%**。
只有每组两次且仅一个 runner，不能推断所有机器，但本轮没有采用更高 fork 数的证据。
命令总耗时从 Gradle 启动到报告检查前计时，不含一次性编译、依赖恢复、排队或 artifact 上传。
每轮编译依赖均通过 UP-TO-DATE 检查，测试真实执行，**2,933 项全部成功、0 跳过**，
用例身份哈希与本机相同。不存在用少跑用例换时间。

两轮原配置最慢类都是 `ScreenshotTest`，27.689s / 27.541s；候选为 28.959s / 28.962s。
`AccountScreensTest` 原配置为 8.430s / 9.065s，候选为 27.156s / 30.847s。
全部 class suite 耗时之和从原配置约 245–247s 增至候选约 503–524s；这与多个 JVM 的
启动/沙箱成本或资源竞争相符，但本轮没有 CPU/GC 细分证据，不能断言具体原因。
大截图类约占测试任务墙钟的 21%，也不能把拆分此类等同于节省全部 27 秒。

## 本机交叉验证

在同源码的干净独立工作树中，使用 JDK 21、真实 Android SDK，完整运行相同的 JVM 测试任务，
只将 fork 覆盖为 2，保留每个 worker 的堆、JVM 参数与 watchdog；没有私有配置文件或发布输入。
关闭任务输出/configuration cache；该机器可能已有依赖缓存，没有清空系统缓存。
这是 macOS 本机结果，不能与 GitHub runner 直接计算提速率。

完整结果：**2,933 项、0 失败、0 错误、0 跳过**，39 actionable task 全执行。
Gradle 报告 187s；测试任务 94.410s，主 Kotlin 32.649s、测试 Kotlin 23.557s、KSP 9.234s。
并行 class suite 耗时之和为 160.206s，不等于测试任务的墙钟时间。

| 本机最慢类 | 用例数 | JUnit suite 耗时 |
|---|---:|---:|
| `ScreenshotTest` | 93 | 26.758s |
| `HermesRestApiUploadTimeoutTest` | 1 | 12.707s |
| `DoubaoVoiceSessionTest` | 8 | 11.338s |
| `OutputHapticRenderingTest` | 3 | 10.441s |
| `WarmChatEntryPresentationTest` | 4 | 8.016s |

截图类确实进入普通 `testDebugUnitTest`，源码中“不在 release gate”的旧注释不能当作排除配置。
这也不能证明本轮执行了像素 golden 的 verify 模式；本轮没有设置 Roborazzi record/verify 属性。
上传回归的 12 秒延迟专门验证生产路径能等待超过旧的 10 秒默认值，不能改成短延迟来保留同等边界。
截图类的异步渲染等待、触觉集成测试的真实解析/帧推进同样需要分别证明替代方案，不能统一删 sleep。

汇总见 [ci-evidence/2026-11-05-android-tests.json](ci-evidence/2026-11-05-android-tests.json)。
只保留公开类名、计数、耗时和用例身份哈希，不保留 JUnit 的 stdout、业务正文或原始方法名。
本轮只有 L1 JVM/渲染执行；没有 L2 真机、L3 模拟器、APK package gate 或生产验收。

## 后续优先级

先用线程栈定位偶发卡顿，避免一次异常消耗十分钟；本轮 ABBA 已排除直接提高到 4 worker。
若大类限制并行，下一步可测拆分截图类，但要维持全部用例与基线图，不把拆分类的收益先记账。
Kotlin 主/测试编译是不同编译任务；后续应研究增量与缓存失效范围，不能将它们作为重复构建删去。

## 验证与交付

保留比较器、完整性/诊断回归、脱敏汇总与本报告；普通 CI、发布工作流、Android 配置和
运行代码不变。比较器沿用 `HR-CI-001`，watchdog 继续沿用 `HR-TEST-001`，不扩展错误契约。
实验前网络启用 Node 基线 1,035 通过 / 21 条件跳过；最终脚本基线 544 通过 / 1 平台跳过，
零失败。最小 Gradle 夹具验证 fork 覆盖不改变原 JVM 参数和堆；工作流通过 actionlint。
XML 仅读取本地生成的 JUnit，单文件限制 16 MiB，解析器目标直接拒绝 DTD/实体声明，
UTF-8/UTF-16 的内部实体和外部 DTD 均有拒绝回归；不添加运行时 Python 安装依赖。
本机的普通完整 Android JVM 任务成功，GitHub 四轮完整任务成功；PR/main 仍按全绿合并门禁处理。
