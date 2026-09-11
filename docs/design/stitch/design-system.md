---
name: Hermes GO 仓库基线
colors:
  surface: '#faf9f5'
  surface-dim: '#dbdad6'
  surface-bright: '#faf9f5'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f4f4f0'
  surface-container: '#efeeea'
  surface-container-high: '#e9e8e4'
  surface-container-highest: '#e3e2df'
  on-surface: '#1b1c1a'
  on-surface-variant: '#494641'
  outline: '#777268'
  outline-variant: '#c9c7c2'
  surface-tint: '#ffffff'
  primary: '#004ac6'
  on-primary: '#ffffff'
  primary-container: '#2563eb'
  on-primary-container: '#eeefff'
  secondary: '#605c54'
  on-secondary: '#ffffff'
  secondary-container: '#e4e1da'
  on-secondary-container: '#605c54'
  tertiary: '#824500'
  on-tertiary: '#ffffff'
  tertiary-container: '#a65900'
  on-tertiary-container: '#ffede1'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#410002'
  background: '#faf9f5'
  on-background: '#1b1c1a'
  surface-variant: '#e3e2df'
  status-good: '#2e7d32'
  status-warn: '#b45309'
  status-bad: '#b91c1c'
  status-running: '#0369a1'
  subline-faint: '#a8a29e'
  status-warn-graphic: '#d97706'
  fab-container: '#181c24'
  spinner: '#2563eb'
  incident-container: '#fff1f2'
  incident-ink: '#9f1239'
typography:
  headline-sm:
    fontFamily: Roboto Flex
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.48px
  title-lg:
    fontFamily: Roboto Flex
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.3px
  title-md:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 22px
    letterSpacing: -0.16px
  title-sm:
    fontFamily: Roboto Flex
    fontSize: 13px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.26px
  body-lg:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0px
  body-md:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0px
  body-sm:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.4px
  label-lg:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.28px
  label-md:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.48px
  label-sm:
    fontFamily: Roboto Flex
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.22px
  session-row-title:
    fontFamily: Roboto Flex
    fontSize: 15.5px
    fontWeight: '600'
    lineHeight: 22.475px
    letterSpacing: -0.155px
  session-row-subline:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 17.4px
    letterSpacing: 0px
  session-row-status:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 17.4px
    letterSpacing: -0.3px
  session-row-title-read:
    fontFamily: Roboto Flex
    fontSize: 15.5px
    fontWeight: '500'
    lineHeight: 22.475px
    letterSpacing: -0.155px
  session-group-header:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.55px
  session-group-count:
    fontFamily: JetBrains Mono
    fontSize: 10.5px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0px
  session-group-note:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '400'
    lineHeight: 13px
    letterSpacing: -0.1px
  segment-label:
    fontFamily: Roboto Flex
    fontSize: 13.5px
    fontWeight: '500'
    lineHeight: 19.575px
    letterSpacing: -0.135px
rounded:
  xs: 8px
  sm: 12px
  md: 16px
  lg: 22px
  xl: 28px
  full: 9999px
spacing:
  screen-padding-horizontal: 16px
  card-page-padding: 24px
  row-min-height-2-line: 72px
  status-pillar-width: 3px
  status-pillar-height: 12px
  count-chip-radius: 6px
  count-chip-padding-h: 8px
  count-chip-padding-v: 2px
  row-padding-h: 16px
  row-padding-v: 12px
  group-header-padding-v: 8px
  topbar-height: 56px
  topbar-icon-button: 36px
  topbar-icon-glyph: 21px
  chevron-size: 17px
  segment-pill-padding-h: 12px
  segment-pill-padding-v: 6px
  segment-icon-size: 17px
  subline-top-gap: 2px
  status-top-gap: 4px
  segment-track-radius: 12px
  segment-track-padding: 4px
  segment-pill-radius: 8px
  segment-pill-min-height: 32px
  search-field-height: 40px
  search-field-radius: 18px
  menu-radius: 16px
  menu-icon-size: 20px
  fab-size: 56px
  fab-icon-size: 28px
  incident-card-radius: 12px
  incident-card-inset: 16px
  run-spinner-size: 18px
  status-dot-size: 10px
---

# Hermes GO 仓库基线

> 这份设计系统由仓库生成并由测试钉死（`android/app/src/test/.../DesignSystemExportTest.kt`）：
> front matter 里的每个颜色与排版值都等于 `Color.kt` / `Type.kt` / `StatusColors.kt` / `Tiles.kt`
> 当前的值，漂了构建就红。它是推给 Stitch 的**合同**，不是设计稿；改这里不改代码不算数。
> 决策与理由见仓库 `docs/DESIGN.md`；规程见 `docs/DESIGN.md` §7 第 8 条。

