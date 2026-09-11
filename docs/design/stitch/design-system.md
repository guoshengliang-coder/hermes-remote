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
  status-warn: '#c2410c'
  status-bad: '#c62828'
  status-running: '#0369a1'
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
    fontSize: 15px
    fontWeight: '600'
    lineHeight: 21.75px
    letterSpacing: -0.15px
  session-row-subline:
    fontFamily: Roboto Flex
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
  status-pillar-height: 14px
  count-chip-radius: 6px
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
| status-good | #2E7D32 | #7CDC80 |
| status-warn | #C2410C | #FBBF24 |
| status-bad | #C62828 | #FFB4AB |
| status-running | #0369A1 | #67E8F9 |
| fab-container | #181C24 | #1E232B |
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
- **一行里只有一个琥珀。** 圆点、立柱、组头、状态行共用 status-warn，不用三个相近的琥珀。
- **时间分组不带颜色。**「今天 / 前 7 天 / 更早」的立柱用 outline-variant，「已置顶」用 outline，
  只有「需要你处理」用琥珀。时间桶绝不用绿，绿只表示「已完成」。
- **FAB 是中性近黑，不是品牌色**：浅色 #181C24 无描边；深色 #1E232B 加 1px #3A4049 描边。
- **转圈是品牌蓝 + 25% 同色完整轨道**，不是状态青，不是无轨道的孤弧。
- **告警条用 incident 玫瑰色**，不用 error-container；左右内缩 16dp、圆角 12dp 的卡，不是通栏色带。
- 深色模式的判定看应用自己的主题设置，不看系统设置。每个颜色必须同时给出浅暗两档。

## 字体与排版

- **app 使用系统字体，不打包任何字族，不用等宽字体。** 稿子里用 Roboto Flex 近似 Android
  系统字体；副行、组头、计数都用同一字族，不要 JetBrains Mono 之类的等宽字体。
- 字号阶梯见 front matter。层级靠字重（600 / 500 / 400）拉开，不靠字号；16px 及以上字距收紧，
  12px 及以下字距放松。
- **会话行三档**：标题 15px/600、副行 12px/400、状态行 12px/500（字距 −0.3px）；行高一律 1.45。
  已采纳、代码待落地（决策 2026-09-11）：标题改为 15.5px，**字重按未读态区分** —— 未读 600、已读 500
  （对应设计师系统里的 session-title-unread / session-title-normal），不按分组区分；落地后本文随之更新。
- 组头：12px/600、大写、宽字距，非等宽。
- 字号不低于 12px；11px 只允许用于 label-sm 这类非正文标签。

## 形状与间距

- 圆角五档：8 / 12 / 16 / 22 / 28px。菜单 16px，分段轨道 12px、浮起片 8px，搜索框 18px，
  计数 chip 6px，告警卡 12px。
- 页边距：列表页 16dp，卡片页 24dp。会话行两行项最小高 72dp（Material ListItem 下限）。
- 触控目标不小于 48dp。顶栏图标按钮视觉 36dp 时，触控区仍是 48dp。

## 组件硬规则（含已否定项，均写成应当如何）

1. **顶栏**：`[头像36] 居中标题 [搜索][更多]`。搜索留在顶栏；「更多」里只有「项目」「已归档」。
2. **分段控件**是下沉胶囊：surface-container 轨道 + 1dp outline-variant 边 + 12dp 圆角 + 4dp 内边距，
   选中段是浮起片（8dp 圆角、白色 / 深色 surface-container-highest），未选段透明。不用 Material
   的填满品牌色分段，不画打勾。会话页 0 段或 2 段（会话 / 机器人），没配渠道时整行不渲染。
3. **分组头**：3×14dp 立柱 + 12px 大写标签 + 计数 chip（圆角 6dp）+ 折叠箭头。颜色规则见上。
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
