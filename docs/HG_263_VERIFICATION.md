# HG-263 本地实现与验证记录

2026-11-22：新会话模型读取与键盘窗口修复已完成本地实现。2026-11-23 用户明确要求「提交到待集成」，授权原会话提交、推送本任务分支并创建单一 PR；合并、升版和发布仍须独立授权，条目保持处理中。

## 范围与依据

- 原派单：`bff29c6b-0329-4798-b8fe-0c561e58f209`；固定获批文档：`307f7ffc-bde4-4372-92a7-6d40b11f0a5f`，v1。
- 方案已由 MissionGo 正式记录批准，当前版本和范围有效；摘要评论及固定 Markdown 附件已归档至 HG-263。
- 独立分支：`codex/hg-263-model-composer`；worktree：`/Users/bs/Projects/missiongo-bff29c6b-0329-4798-b8fe-0c561e58f209`。
- 基线与交付前远端 main 均为 `c40036f035564ecdad1944d4604ceefb279eb4a3`。公共 internal 索引的 0.1.203/code 204 指向该提交；不能据此认定反馈手机已安装该版本。
- 已完整读取条目、初始时间线及两张截图。无现场请求日志，也未取得反馈手机的系统、ROM 和输入法版本。

## 实际行为变化

| 对象 | 变化 | 边界 |
|---|---|---|
| 新会话模型 | 独立请求默认模型，不再等待项目或项目偏好 | 不新增服务端接口、不猜模型、不修改 Mac 默认 |
| 读取状态 | 已知显示具体模型；读取中和不可用有独立文案 | 欢迎 pill 和展开输入区采用同一规则；已有会话与机器人语义不变 |
| 失败恢复 | 10 秒边界、模型面板重试、重连及重新进入面板重读 | 使用 HR-MODEL-004 双语错误，不显示配置或 HTTP 原文 |
| 异步隔离 | 取消、读取版本和草稿/角色/账号/Mac 身份检查 | 迟到结果不覆盖新作用域；手选模型、服务商和推理档位优先 |
| 键盘窗口 | 新会话 Dialog 使用 edge-to-edge，聊天底栏单独负责 IME/系统栏 | 不全局修改其他窗口，不简化完整输入组件 |

初始模型问题不是界面始终写死“默认模型”：旧实现配置读取成功后本就会显示具体模型。已用回归证明项目或偏好挂起会阻塞旧模型读取，且失败缺少恢复；仍不能断言反馈现场究竟阻塞在哪一次请求。

## 自动化验证

| 检查 | 实际结果 | 证据与覆盖 |
|---|---|---|
| 旧模型行为回归 | 2 项均失败，符合预期 | 项目请求挂起、项目偏好尚未发值时，旧实现模型仍为空 |
| 聚焦逻辑回归 | 通过 | ChatViewModel、ModelChipLabel、AppError 全类测试 |
| 仓库标准 Android 基线 | 3053 项测试，0 失败、0 错误、0 跳过；debug 构建通过 | `./gradlew :app:testDebugUnitTest :app:assembleDebug` |
| 本次截图严格比对 | 4 项通过 | 新会话浅色/深色大字号；读取错误的中文浅色/英文深色大字号 |
| 仪器测试构建 | 通过 | `./gradlew :app:assembleDebugAndroidTest` |
| 修复版真实 IME 测试 | 通过，恢复修复版后再次通过 | Pixel_API_37 / emulator-5554 / sdk_gphone64_arm64 / SDK 37；浅深主题各重复开合两次，检查贴边和草稿保留 |
| 旧窗口回归探针 | 失败，符合预期；已目检悬浮空白 | 原主线临时快照沿用旧 Dialog 属性；先触发系统栏间距失败，再继续到 IME 贴边断言失败 |
| 独立研发审查 | 第 1 轮完成，无可确认的生产代码缺陷 | 原生独立 Agent `/root/hg263_review`；只读、未派生、未修改文件；无生产代码补修，同轮唯一一次定向复核最终测试与证据记录完成 |
| 最终差异检查 | 通过 | `git diff --check`；未混入主工作区现有文件、版本改动或凭据 |

L1 覆盖超时、空/异常配置、失败后重试、重连、面板不重复并发读取、角色 A→B→A 迟到响应、离开草稿、账号与 Mac 切换、手选与推理设置保留，以及原有普通/机器人的模型标签规则。旧行为失败与修复后通过均实际执行，没有通过删除断言来消除失败。

L3 使用生产 `NewChatWindow` 和真实 Android 输入法，加轻量输入区/脚注夹具；L1 的完整 ChatScreen 测试另覆盖完整输入组件和角色切换。不能将两个层次合称为真实账号完整端到端验收。旧窗口探针仅存在于 `/tmp` 的诊断快照，不进入任务分支。

仪器测试最初在重复键盘开合中读到了动画期间的旧无障碍坐标；修正为等待实际底栏回到系统边缘及无障碍事件空闲后再点击下一次，不放宽最终贴边断言。最终夹具允许的底部间距为 0–12dp，用于保留现有脚注下方小留白，不能容忍整段键盘高度空白。

