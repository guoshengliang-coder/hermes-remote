# 子项目集成规则（INTEGRATION.md）

> For agents: this is the cross-subproject integration contract, written in Chinese (the product
> owner's working language — the same deliberate convention as `docs/DESIGN.md` and the
> `docs/CLOUD_GATEWAY_R*` documents). `AGENTS.md` is the canonical agent policy and routes here;
> this document does not repeat it. Per-subproject release detail lives in the documents named in 表 1.

本仓库是单仓库多子项目：`android/`、`desktop/`、cloud（`gateway/` `connector/` `protocol/` `ops/`
`release-server/` `deploy/`）共用一条 `main`。它们的**发布物、版本真相源、门禁、可回滚性**四项全不
相同，所以"集成"对三者根本不是同一件事。本文用三张表把判断写死，避免每次临场发挥。

## 表 1 · 子项目基线

| | Android | Desktop | Cloud |
|---|---|---|---|
| 路径 | `android/**` | `desktop/**` | `gateway/**` `connector/**` `protocol/**` `ops/**` `release-server/**` `deploy/**` |
| 发布物 | 签名 APK + 公共发布索引 | `.app` / `.dmg` | HK 主机上**运行中的服务** |
| 版本真相源 | `android/app/build.gradle.kts` 的 `appVersionName` / `appVersionCode` | `desktop/Packaging/Info.plist` 的 `CFBundleShortVersionString` / `CFBundleVersion` | `gateway` `connector` `protocol` 三个 `package.json` 的 `version`，外加 `gateway/release-contract.json` |
| 门禁 | `scripts/package-debug-apk.sh` → `scripts/publish-android-apk.sh` | `npm run desktop:assets:test` / `desktop:test` / `desktop:app`（需 macOS） | `npm run build && npm test`；生产变更走 R4 候选 → 切换 → 回滚状态机 |
| 可回滚 | **否** —— Android 禁止覆盖降级，只能 roll-forward | 是，装回旧包 | 是，见 `release-contract.json` 的 `rollbackSupported` |
| 细则文档 | `docs/APP_UPDATE.md`、`docs/SIGNING.md` | `docs/DESKTOP_PHASE0.md`、`docs/DESKTOP_TEST_PLAN.md` | `docs/DEPLOYMENT.md`、`docs/CLOUD_GATEWAY_R4_PLAN.md` |

`scripts/` 与 `docs/` **按文件归属，不按目录** —— 它们是三边工具混放：

- **android**：`scripts/package-debug-apk.sh`、`scripts/publish-android-apk.sh`、
  `scripts/import-android-release-history.sh`、`scripts/lib/release_metadata.py`、`docs/DESIGN.md`
- **cloud**：`scripts/hermesctl.mjs`、`scripts/production-monitor.mjs`、`scripts/deploy-*.sh`、
  `scripts/bootstrap-release-server.sh`、
  `scripts/test/**`、`docs/CLOUD_*`、`docs/DEPLOYMENT.md`
- **共用**：`scripts/dev/**`、`scripts/mock-hermes*.mjs`（本地联调工具；不阻断合并，但改动要在提交
  信息里说明，因为三边都可能依赖它复现问题）

## 表 2 · 改了什么 → 判定

| 触发条件 | 判定 | 要求 |
|---|---|---|
| 改动只落在一个子项目，且未触及下方契约面 | 🟢 **绿** | 直接合。只跑该子项目的门禁基线 |
| 触及任一**契约面** | 🟡 **黄** | 同一分支内必须交代另外两侧：要么一起改，要么在提交信息里写明为何不需要改 |
| 改动任一**版本真相源**，或 `gateway/release-contract.json` | 🔴 **红** | 只能由集成 agent 处理，进表 3 的版本闸与发布闸 |

**契约面清单**（刻意保持在 5 条以内 —— 清单一长就处处黄灯，人会学会无视它；宁可漏一两条靠事故补）：

| 文件 | 为什么是契约面 |
|---|---|
| `protocol/src/index.ts` | 线上协议本体，含 `PROTOCOL_VERSION` / `ACCOUNT_CONNECTOR_PROTOCOL_VERSION` |
| `gateway/release-contract.json` | `minimumClients` / `protocolVersions` / `databaseSchemaVersion` |
| `gateway/src/gateway-http-router.ts`、`gateway/src/account/*-http-controller.ts` | Android 与 Desktop 直接调用的 REST 面 |
| `android/app/src/main/java/com/hermes/client/data/network/Dtos.kt`、`HermesRestApi.kt` | 上述协议在 Kotlin 侧的**手抄镜像** |
| `docs/ERROR_HANDLING.md` | `HR-*` 码表；已发布的码不可复用于其他条件 |

**Cloud 兼容性硬规则**：任何使**已发布**的 Android 或 Desktop 版本无法继续工作的改动，必须在同一次
改动里更新 `minimumClients`；且 `minimumClients.android` **不得高于**公共发布索引
`latestVersionCode` 所对应的版本 —— 否则等于把现有测试者全部锁死在无法升级的状态。

## 表 3 · 三个闸

"合并""升版""发布"是三件独立的事。**不要用一条指令同时触发它们** —— 绝大多数改动只需要过第一个闸。

| 闸 | 回答的问题 | 谁决定 | 前置条件 |
|---|---|---|---|
| **合并闸** | 能不能进 `main` | 集成 agent | PR 检查全绿；表 2 判定为绿，或黄灯已按要求交代 |
| **版本闸** | 要不要升版、升哪个子项目、升哪一位 | 集成 agent 提议，**用户确认** | 该子项目确实要交付新发布物。纯文档或纯服务端改动不升 Android 版 |
| **发布闸** | 要不要发布、发到哪 | **必须用户明确授权** | Android 走 `docs/APP_UPDATE.md`；Cloud 走 `docs/DEPLOYMENT.md` 与 R4 状态机；Desktop 需签名 + 公证 + 干净机器启动全部通过才算发布 |

**追远端判据**（合并闸的补充）：当 `origin/main` 领先于本地时，先确认三件事再合并 ——
(a) 双方是否改了同一个版本真相源；(b) 改动文件集是否相交；(c) 是否跨子项目。
三者皆否可直接合并；任一为是则停下，通过编排 agent 协调。

单仓单 `main` 意味着子项目之间**文件不重叠但发布节奏串行**：发一个 Android 版之前可能需要先合入
无关的 cloud 提交。这是 monorepo 的固有性质，不是异常，按上述判据处理即可。

## 已知盲区

表 2 的判定只能抓**结构耦合**（改了哪些文件），抓不到**语义耦合**。

具体地：`protocol/` 是 TypeScript，只有 `gateway/` 通过 import 消费它；Android 用 36 个手写
`@Serializable` Kotlin DTO 重述同一份协议，Desktop 用 Swift —— 二者都**没有**对 `protocol/` 的依赖。
因此在 gateway 内部改掉一个 JSON 字段名（文件全部落在 `gateway/` 内），判定必然是绿灯，而 Android
会在运行时静默解析失败。同理，`release-contract.json` 的 `minimumClients` 目前只被 TypeScript 侧读取，
Android 与 Desktop 没有任何代码校验它。

**契约面清单是唯一防线，它的质量决定整套规则的质量。** 两条计划中的缓解，均需独立立项：

1. **协议单一真相源** —— 由 `protocol/` 生成 Kotlin DTO，消除手抄
2. **跨端契约测试** —— Android 侧保存真实 Gateway 响应样本做解析回归，让字段变更在对面直接变红

在这两条落地之前，不要把本文的判定当作完备的安全网。
