# 会话列表 · 真机截图（L2）

2026-09-11，HONOR CLK-AN00 / Android 14 / SDK 34 / density 480 / fontScale 1.0，
构建 0.1.116（分支 `claude/stitch-design-baseline`，PR #172），数据来自本地开发栈的 mock。

这些图是「照 Stitch 稿落地」之后的真机实拍，不是 Roborazzi 渲染。

| 文件 | 内容 |
|---|---|
| `session-list-honor-light.png` | 浅色，最终构建 |
| `session-list-honor-dark.png` | 深色，最终构建 |
| `session-list-honor-topbar-before.png` | 顶栏缺陷现场：`Modifier.height(56.dp)` 把状态栏内边距一起算了进去，标题竖向裁切、与首个组头贴住 |
| `session-list-honor-topbar-after.png` | 改用 `expandedHeight = 56.dp` 之后 |

## 图里能看到什么

- 顶栏内容行 56dp，标题居中完整；两个动作按钮 36dp、字形 21dp。
- 副行「项目 · 模型」是 JetBrains Mono；中文项目名回落到系统字体，所以「赫尔墨斯远程」不是等宽。
- 文件夹图标用设计源最淡的一档 `#A8A29E`，比副行文字再浅一级。
- 分组立柱 3×12dp 全圆角、中性色；计数 chip 10.5sp 等宽。
- 深色 FAB 是近黑 `#181C24` 加 1dp `#3A4049` 描边环 —— 没有这圈描边它对暗底只有 1.19:1，就是页面上一个洞。

## 图里那块空白不是这次改出来的

项目名是中文的两行（重构 gateway、整理 docs/DEPLOYMENT）高 88dp，其余行 72dp，副行下方空出约
16dp。同一台机器上对 `origin/main`（c736350）跑同一组数据，那两行同样是 264px，所以不是打包
等宽字体造成的。详见 `docs/ANDROID_SMOKE.md` 的 A-05。
