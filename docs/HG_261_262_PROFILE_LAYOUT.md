# HG-261 / HG-262 — 角色副信息布局

Android 与 Web 将已有普通会话的角色名统一放在标题下一行；列表角色名与后续实际内容采用现有圆点分隔。没有角色或后续内容时不添加首尾圆点。只是布局与回归覆盖，不改变角色归属、别名规则、隐藏／筛选、请求路由、项目权限或服务端。

## 授权与主线

固定方案 v1：<https://missiongo.mrlgs.net/?planDocument=cc729e4d-cce2-44dc-8ea9-6f453eea2805>，SHA-256 `968d9f79e5b273b5c8a4c7b42dba6048e9df3d8ef75f5fe76e0ba919bbda1557`。
用户查看真实样式预览后在原会话明确回复“批准，开工”。原派单 `c51931e2-f73a-4b65-8e8c-06a05ea08a72`，两条领取身份与处理轮次保持。授权不含推送、合并、升版、发布或验收。

主线在调查后的 `5ee1d772` 基础上合入 HG-260，实施前已读取差异并快进到 `c4cf96fb4711a3e41fd788802d486ceb1475a9fd`。保留其完整新建输入区、中央切换角色、项目选择与多选角色筛选。

MissionGo 同步缺口：`recover_plan_approval` 返回 `history_incomplete`，`get_agent_history` 返回 `unsupported_or_not_synchronized`。固定附件归档被服务端拒绝，未声称归档成功。两条已分别保存带“用户决策 · 2026-11-22”标记的真实人工决定、固定文档引用和此限制。原生明确批准有效，没有再次索批或借用其他身份。

## 实际改动

| 对象 | 变化 | 边界 |
|---|---|---|
| Android ChatTopBar / ChatScreen / WorkspaceSubtitle | 角色名作为副行数据，移除标题前缀 | 空新会话中央选角、机器人来源与项目可用规则保持 |
| Web ChatPage / styles | 标题独占首行，角色在副行与项目或连接提示同排 | 原连接提示优先级及项目移动禁用保持 |
| 两端共享会话行 | 角色与草稿／项目／分支／模型等后续内容之间加一个圆点 | 置顶在最左，草稿强调色、原项目模型分隔不变 |
| 测试及设计文档 | 位置、缺项、浅暗、大字号、窄屏与重连回归 | 不新增错误路径或 HR 码，不修改全局 token |

共享行还用于搜索、归档和项目列表，这些位置同步生效。Web 归档标签也计作实际后续信息。机器人没有普通角色段，不制造首点。

## 回归证据与执行记录

- Web 新增位置及圆点测试在原实现上出现 6 个预期失败；修改后定向套件通过。
- Android 新增 SessionSubline 边界测试首次有 2 个断言失败；修改后通过。首次构建期间未固定生产文件快照，不将该次结果夸大为完整旧版二进制验证；Web 的旧实现复现独立成立。
- Android 首轮完整基线 3046 项中 4 项失败，均为 DefaultProjectSublineScreenshotTest 中旧的圆点计数。保留目录隐藏、真实项目名、模型、置顶和草稿断言，更新为五个角色分隔加两个原项目分隔。
- 新增 ChatRoleLayoutTest 对生产顶栏断言角色位于标题下方且不挤占新建按钮，同时覆盖项目和无项目、浅色和系统浅色／应用深色的 1.3 字号。
- 新增 ProfileLayoutDeviceTest 通过公开无障碍 API 测量生产组件并保存系统截图，不依赖 API 37 不兼容的 Espresso 注入器，不访问账号、真实聊天或线上服务。
- Web 浏览器脚本直接加载生产 ChatPage / SessionRow / styles，只有传输采用隔离假实现；不以另画 HTML 的效果图冒充验证。浏览器截图及测量在本机临时产物中。

### 最终验证

- Web：typecheck、全量 Vitest（109 文件 / 1109 项）与生产 build 通过。
- Chromium / WebKit：中英文长名称、320/390/900px、16/21px 根字号、浅暗、有／无项目共 96 个布局通过；每个场景还检查重连后的角色位置。列表圆点缺项断言通过，页面异常为空。
- Android：补修后最终完整 `:app:testDebugUnitTest :app:assembleDebug` 通过（3046 项，0 失败，0 跳过），canonical debug signing 校验通过。首轮失败及修正见上文，未降低原有业务断言。
- L1：JVM 位置与圆点定向回归通过；四个生产组件 Roborazzi 截图单独录制通过并已目检，不重录其他 golden。
- L2：无物理设备，未运行。
- L3：Pixel_API_37（Android 17 / SDK 37，emulator-5554）上的 `ProfileLayoutDeviceTest` 通过；浅暗、有／无项目四种组合，深色 1.3 字号，已取回四张系统截图。只覆盖生产组件，不冒充登录后的完整业务验收。
- `git diff --check` 通过。独立审查第 1 轮已发现并补修下述问题，本轮唯一一次同 Agent 定向复核已完成，两项 P2 均闭环，无其他相关有效问题。Android 补修后完整基线与同一 Pixel API 37 组件测试再次通过，见 `/tmp/hg261262-android-reviewed.log`。

