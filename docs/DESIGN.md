# Hermes GO 设计规范（DESIGN.md）

> For agents: this is the UI/UX design contract for the Android client, written in Chinese
> (the product owner's working language — a deliberate decision, 2026-09-01). Read it before
> any UI change; where it conflicts with an implementation, the implementation is the
> regression. Process rules live in `AGENTS.md`; error copy rules live in `ERROR_HANDLING.md`.

**定位与优先级**：本文约束 Android 客户端的视觉与交互。冲突裁决顺序：用户的明确指示 >
本文 > Material 3 默认。流程与发布规范见 `AGENTS.md`，错误文案见 `ERROR_HANDLING.md`，
本文不重复二者。

**权威来源声明**：本文引用的具体色值 / 字号 / 尺寸以**代码为准**（每处标注定义文件），
文中数值是便于阅读的快照。改代码后同步本文；发现漂移按代码修文档。
（待立项：把 `CardPage.kt` 内联的淡卡色 / 发丝线提取为主题级 token，消除双份真相。）

**设计稿画布**：<https://claude.ai/code/artifact/bbb06b3b-ffa9-4f8e-ade1-d230741b8f6c>
（「Hermes Remote 新版 IA」，8 画板）。

**决策标注惯例**：关键决策附日期（如 *决策 2026-08-31*），推翻旧决策时保留日期链便于追溯。

---

## 1. 三条根本原则（决策 2026-08-31）

1. **Profile 是唯一作用域边界。** 切换身份即切换整个工作区：会话、项目、归档、搜索、
   定时任务、用量、模型、技能、消息渠道全部只呈现当前身份。唯一例外：身份选择页/切换列表
   可显示其他身份的进行中/待处理计数（切换器天然需要全局视野）。
2. **一处切换，处处生效。** 全 App 只有身份选择页一个显式切换入口；唯一保留的隐式切换是
   通知深链（进会话前先切换）。`switchTo()` 是网关写操作、会失败：**失败时 UI 必须停留在
   原身份**并给出重试提示，禁止乐观更新。
3. **身份只由头像承载。** chrome（顶栏、FAB、分段、分组头、发送键）一律品牌色；
   身份的哈希色/自定义色只出现在头像圆上。列表页、聊天页不出现身份文字。

## 2. 色彩

### 2.1 基础（`ui/theme/Color.kt`，icon-blue 决策 0.1.61）
- 品牌主色 **蓝**：light primary `#0B5FD0`（Blue40）、dark primary `#A9C7FF`（Blue80）；
  背景冷调（light `#F7F9FD`）。历史：Mint 绿已废弃 —— 它与状态语义冲突（primary 同时
  兼任"完成"色只因绿恰好像成功），也与彩色启动图标打架。
- **状态色独立于品牌色**（`ui/theme/StatusColors.kt`，深浅双档、测试钉死）：绿=成功/完成
  只在状态语义中出现，不再与 primary 混用。chrome 永远蓝，状态永远走 StatusColors。
- 中性色、错误色等全部走 `MaterialTheme.colorScheme`；**禁止**新增只在单一主题下定义的
  硬编码色（每个颜色必须同时考虑深浅两个模式）。

### 2.2 深浅模式判定（强制，决策 2026-09-01）
- 深浅由**生效主题**决定：`MaterialTheme.colorScheme.surface.luminance() < 0.5f`。
- **禁止**用 `isSystemInDarkTheme()` 做样式分支。教训：应用内主题（随系统/浅色/深色）与
  系统设置可以不一致，曾导致"应用选深色、卡片仍是浅色白卡"的花屏（0.1.56 修复）。

### 2.3 淡卡语言（卡片页容器，`ui/nav/CardPage.kt`）
- 浅色：填充 `#FAFBFD`（冷调近白，与白底仅差 1–2 灰阶）+ `shadowElevation 1dp`；
  发丝线 `#EBEDF2`。注意：早期暖白 `#FAFAF8/#ECECEA` 在蓝调环境下泛黄，已随 icon-blue
  切换修正 —— 淡卡字面量必须与品牌冷暖一致。
- 深色：`lerp(surface, White, 0.06f)` 微提亮一阶、无阴影；发丝线 `lerp(surface, White, 0.14f)`。
- 精神：卡片轮廓靠**微差与微影**，不靠重填充；深浅两模式必须同一语言。

### 2.4 身份头像色（`ui/theme/ProfileAccent.kt`）
- 自动色：FNV-1a + fmix32 哈希名字取色相，固定 `s=0.62, l=0.32`。
  l=0.32 是硬约束：全部 360 色相下白字对比 ≥3.9:1（`ProfileAccentTest` 钉死），因此
  **永远白字**、无需自适应黑白机制（该机制已于 0.1.55 删除，勿复活）。
- 自定义色：仅限 `AVATAR_SWATCHES`（10 色相 + 灰 + 近黑，同一明度），存
  `AvatarColorStore`（设备本地）；「自动」= 清除。作用域**仅头像**（列表、聊天、通知
  同步生效），不染 chrome。
- 通知强调色用 `avatarColorArgb(profile)` —— 它是身份信号，不是 chrome。

## 3. 字体与文本适配

### 3.1 全局
- Type ramp 见 `ui/theme/Type.kt`；阅读界面（聊天、列表、设置）**跟随系统字体缩放**。

### 3.2 卡片页设计尺度锁定（决策 2026-09-01，仅限卡片页）
- 整页 `fontScale = min(system, 1.0f)`（LocalDensity 覆盖）。此锁**不得扩散**到阅读界面。
- 字号对照（sp，锁定尺度下，权威见 `CardPage.kt`）：

  | 元素 | 值 |
  |---|---|
  | 字标 Hermes GO | 26 Bold |
  | 身份名 | 20 |
  | 统计标签 / 副行 | 15 |
  | 统计数值 | 23 Bold（缩字下限 13） |
  | 快捷行文字 | 17（B 档密度，决策 2026-09-01） |
  | 快捷行右值 | 15（缩字下限 12） |
  | 头像字母 | 0.47 × 圆径，SemiBold（≈字符高占圆径 35%，参考稿实测） |

### 3.3 文本适配三级策略（通用，`CardPage.kt` FitText）
适用于所有"内容必须完整"的受限宽度文本（统计数值、设备名、行右值等）：
1. 单行内逐级缩字至下限；
2. 下限仍溢出 → **保持下限折两行**；
3. 两行仍溢出才允许省略号。数字与设备名不得在 ①② 之前被截断。

工程要点：
- 溢出检测必须用 `isLineEllipsized` —— `overflow=Ellipsis` 时 `didOverflowWidth` 恒为
  false，缩字永不触发（0.1.53 实坑）。
- 同一视觉行的左右两格字号**锁步**：任一格触发缩字，两格一起缩（父级受控状态）。
- 延迟显示：<1000ms → `N ms`，否则 `X.X s`（四位数毫秒不配占宽）。

### 3.4 对照设计稿的方法论
参考图的像素与 dp **不可直接换算**（多为放大裁剪图）。规范做法：锚定一个双方共识字号
（如标签 15sp），其余按参考图**内部比例**推导；拿不准以真机观感裁决，并回写本文。

## 4. 图标

### 4.1 风格（唯一）
- **1.7dp 描边**、圆头圆角（StrokeCap/Join = Round）、24×24 viewport 手绘 `ImageVector`。
- **禁止** Material 填充式/outlined 图标混入此体系（它们按 2dp 裁切，观感重一档；
  例外：底部菜单、对话框等弹出层沿用 Material Rounded 与既有交互一致）。
- 语义底线：齿轮必须**带齿圈**（hub+刻线的"太阳式"曾被误读为亮度调节，0.1.58 废弃）。

### 4.2 清单与尺寸
- 卡片页私有（`CardPage.kt`）：齿轮、时钟、月亮、立方体、下载盒、细箭头 ThinChevron。
- 公共（`ui/components/StrokeIcons.kt`）：FolderStrokeIcon、ArchiveBoxIcon。新增图标
  **优先入公共文件**。
- 尺寸：快捷行/齿轮钮内 22dp；行尾细箭头 20dp；列表 leading（文件夹/归档盒）24dp。

### 4.3 新增图标操作指引
1. 用 `strokeIcon(name) { pathBuilder }` helper（fill=null、1.7f、Round）；复杂路径可用
   `addPathNodes("svg path data")` 直接转写。
2. 发布前用 SVG 快速目检：把路径贴进 `<svg stroke-width="1.7">` 渲染成图核形状
   （模拟器不可用时的标准替代验证）。
3. 颜色永远交给 `Icon(tint=…)`，路径内 stroke 颜色会被覆盖，写 `Color.Black` 占位即可。

## 5. 页面与组件

### 5.1 卡片页（抽屉，`CardPage.kt`）
- `ModalDrawerSheet`：宽 86%、上限 360dp，右缘圆角 22dp，容器 = `surface`，遮罩默认 32%。
- **返回契约**：必须传 `drawerState`（预测性返回）+ 外层兜底 `BackHandler`；返回键永远
  先关抽屉、绝不退出应用；从卡片页进入的页面返回时**恢复打开的卡片页**（reopen 机制）。
- 结构：字标+齿轮钮 → 身份卡（仅当前身份 → 身份选择页）→ 统计卡（本周用量｜远程设备，
  贯穿竖线，两格 `weight(1f)`）→ 快捷行：定时任务 / 主题 / 模型 / 检查更新。
- 入口行范式：**图标 + 标题（+右值）（+徽标）+ 细箭头**。纯展示无箭头，可点必有箭头；
  徽标中性色（`surfaceVariant` 底），不用警示色。
- 主题行点开底部三选一（随系统/浅色/深色），与设置→外观共用同一存储。

### 5.2 会话列表（`SessionsScreen.kt`）
- 顶栏 `[头像36] 居中标题 [搜索]`，左右各 52dp 配平；头像即卡片页入口。
- 分段三段（会话/项目/已归档）：选中只用底色区分，**`icon = {}` 去打勾**（决策
  2026-09-01：打勾出现/消失会推移标签）。
- 分组顺序：需要你处理 → 已置顶 → **今天 → 前 7 天 → 更早**。
  - 时间桶（`groupByRecency`，决策 2026-09-01）：滚动窗口，精确区间 —
    今天 = `[今日零点, now]`；前 7 天 = `[今日零点 − 7×24h, 今日零点)`；其余（含无
    时间戳）= 更早。本地时区；clock/zone 注入可测。选滚动而非自然周，避免周初
    "本周"组瞬间清空（ChatGPT 同款约定）。
  - 组头可折叠（`SectionHeader`：labelMedium + 计数常显 + 箭头），折叠态
    `rememberSaveable`，**不跨启动持久化**；空组不渲染；组内新→旧。
- 行 = `ListItem`（白 surface 底），**无行间分隔线**（搜索结果页除外）。
- cron 告警条：复用 `HealthStrip` 形态（ERROR=errorContainer / OVERDUE=surfaceVariant），
  仅失败/逾期时出现。
- 运行状态色走 `StatusColors.kt`（语义色，独立于品牌蓝；深浅双档）。

### 5.3 项目 / 已归档
- 项目行：leading 描边文件夹 24dp，项目色着**线条**；随当前身份过滤，无身份标。
- 归档行：leading 描边归档盒 24dp（`onSurfaceVariant`）+ 标题/模型副行；无分隔线、无
  trailing 按钮；点击打开，**长按**出菜单（取消归档 / 删除+确认）。

### 5.4 聊天页（`ChatScreen.kt` / `ChatComponents.kt`，本轮确认值）
- 顶栏与正文同底（`background` 色）、裸图标；标题左侧 24dp 身份头像是**唯一**身份信号，
  无身份文字。
- 用户气泡：`surfaceVariant @78%`、圆角 22/22/**7**/22（右下小尾角）、内边距 16×11、
  正文 17sp/25；宽度上限 ≈ 屏宽 82%。助手回复**无气泡**纯排版。
- 输入区：浮动 `Surface`，圆角 30（聚焦 28）、tonal 1dp + shadow 7dp、最小高 60dp；
  内含麦克风、无边框输入框、48dp 圆形发送键 —— 发送键用 **theme primary**（明确决策：
  核心全局控件不随身份变色）。
- 新建会话入口唯一 = 列表页 FAB（顶栏语音建会话入口已删除，语音输入只在输入区内）。

### 5.5 长按菜单范式
统一 `ModalBottomSheet`：标题（≤2 行）+ `ListItem` 操作项（leading 图标）；破坏性操作
红色（error）且必须 `AlertDialog` 二次确认。

### 5.6 动效（`ui/theme/Motion.kt` 及既有惯例）
- 导航转场：120–190ms tween + 轻微横向位移（chat 进出场见 `HermesNav.kt`）；
  折叠/列表项用 `animateItem()`；节制使用 —— 新动效先对齐这些时长再谈新曲线。

### 5.7 无障碍
- 可点目标最小 **48dp**（含 IconButton 默认触达区，图标本体可小于此）。
- 纯装饰图标 `contentDescription = null`；可操作图标必须给双语描述。
- 颜色对比：正文 ≥4.5:1，大字号/图形 ≥3:1（头像白字由 §2.4 的明度硬约束保证）。

### 5.8 间距
无独立 spacing scale（现状快照）：页边距 24dp（卡片页）/16dp（列表），组件内 16/18dp。
新界面**沿用最近似的现有页面取值**，不发明新值；如需体系化 scale 另行立项。

## 6. 文案

- 产品名统一 **Hermes GO**（字标、磁贴、关于页、崩溃报告、诊断/对话分享主题、表格导出
  文件名 `HermesGO-Table-*`）。安装包 ID 与签名不变。
- 「切换身份」专指 profile；聊天内 Persona 选择叫「切换人格」，不得混用（决策 2026-09-01）。
- 所有用户可见文案走 `localized(language, zh, en)` 双语；错误提示遵循
  `ERROR_HANDLING.md` 的 `HR-<AREA>-<NNN>` 规范。

## 7. UI 改动验证补充（在 `AGENTS.md` 基线之上）

`AGENTS.md` 的测试基线（单测 + assembleDebug + 交接检查）照常执行；UI 改动**额外**要求：

1. 纯函数逻辑（分组、排序、缩字边界）注入 clock/zone 单测钉死边界。
2. 模拟器双主题截图：浅色 + **系统浅色而应用选深色**（§2.2 的历史 bug 组合）。
   模拟器必须经 `scripts/dev/emulator.sh start` 启动（该脚本固化了本机的稳定规程：先停
   Gradle 守护并清残留 qemu/adb、限内存 2048、禁快照、只用 Pixel 官方镜像（HONOR 折叠屏 AVD 已整体弃用，任何场景不再启动）、构建与启动
   分时、见 "hanging thread" 立即杀掉重来 —— 决策 2026-09-01，源自多次 QEMU 挂死排障）。
   结构性替代（待立项）：扩充既有 Roborazzi 截图测试（0.1.45 引入）覆盖卡片页与会话列表，
   使 UI 校验脱离模拟器在 JVM 内完成。
3. `fontScale 1.3` 下受限宽度文本完整（长设备名场景，§3.3）。
4. 图标改动：SVG 渲染目检形状（§4.3）。
5. 真机验收后才可声称设备验证；模拟器不可用时须在提交信息与交付说明中明示
   （惯例见 0.1.67 提交）。
