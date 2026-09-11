# Stitch 基线快照

> For agents: this directory pins the Stitch screens that `docs/DESIGN.md` treats as the visual
> design source. The rules (labels, keys, what counts as a baseline) live in `docs/DESIGN.md` §7
> item 8; this file is only the mechanics — how to pull, render, hash and compare. Stitch is
> read-only for agents.

## 目录里有什么

| 文件 | 内容 |
|---|---|
| `stitch.lock.json` | 索引：屏幕 ID → 键 → DESIGN.md 章节 → 代码文件 → HTML 哈希 → 实现状态；`pairs` 记浅暗配对的几何差异 |
| `<key>.html` | Stitch 导出的 HTML 原文，一字不改。文本，`git diff` 可读 |
| `<key>.png` | 设计尺寸渲染：390 CSS px 宽 @2x，高度取 Stitch 画布高度的一半 |
| `<key>.roborazzi.png` | 叠图尺寸渲染：411×891 CSS px @2.625x = 1078 px 宽，与 `android/app/screenshots/` 的 golden 同尺寸 |

键的格式是 `page.view.state[.overlay].theme`，例如 `sessions.chats.default.more-menu.dark`。
同一个键也用作截图用例名。

## 拉取（通过 Stitch MCP，由人发起）

1. `list_projects` → 记下「Hermes GO」的 `updateTime`，与锁文件 `project.updateTimeAtPull` 比较。
   没变可以直接停。
2. `get_project`（或 `list_screens`）→ `screenInstances` 里带 `label` 的条目。只看 `基线：` 与
   `候选：` 前缀；无标签、`hidden: true` 的忽略。
3. 对每张要入库的屏幕 `get_screen` → `htmlCode.downloadUrl` 是带签名的临时地址，当场 `curl` 下载，
   存为 `<key>.html`。缩略图 `screenshot.downloadUrl` 只有 163×512，**不要用它做任何比对**。
4. `shasum -a 256 <key>.html`，与锁文件 `sha256` 比较。变了的屏幕做第 5 步。
5. 渲染（见下）并跑配对检查：把浅暗两版去掉颜色类后 diff，只看几何类
   （`text-[..px]`、`font-*`、`p*-`、`gap-`、`rounded*`、`w-/h-`、`leading-`、`tracking-`）。
6. 报告固定四段：**新增基线 / 候选待裁决 / 已有基线的规格变更 / 浅暗配对的几何差异**。
   没有内容的段写「无」。规格变更先落到 `android/app/src/test/resources/design-conformance.json`，
   再改 `docs/DESIGN.md`，再改代码。

## 渲染

```bash
# 设计尺寸（高度 = Stitch 画布 height / 2）
scripts/design/stitch-render.sh docs/design/stitch/<key>.html docs/design/stitch/<key>.png \
  --width 390 --height 1223 --scale 2

# 叠图尺寸（与 Roborazzi golden 同尺寸）
scripts/design/stitch-render.sh docs/design/stitch/<key>.html docs/design/stitch/<key>.roborazzi.png \
  --width 411 --height 891 --scale 2.625
```

脚本只依赖本机的 Google Chrome 和 macOS 自带的 `sips`。Chrome 桌面窗口最窄约 500 px，脚本用
居中的 iframe 绕过这一点，细节见脚本头部注释。

**网络依赖**：HTML 引用 Google Fonts（Plus Jakarta Sans / JetBrains Mono / Noto Sans SC /
Material Symbols）和 Tailwind CDN。离线渲染会静默退到系统字体，图就不是参照了。提交前看一眼
标题字形：Plus Jakarta Sans 与 PingFang 一眼可辨。字体和 Tailwind 没有 vendoring 进仓库 —— Noto
Sans SC 按 unicode-range 拆成四百多个子集，体量不值得；参照 PNG 已经提交，读的人不需要重渲。

## 比对

- **几何**：把 `<key>.roborazzi.png` 与对应 golden 在 Preview 里叠看，或临时把参照图放到 golden 的
  文件名下跑 `./gradlew :app:compareRoborazziDebug` 拿差异图（跑完把 golden 换回来）。
- **数值**：`design-conformance.json` 的 `design` 列直接从 HTML 类名读，1 CSS px = 1 dp
  （`docs/DESIGN.md` §3.4）。
- **观感**：真机（L2）。字体三方不同，像素不会全等，叠图只看几何。

## 首批基线（2026-09-11）

会话列表浅 / 暗、「更多」菜单浅 / 暗、搜索结果浅 / 暗，共 6 张，Stitch 标签仍是旧格式
（「会话列表界面」等），锁文件 `stitchLabel` 记的是拉取时的原文。改标签要在 Stitch 网页里做，
MCP 没有改标签的接口。