## 品牌与风格

Hermes GO 是给技术操作者用的 Android 客户端：暖纸面、蓝色品牌 chrome、状态色独立于品牌色、
描边式图标、Material 3 的层级与容器族。风格关键词是**安静、密实、可扫视**：颜色只留给
需要行动的状态，时间和结构用中性两档表达。

## 颜色：浅暗双档角色表

front matter 只能放一套颜色（Stitch 的格式限制），那是浅色档；两档的真值都在下表。

| 角色 | 浅色 | 深色 |
|---|---|---|
| surface | #FAF9F5 | #0F1217 |
| surface-dim | #DBDAD6 | #0F1217 |
| surface-bright | #FAF9F5 | #2E343D |
| surface-container-lowest | #FFFFFF | #0A0D11 |
| surface-container-low | #F4F4F0 | #161A22 |
| surface-container | #EFEEEA | #1A1F27 |
| surface-container-high | #E9E8E4 | #1E232B |
| surface-container-highest | #E3E2DF | #262C35 |
| on-surface | #1B1C1A | #E2E0DB |
| on-surface-variant | #494641 | #A8A49C |
| outline | #777268 | #8D897E |
| outline-variant | #C9C7C2 | #423F3A |
| surface-tint | #FFFFFF | #E2E0DB |
| primary | #004AC6 | #A9C7FF |
| on-primary | #FFFFFF | #00306A |
| primary-container | #2563EB | #00458F |
| on-primary-container | #EEEFFF | #D6E3FF |
| secondary | #605C54 | #C8C5BD |
| on-secondary | #FFFFFF | #32302A |
| secondary-container | #E4E1DA | #494640 |
| on-secondary-container | #605C54 | #E4E1DA |
| tertiary | #824500 | #FFB77D |
| on-tertiary | #FFFFFF | #4A2400 |
| tertiary-container | #A65900 | #6E3900 |
| on-tertiary-container | #FFEDE1 | #FFDCC3 |
| error | #BA1A1A | #FFB4AB |
| on-error | #FFFFFF | #690005 |
| error-container | #FFDAD6 | #93000A |
| on-error-container | #410002 | #FFDAD6 |
| background | #FAF9F5 | #0F1217 |
| on-background | #1B1C1A | #E2E0DB |
| surface-variant | #E3E2DF | #262C35 |
| status-good | #2E7D32 | #34D399 |
| status-warn | #B45309 | #FBBF24 |
| status-bad | #B91C1C | #F87171 |
| status-running | #0369A1 | #67E8F9 |
| status-warn-graphic | #D97706 | #F59E0B |
| subline-faint | #A8A29E | #64615B |
| fab-container | #181C24 | #181C24 |
| fab-outline | — | #3A4049 |
| spinner | #2563EB | #3B82F6 |
| incident-container | #FFF1F2 | #2A1B1D |
| incident-ink | #9F1239 | #FCA5A5 |

### 颜色规则

- **纸面是暖的，整个中性族都是暖的。** 列表行直接坐在 `surface` 上，没有白卡；纯白只用于浮起层
  （弹层、菜单、对话框、淡卡）。冷灰（#434655 / #737686 / #C3C6D7 一类）不要出现。
- **品牌色只用于 chrome**（顶栏、分段、未读点、图钉、转圈）。品牌蓝不表达任何运行状态。
- **状态色四档独立于品牌色**：good 绿、warn 琥珀、bad 红、running 青。「思考中 / 正在输出 /
  正在使用工具」一律用 running 青，不用灰。深色档四档亮度等重，只靠色相区分。
- **琥珀分两档，标记一档、文字一档。** 立柱与等待圆点用 status-warn-graphic（亮），组头标签与
  等待状态句用 status-warn（深）。这是稿子自己的区分：看的标记和读的词不是一回事。其余三个状态
  标记与文字同色。
- **时间分组不带颜色。**「今天 / 前 7 天 / 更早」的立柱用 outline-variant，「已置顶」用 outline，
  只有「需要你处理」用琥珀。时间桶绝不用绿，绿只表示「已完成」。
- **FAB 是中性近黑，不是品牌色**：浅暗两档都是 #181C24；深色再加 1px #3A4049 描边，
  因为近黑对暗底只有 1.19:1，不描边就是页面上一个洞。
- **转圈是品牌蓝 + 25% 同色完整轨道**，不是状态青，不是无轨道的孤弧。
- **告警条用 incident 玫瑰色**，不用 error-container；左右内缩 16dp、圆角 12dp 的卡，不是通栏色带。
- 深色模式的判定看应用自己的主题设置，不看系统设置。每个颜色必须同时给出浅暗两档。

