# HG-252 · 普通会话列表换组重影

## 已核实的绘制路径

报告图片中，一条会话在「需要你处理」正常显示，又在「已置顶」区域和邻行文字相叠。
图片没有应用版本、设备型号或触发步骤，不能据此声称知道现场的持续时间或唯一根因。

普通列表原先给同一会话分配不同的分组 key（`n-`、`p-`、`today-` 等），每行还使用
`animateItem()`。换组因此是旧项退出、新项进入，其他行同时移动；行的透明底让两份文字
同时可见。纯分组逻辑是互斥的，这条现象不需要数据源实际返回重复会话就能出现。

受控验证先把生产列表原样提取为 `GroupedSessionsList`，保留旧 key 和动画：换组中间帧
实际出现新旧两条会话，文字还穿过了「已置顶」组头。修复只针对这条已复现的绘制路径，
不将合成数据复现等同于原报告设备已验收。

## 行为与边界

- 行的 UI 身份只由设备、profile、会话 ID 组成，采用长度前缀避免分隔符碰撞；默认 profile
  的 null/空字符串与 `default` 归一。key 是可保存的字符串，不修改磁盘置顶或未读格式。
- 普通分组列表不对行做进出或位移动画。换组、排序、折叠与状态行增减立即应用布局，
  不用加大行高、覆盖背景或裁切文字掩盖重影。
- 保留分组顺序、时间窗口、置顶与状态提示、菜单、打开目标、运行转圈和屏幕层显式滚动提示。
  机器人、搜索、归档和项目列表不改；网络、运行态状态机、协议与服务端不改。
- 设计代价是普通会话行不再有淡入淡出或移动过渡，规则记在 `DESIGN.md` §5.2/§5.6。
  没有新增或修改用户可见失败，不新增 HR 错误码。

## 回归方法

`GroupedSessionsListTest` 直接绘制生产列表及透明 `SessionRow`，不复制一个测试专用列表。
固定时钟、静态等待/结论状态避免运行转圈本身污染像素比较。测试先派发状态快照通知，
再推进 Compose 手动时钟，比较首个更新帧、中间帧和稳定帧的实际像素。只检查 semantics
不够：退出项的语义节点可以消失，但旧绘制内容仍在。首个更新前完整旧画面的调度延迟
也不等于重影，不能将它作为回归失败依据。

覆盖：置顶与待处理双向换组、四个时间桶的待处理往返、同组排序、状态行增减、折叠展开、
分组消失、点击目标，以及系统浅色/应用深色和 1.3 倍字体。`SessionListItemKeyTest` 覆盖
可变元信息不改身份、设备/profile/ID 隔离、默认 profile 与含分隔符标识。

聚焦动态测试的 PNG 输出在 `android/app/build/outputs/hg252/`，不是正式安装产物。
测试失败不得用更新静态 golden 的方式消除。

## 本轮验证结果（2026-11-14）

| 检查 | 结果 |
|---|---|
| 旧行为反向回归 | 在同一生产组件临时恢复分组 key 与 `animateItem()`，校准后的换组、排序测试均因实际绘制帧不同而失败；实验后精确恢复最终源码 |
| 新动态回归 | 5 个测试全部通过，包含 22 次状态变化的 66 张首帧/中间帧/稳定帧截图 |
| UI 身份隔离 | 4 个测试全部通过 |
| Android 基线 | `ANDROID_HOME=/Users/bs/Library/Android/sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功；2973 个测试，无失败、错误或跳过 |
| 签名 | `verifyDebugSigningKey` 通过，另用 SDK `apksigner verify --print-certs` 核对本地测试 APK，证书 SHA-256 与 `SIGNING.md` 一致 |
| diff | `git diff --check` 通过；只有本任务的源码、测试与文档变化 |

L1 已运行；浅色、系统浅色/应用深色及 1.3 倍字体动态截图已实际目检。L2 无连接真机。
L3 Pixel_API_37 可用但未启动，本次不修改目标 SDK 门控行为，动态重排由 L1 覆盖，不能代替原机确认。
本地 debug APK 仅是构建验证，未通过分发发布门禁、未交给测试者，不能当作新的 0.1.195 正式版本。
以上为开发验证阶段记录；后续提交、PR、集成与条目交接事实以 Git 和 MissionGo 的实际记录为准。
本说明不授权升版或发布。

## 仍需设备确认

本次主机探测：L1 JVM/Roborazzi 可用，L2 无连接真机，L3 有 Pixel_API_37。
原报告设备、真实运行事件时序与厂商绘制行为待真机确认，步骤见 `ANDROID_SMOKE.md` HG-252。
本变更不分配版本、不分发 APK、不发布服务，也不代表人工验收通过。

## 合并补修：用户明确授权 SDK 最新版（2026-11-14）

PR 首轮 CI 在运行测试前因旧 `io.missiongo:missiongo-feedback:0.2.5` 的公开 POM 返回 404 失败。
用户随后明确要求升级 SDK 最新版，升级与原修复共用 PR，不修改或发布 MissionGo 服务。

- 官方 `guoshengliang-coder/missiongo` 主线 `released.json` 的已发布 `androidSdk` 为 **0.2.8**，
  来源 `a939f47bc61c283d01ffae77f555bf325e9e4a41`。公开 Maven origin 不变，0.2.8 的 POM 与 AAR
  已实际下载并确认 HTTP 200；公开接入文档的 0.2.5 说明已落后于发布记录。
- 实际 AAR 中宿主直接消费的六类公共 API 签名与 0.2.5 一致，不改 endpoint、Token 注入、
  日志脱敏、错误映射、主题传递、无配置隐藏入口与宿主反馈接口。
- 刷新依赖后的首次升级构建确实在 Manifest 合并失败：SDK 新增的相机 `FileProvider`
  与宿主同为 `androidx.core.content.FileProvider`，两套 authority 和路径元数据发生冲突。
  宿主改用独立 `HermesFileProvider` 子类作为 Manifest 身份，保留原 `${applicationId}.fileprovider`
  和全部原路径；SDK 保留 `${applicationId}.missiongofeedback.fileprovider` 和仅相机缓存目录。
  不用 `tools:replace` 覆盖 SDK authority 或合并扩大它的共享路径。
- 新增宿主合并 Manifest 回归，检查两套 provider 未导出且允许逐 URI 授权、原宿主文件可读、
  SDK 相机文件可读且拒绝其他宿主缓存。SDK 编辑器真实相机/图库、WebView、返回和旋转仍须真机
  验证，见 `ANDROID_SMOKE.md` 的 0.2.8 升级章节。

升级后的聚焦反馈测试 21 项通过（含新增 provider 兼容回归 3 项）。最终
`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 全部通过，2976 项测试无失败、错误或跳过；
另核对实际测试 APK 签名与 `SIGNING.md` 一致，`git diff --check` 通过。L1 已运行，L2 无真机，
L3 未运行。后续 CI 与实际集成结果以 PR 和条目交接记录为准；本节不扩大升版/发布授权。