## 额外全库截图检查的既有问题

额外执行全库 `-Proborazzi.test.verify=true` 得到 36 项失败，不把它记为全库截图通过：

- 22 项将截图写到临时目录或 build 输出，没有可比对的原图基准。
- 14 项为未修改页面的既有基准漂移。用原主线 `c40036f0` 的独立源码快照复核，同一 14 项全部失败；原主线与本次实际渲染图的 SHA-256 全部一致（14 相同，0 不同）。
- 没有更新这些无关页面的 golden、删除断言或修改产品行为。本次四项截图另行严格比对并通过。

原有截图基准维护超出 HG-263 范围，作为已核实的既有验证限制保留。

## 文件范围

生产代码：

- `android/app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- `android/app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`
- `android/app/src/main/java/com/hermes/client/ui/chat/ModelChipLabel.kt`
- `android/app/src/main/java/com/hermes/client/ui/chat/DraftModelReadStatus.kt`
- `android/app/src/main/java/com/hermes/client/ui/chat/NewProfileChat.kt`
- `android/app/src/main/java/com/hermes/client/data/error/AppError.kt`
- `android/app/src/main/java/com/hermes/client/ui/localization/AppErrorLocalization.kt`

测试：`ChatViewModelTest.kt`、`ModelChipLabelTest.kt`、`NewChatFullScreenTest.kt`、`DraftModelReadStatusTest.kt`、`AppErrorTest.kt`，以及 `src/androidTest/.../NewChatKeyboardDeviceTest.kt`。截图位于 `android/app/screenshots/`：两张 `hg260-new-chat-*` 更新，两张 `hg263-model-read-error-*` 新增。

文档：`android/README.md`、`docs/DESIGN.md`、`docs/ERROR_HANDLING.md`、`docs/ANDROID_SMOKE.md` 及本记录。

## 执行日志

以下为本机临时执行记录，未上传为 MissionGo 附件；文件保留依赖当前节点在线及临时目录未被清理。不能把正文中的路径当作已保存到远端的文件。

- `/tmp/hg263-red-unit.log`：旧模型阻塞的两个失败回归。
- `/tmp/hg263-unit-focused.log`：模型逻辑聚焦测试。
- `/tmp/hg263-android-required-baseline.log`：3053 项标准基线和构建。
- `/tmp/hg263-baseline-summary.json`：标准基线 XML 汇总。
- `/tmp/hg263-changed-screenshot-verify.log`：本次四项严格截图检查。
- `/tmp/hg263-android-baseline.log`：额外全库截图检查的 36 项失败。
- `/tmp/hg263-original-main-screenshot-check.log`、`/tmp/hg263-original-main-screenshot-summary.json`：原主线的 14 项复核。
- `/tmp/hg263-legacy-keyboard-red.log`、`/tmp/hg263-legacy-ime-red.log`：旧窗口失败。
- `/tmp/hg263-keyboard-device-v3.log`、`/tmp/hg263-final-keyboard-green.log`：修复版真实 IME 测试通过。
- `/tmp/hg263-evidence/HermesTests/`：已取回并目检的合成夹具截图；不含真实账号会话。

## 待实机验收与交付边界

L2 未执行：本机无物理设备。反馈手机的厂商 ROM、中文输入法动画、真实账号的项目/配置网络时序，以及完整角色/项目/附件/语音/首次发送流程仍按 `docs/ANDROID_SMOKE.md` 的 HG-263 清单验收。

实施批准只覆盖本地代码与验证；后续「提交到待集成」指令另行授权本任务提交、正常推送和创建 PR，登记须绑定实际提交与实时检查。不自动合并、升版或发布。没有可分发的新版 APK，没有运行发布闸门或部署。未核实集成和正式发布前，不将条目推进开发完成或待验证，人工验收不由 Agent 决定。

## MissionGo 回写结果

获批方案的摘要评论与固定 Markdown 附件归档成功。完成结果评论使用读取到的原会话 `aa70bd2a-2a04-4d0e-b2f8-803b7a8f60cf` 关联时，`append_comment` 明确返回 `Agent session was not found.`，没有成功保存完成评论。随后只读核对 `list_item_agent_sessions` 和方案批准记录，原会话仍为当前派单的 active/synced 记录，批准与范围仍有效；没有另注册会话、换身份、重领、重试写入或推进业务状态。此为服务端回写关联缺口，不改写为批准失效或代码验证失败。

截至 2026-11-22 实施回合结束，结果只写入本地本记录，尚未保存为 MissionGo 完成评论；不能将该次失败改写为已回写或已通知。2026-11-23 按原 dispatchId 只读恢复核验成功，领取与范围仍有效，未重新领取或另建会话。待集成登记与普通评论回写分别核实，后续以各自实际回执为准。条目仍为处理中，结果文件未作为新增附件上传。