## 字体与排版

- **散文用系统字体，数据用 JetBrains Mono。** 稿子里 Roboto Flex 近似 Android 系统字体。
  等宽只用在这几处：组头标签、「仅此设备」注记、计数 chip、副行的「项目 · 模型」，以及
  **只有**「正在使用 X 工具」那一条状态行 —— 其余状态句（已完成 / 运行失败 / 已中断）是散文。
  等宽字族无中日韩字形，中文自然回落到系统字体，所以等宽实际作用在拉丁文与数字上。
- 字号阶梯见 front matter。层级靠字重（600 / 500 / 400）拉开，不靠字号；16px 及以上字距收紧，
  12px 及以下字距放松。
- **会话行**：标题 15.5px，**字重按未读态区分**（未读 600 / 已读 500，对应设计师系统的
  session-title-unread / session-title-normal）；副行 12px/400 等宽；状态行 12px/500，字距 −0.3px。
  行高一律 1.45，即标题 22.475、副行与状态行 17.4。副行距标题 2dp，状态行距副行 4dp。
- 组头标签 11px/600 等宽大写、字距 0.55；计数 chip 10.5px 等宽（「需要你处理」那组 600，其余 500）；
  「仅此设备」10px/400 等宽。
- **没有字号下限。** 2026-09-11 废除了「不低于 12px」那一条，改为严格照稿。

## 形状与间距

- 圆角五档：8 / 12 / 16 / 22 / 28px。菜单 16px，分段轨道 12px、浮起片 8px，搜索框 18px，
  计数 chip 6px，告警卡 12px。
- 页边距：列表页 16dp，卡片页 24dp。会话行两行项最小高 72dp（Material ListItem 下限）。
  会话行内边距 16·12dp；组头 16·8dp；计数 chip 8·2dp。
- **没有触控下限。** 顶栏图标按钮就是 36dp，稿子画多大就是多大。2026-09-11 废除了「不小于 48dp」
  那一条 —— 与字号下限一起，换成严格照稿。

## 组件硬规则（含已否定项，均写成应当如何）

1. **顶栏**：高 56dp，`[头像36] 居中标题 [搜索36][更多36]`，图标字形 21dp。搜索留在顶栏；
   「更多」里只有「项目」「已归档」。
2. **分段控件**是下沉胶囊：surface-container 轨道 + 1dp outline-variant 边 + 12dp 圆角 + 4dp 内边距，
   选中段是浮起片（8dp 圆角、白色 / 深色 surface-container-highest），未选段透明；片内 12·6dp，
   图标 17dp，标签 13.5px/500。不用 Material 的填满品牌色分段，不画打勾。会话页 0 段或 2 段
   （会话 / 机器人），没配渠道时整行不渲染。
3. **分组头**：3×12dp 全圆角立柱 + 11px 等宽大写标签 + 计数 chip（圆角 6dp）+ 17dp 折叠箭头。
   颜色规则见上。
4. **会话行**：`标题 / [图钉][文件夹] 项目 · 模型 / 状态行`，无行间分隔线，无 leading 图标；
   图钉在副行前缀。行尾：运行中 18dp 转圈；已完成 10dp 绿点；未读 9dp 品牌蓝点；
   「已中断」「运行失败」只留文字不留圆点。
5. **搜索框**：全 app 唯一形态 —— 填充式 surface-variant、高 40dp、圆角 18dp、前置放大镜、有字时
   尾部 ×。**不加描边**，浅暗两档都不加。
6. **搜索结果**：「标题匹配」「消息匹配」两段；结果行之间有分隔线（这是全 app 唯一允许分隔线的列表）；
   命中词用 primary 色 + 500 字重高亮。
7. **FAB**：56dp 圆形、28dp 白色加号，颜色规则见上。机器人段不显示 FAB。
8. **菜单与弹层**：下拉菜单 16dp 圆角、surface 底、20dp 前置图标；底部弹层自下而上；破坏性操作红色，
   可逆操作（如归档）不用红色。
9. **图标**：描边式（stroke），不用填充式、不用品牌标志、不用 Material Symbols 的填充字形。
10. **文案**：稿子里的告警文案、状态文案都是示意；真实文案来自错误码目录（`HR-<AREA>-<NNN>`）
    与中英双语资源。

## 与本仓不一致时怎么办

Stitch 稿子与本文冲突时，**几何以浅色稿为准、颜色与字体以本文为准**；本文与代码冲突时以代码为准
并回写本文（测试会先报）。设计师若要改本文里的任何规则，先在 Stitch 里画出来并打上 `基线-` 标签，
由仓库拉取、裁决后再更新本文。