### 第 1 轮独立审查与补修

原实施会话通过原生 collaboration.spawn_agent 启动独立只读审查，reviewer 标识 `/root/profile_layout_review`；未切换模型、权限或供应商，审查者未再派生。实际完整审查 1 轮，同一 Agent 定向复核 1 次；最终两项 P2 均闭环，未发现其他相关有效问题。审查者核验最终记录与内容，没有冒称重新运行全量测试。

1. Android 长角色名＋项目：初版只断言项目节点存在，实际截图显示名称被挤掉。现在按有副标题时角色最多占该行 30%（仍有 112dp 上限）先测量，短角色不会占据未使用的预算；工作区获得剩余空间。只在角色同排时让项目、分支共享文字预算，不改变无角色的新建工作区。新增 TextLayoutResult 断言：项目和分支都必须显示至少一个真实字符，不能只有省略号。四张浅暗／字号截图重录并目检通过。
2. Web 窄屏大字号英文重连：审查提出风险后，原会话补真实 Range 文本测量，在 Chromium 320px / 21px 根字号复现 `reconnect text escapes its slot`。连接提示改为在副行剩余空间内单行省略，完整原文保留在 DOM、读屏与 title；不会覆盖新建／更多按钮。该补修没有增加或改变错误文案。加入状态容器与文字绘制边界断言后，96 个中英／内核／主题／宽度／字号／项目组合全部通过。Web 全量 109 文件／1109 项与 build 再次通过。

两项都是已批准的长名称、窄屏和重连布局验证范围内补修，没有新增交互或产品选择；没有重复索批。审查不是人工验收。

### 可复现命令

```sh
npm ci --ignore-scripts --prefix web
npm run typecheck --prefix web
npm test --prefix web
npm run build --prefix web
node web/scripts/check-profile-layout.mjs <absolute-playwright-module> <output-directory> [webkit-executable]
cd android
ANDROID_HOME=<sdk> ./gradlew :app:testDebugUnitTest :app:assembleDebug
ANDROID_SERIAL=<serial> ANDROID_HOME=<sdk> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hermes.client.ui.chat.ProfileLayoutDeviceTest
ANDROID_HOME=<sdk> ./gradlew :app:recordRoborazziDebug --tests '*ChatRoleLayoutTest'
```

## 研发完成时的交付边界

无需新增 Gateway、Connector 或 Desktop 发布。此改动最终仍需要 Android APK 和 Web 静态包分别发布，当前没有提交修复 commit，也没有执行发布、升版、推送、PR 或合并，两条保持处理中。未把“用户未要求发布”写成“无需发布”。

无物理手机连接，L2 未运行；真实 Android 厂商字体、iPhone Safari/PWA 及触摸验收仍待人工按 ANDROID_SMOKE.md / SMOKE_TEST.md 检查。L1 和模拟器不替代真机观感。


## 研发阶段交付快照与产物（合并授权前）

研发、验证和独立审查均完成于本地独立分支 `codex/hg-261-262-profile-layout`。HEAD 仍是主线基线 `c4cf96fb`，不是修复提交；本次源代码、测试和文档改动尚未提交或推送。没有 PR、合并、升版、部署、APK 分发或人工验收。两条保持处理中。

- 四张 L1 参考图：`android/app/screenshots/hg261262-{light-1.0,dark-1.3}-{plain,project}.png`。
- 最终 L3 系统截图：`/tmp/hg261262-final/android-{light,dark}-{plain,project}.png`，均为独立假数据组件 Activity，不是登录后的完整业务页面。
- 最终 Web 实组件截图：`/tmp/hg261262-final/web-{light,dark}.png`，窄屏英文重连补修图 `web-narrow-reconnect.png`。
- 原截图及完整几何测量：`/tmp/hg261262-browser-reviewed/observations.json`；对应日志 `/tmp/hg261262-browser-reviewed.log`。
- 补修后 Android 全量日志：`/tmp/hg261262-android-reviewed.log`；四图录制日志 `/tmp/hg261262-roborazzi-final.log`；Web 全量及构建 `/tmp/hg261262-web-review.log`。

最终检查 `git diff --check` 通过；工作树仅含本任务修改与明确录制的四张参考图。主目录其他会话的文件未修改。MissionGo 正式批准恢复与固定附件归档仍有前述历史同步缺口，真实人工决定已分别保存为评论，不宣称附件归档已成功。


## 合并与发布授权（2026-11-22）

用户随后在原会话明确要求“合并和发布”。沿用原派单和本批两条范围，授权提交本任务正常分支、创建一个 PR、通过现有门禁合并，并按正式发布流程发布 Android 与 Web；具体实时 head、CI、版本、完整候选和公开产物核验保存在原会话后续执行记录，不把此前研发快照冒充发布事实。
