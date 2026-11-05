# CI 任务耗时与 Swift 缓存实验 · 2026-11-05

结论：本轮不向常规 CI 加入全目录 Swift 编译缓存；Android 下一步优先研究单测和 Kotlin 编译。
所有常规检查、签名和发布门禁保留。没有升版、生成分发 APK、修改 Swift/Kotlin 运行代码或生产发布。

## 数据与实验边界

[实验 run 41973488828](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41973488828)
的七个 job 全部成功。原始工作流在提交 `f5b291150e2c8e195f6d50b0c332933b6e29b075`，
只由本会话分支 push 触发，权限仅 `contents: read`，checkout 不保留凭据；没有受保护环境、
发布 secret、APK 构建或外部 Build Scan。临时工作流已在最终分支移除，不进入 main 的当前工作流。
Desktop 源码树保持 `4c40181d2c3b26117f618795e157645904b39d25`，与 #732 一致。

汇总数据保存在 [ci-evidence/2026-11-05-profiling.json](ci-evidence/2026-11-05-profiling.json)。
GitHub 原始日志、六份 Swift JSON/日志和 Android HTML profile artifact 保留七天。
汇总不含凭据、原始异常、用户目录或应用业务数据；构建目录仅以哈希记录。

## Swift：全新 runner 上的真实恢复实验

两个 seed job 分别执行完整 Swift 测试和 release-mode app 构建，保存编译状态。
随后四个新 `macos-15` runner 各自重新 checkout 同一个提交，按 unit/app 与 cold/cached 矩阵运行。
cached 必须真实命中本次 run 的精确 seed key，无前缀回退。比较时源码、Swift/Xcode、系统版本、
架构、构建目录哈希和测试/应用身份必须相同；不完整、失败或不可比样本不报告收益。

所有 runner 为 macOS 15.8.2 arm64、Xcode 16.4/16F6、Swift 6.1.2。
计时从 checkout 完成后、缓存恢复前开始，直到完整命令与验证结束，包含恢复和工具链识别成本；
不包含 runner 排队、checkout、结果 artifact 上传，以及 seed 的缓存保存时间。

| 完整门禁 | 无缓存总耗时 | 命中缓存总耗时 | 本轮差值（无缓存减缓存） |
|---|---:|---:|---:|
| `npm run desktop:test` | 82.072s | 86.684s | **-4.612s** |
| `npm run desktop:app` | 64.653s | 79.234s | **-14.581s** |

unit 的命令自身耗时为 78.959s / 76.002s，但计入恢复等成本后没有净收益。
两组均执行全部 740 项测试、0 失败。app 命令自身为 62.231s / 74.493s，
两组均重新打包并通过严格 ad-hoc 签名检查，bundle ID、0.2.52/build 55 一致。
没有使用 `--skip-build`，没有缓存整个 `.app` 或签名材料，也没有声称 Developer ID/公证验收。

缓存范围为 `desktop/.build`、`desktop/build/swift`、`desktop/build/module-cache`、
`desktop/build/swiftpm-module-cache`，unit/app 种子的未压缩目录约 334 MB / 141 MB。
缓存后仍出现大量编译：unit 的 `Compiling` 日志行是 155 对无缓存的 156；app 两组均为两个
批处理编译步骤。单次日志不能确证重编译的全部原因，因此不把它直接归因为某个 SwiftPM 失效规则。

每种模式只有一对 cold/cached、另有 seed，且不同 runner 会有波动。这不能证明所有缓存方案都更慢，
但不足以支持增加这套持久配置。因此常规 CI 保持 #732 的并行全量验证，不加入该缓存。
后续若再试，应先缩小缓存范围、确认实际跳过哪些编译、增加重复样本，再评估保存/恢复成本。

可用保留的比较器复核原始 JSON，负收益会如实保留：

```bash
python3 -B scripts/ci/benchmark-swift-cache.py compare \
  <swift-unit-cold/result.json> <swift-unit-cached/result.json>
```

缓存的 key/version/branch 作用域与 restore/save 行为见
[官方 actions/cache 说明](https://github.com/actions/cache#cache-scopes)。本实验仅验证本次精确 key，
没有证明跨源码、工具链、构建路径或前缀回退缓存可以安全有效地复用。

## Android：历史发布阶段与精确任务报告

历史正式发布中，package step 与日志内主 Gradle 构建时间如下：

| 版本 | package step | 主 Gradle 构建 | 差值 |
|---|---:|---:|---:|
| 0.1.194 | 394s | 372s | 22s |
| 0.1.193 | 323s | 301s | 22s |
| 0.1.191 | 336s | 311s | 25s |

差值包含前置配置检查、启动和 APK 检查等，不能全算成配置或签名时间。
普通 Gradle 日志的 task 标签可能重复或与并行任务交错，因此本报告不把标签间隔冒充 task duration。

本次另外执行原有完整 `:app:testDebugUnitTest`，使用 Gradle 9.6.1 `--profile`，
关闭任务输出缓存和 configuration cache，允许恢复既有依赖缓存，使用 JDK 21 与 Ubuntu runner。
39 个 actionable task 全部执行，构建成功；总计 **365.91s**，Task Execution 汇总 **356.83s**。

| 实际任务 | profile 报告耗时 |
|---|---:|
| `:app:testDebugUnitTest` | **158.200s** |
| `:app:compileDebugKotlin` | **95.770s** |
| `:app:compileDebugUnitTestKotlin` | **44.697s** |
| `:app:kspDebugKotlin` | **26.162s** |

四项累计约 325s，占报告 Task Execution 汇总的约 91%；这是任务耗时汇总，不能直接承诺等量墙钟提速。
本次配置项目 3.206s、依赖解析 0.731s；后者在依赖缓存已恢复的条件下测得，不包括首次下载成本。
优先级据此为：先分析单测内部的类/渲染分布，再分析 Kotlin 主/测试编译失效范围，KSP 排在之后。
不把增加堆、升级 Action 或缓存命中本身当作已经提速，也不删除前置配置和最终签名校验来换时间。

这次 profile **不是正式 package gate**：没有反馈凭据、签名 key、assemble/dex/APK 校验和发布。
它定位当前无凭据测试图的瓶颈，不能替代真实发布构建的任务分布或安全验证。
普通测试成功也不代表真机、通知/后台行为或生产验收完成。
[Gradle 官方 CLI 文档](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:command_line_performance)
说明了 `--profile` 的本地报告用途；本实验没有使用 `--scan` 上传构建数据。

## 本次交付

保留可重复使用的 Swift 计时/比较器与完整性回归，以及上述数据和结论。
比较器沿用 `HR-CI-001` 的既有“构建对比未完成/证据不可比”错误条件及双语提示，
不扩展运行时错误契约。正常 CI、发布工作流和客户端行为均不改动。

本地验证：最终 14 项聚焦检查通过，完整脚本基线为 540 通过 / 1 平台跳过 / 0 失败；
实验启动前的完整网络启用 Node 基线为 1033 通过 / 21 条件跳过 / 0 失败。
临时工作流通过 actionlint 1.7.7；原始分支隔离测试随工作流移除，比较器完整性测试保留。
最终 CLI 已用下载的两对真实 JSON 重新复算出上述负收益。GitHub 的七个实验 job 均成功，
普通 PR 和 main 检查仍须按既有合并门禁通过。不进行真机、Developer ID/公证或正式发布验收。
