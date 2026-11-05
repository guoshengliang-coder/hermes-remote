# Android CI 卡顿诊断 · 2026-11-05

目标：下次完整单测偶发卡住时，保留未结束用例与 worker 栈证据，支持定点修复。
前一轮 [完整测试研究](ANDROID_TEST_RESEARCH_2026_11_05.md) 的原配置曾触发 600 秒 watchdog，
随后四轮完整测试未重现。本次不把重复成功当作已经修复，也不改变用例、fork 数、堆或硬超时。

## 正常 CI

仅 `ci.yml` 的 `android` job 设置 `HERMES_TEST_DIAGNOSTICS_DIR`，目录在 runner temp 内。
命令仍是完整 `:app:testDebugUnitTest --no-daemon`，增加 `--console=plain` 以便阅读进度。
使用 Gradle 内置 TestLogging，不安装会破坏 configuration cache 的测试监听器。
测试失败时保留完整异常栈，便于区分测试调度、渲染或清理阶段的错误。
未匹配的 STARTED/PASSED 记录是诊断线索，最终成败仍以原 Gradle/JUnit 结果为准。

每个 worker 原有的独立 watchdog 保留 PID + startInstant 检查。它在预算 50%/80% 时异步请求
线程快照，默认第 300/480 秒；第 600 秒仍按原 `HR-TEST-001` 条件强制终止对应 worker。
每个采样进程最多等待五秒，不阻塞计时线程；watcher 退出时终止自己的采样子进程。
程序来自该 JDK 的 `bin/jcmd`，参数为验证后的数字 PID 和固定 `Thread.print -l`，不使用 shell。
参见 [Oracle JDK 21 诊断工具](https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html)
和 [Gradle TestLogging](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.logging.TestLogging.html)。

采样是尽力而为：JDK attach 不可用、JVM 无响应、输出目录不可写等情况下仍保留原硬终止行为，
不能保证所有卡顿都能取得栈。采样失败只产生静态 unavailable 证据，不输出原始异常或路径。

## 证据边界

失败 job 上传 `android-test-diagnostics-<run>-<attempt>`，保留七天，路径仅指定目录内 `*.txt`。
不上传整个 build 目录、BuildConfig、JUnit stdout、环境、配置、APK、签名材料或 heap dump。
成功 job 不上传该 artifact；已有 GitHub job 日志仍记录公开测试名与结果。

worker metadata 仅包含 PID、启动时间、预算与采样比例。导出的栈只允许数字线程 ID、线程状态、
Java 栈帧；不保留线程名、锁对象详情、任意 JVM 输出或原始错误。原始 jcmd 文件在系统临时目录，
过滤后删除，不进入 artifact 目录。原始文件上限 2 MiB，导出约 256 KiB、256 线程、每线程 96 帧；
超过限制的细节可能缺失。栈帧仅用于诊断，不把不足的证据包装成确定根因。

本机与正式发布默认不启用此功能，lint/release 工作流不增加该环境变量。需要在无凭据的隔离
工作树手动排查时可设置变量为新的临时目录；普通成功构建不会运行 jcmd。

## 测试影响与复核

新增真实卡顿回归首先在旧实现下因缺失快照失败，再验证新增实现。
六项 JDK 17 字节码真实 JVM 回归覆盖：采样与脱敏、输出界限、阻塞采样不延后硬终止、
不可写目录、正常退出、PID 启动时间不匹配，以及保留另一个 JVM。沿用既有 `HR-TEST-001`
双语、不可直接重试的错误契约，没有新增错误码或跨端协议。

这些验证仍由 `:verifyTestWatchdog` 在 JVM 测试前执行；本机六项约 14 秒，旧三项约 6 秒。
这是新增故障边界回归的固定成本，不报告为提速。普通健康 worker 不触发取栈。
工作流回归验证失败条件、目录范围、七天保留、配置缓存、完整测试和发布输入隔离。

另外在只读权限、无凭据的临时分支工作流中连续执行三轮原配置完整测试，强制测试任务重跑，
保留正常 configuration cache，核对用例身份/数量、实际 worker 数、进度事件、编译 UP-TO-DATE
及第二、三轮的配置缓存复用。临时工作流在最终分支移除，不进入常规 main 调度。
若任一轮失败，优先读取最后未完成用例和取栈，不将失败轮计作性能样本。

## 复核中找到的测试调度回归

[run 41998575416](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41998575416)
第二轮执行全部 2,933 项，其中 `ImageEditorGestureTest.eachToolShowsItsOwnOptionRow` 失败，
报告 `CalledFromWrongThreadException` 和 Compose `SlotTable` 越界。这是一次真实测试失败，
不是 configuration cache 校验误报；失败轮不计为通过样本，也不绕过重试。

该类遗漏了项目已有的 `createHermesComposeRule()` 迁移。编辑器在真实 IO 线程解码/像素化，
旧 v1 Compose 测试规则的 unconfined 默认可能让 effect continuation 在 IO 完成线程上恢复。
新增回归直接建立 UI scope、等待真实线程完成 Deferred，先验证 continuation 必须等待 UI
调度器，再验证恢复线程必须等于 UI 线程。旧规则确定失败，实际恢复在 `pool-4-thread-1`；
改用既有 StandardTestDispatcher 规则，不删除原 11 项手势/撤销/完成/取消断言，不改生产 UI。
这是已捕获的测试调度问题；没有证据证明它就是前一轮 600 秒卡顿的原因。
