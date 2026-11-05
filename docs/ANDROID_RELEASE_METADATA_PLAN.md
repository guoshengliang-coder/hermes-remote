# Android 纯升版 PR 检查优化方案

状态：**仅观察，尚未启用检查豁免**。当前 PR、main、`prepare` 与正式 package gate 的检查均继续执行。
本方案没有分配版本、构建 APK 或授权发布。现行规则仍以 `AGENTS.md`、
`docs/INTEGRATION.md` 和 `docs/APP_UPDATE.md` 为准。

## 本次实现

`scripts/ci/android-release-metadata.mjs BASE_SHA HEAD_SHA` 读取两个不可变 Git 提交，判断净变更能否
由现有 `bump-android-release.mjs` 的纯元数据变换完整重现。CI 只把结果写入 step summary，
没有输出供 job 条件消费，也没有修改 `changed-components.mjs` 或 Android 两个 job 的条件。
分类结果不代表代码审查、基础提交检查成功、升版批准或发布批准。

候选必须同时满足以下条件：

- BASE 是 HEAD 的祖先，两个完整 SHA 均可读取；净变更恰好涉及下面三个普通 `100644` 文件。
- `android/app/build.gradle.kts` 只按既有工具改写 `appVersionName` 和 `appVersionCode`，
  patch 与 code 各加一，声明唯一、数值合法；其余字节必须与工具输出相同。
- `android/README.md` 只按工具插入一个标准发布条目并替换旧版本 APK 文件名。
  条目摘要可自定义；正文、历史条目或其他操作说明有额外修改时不算候选。
- 新增 `android/releases/<新版本>.json`，只含 `channel: internal` 和非空发布说明数组，
  使用工具的规范 JSON 格式。沿用发布 schema 的 20 条、每条 500 字符限制，拒绝控制字符、
  空白条目、重复键、额外字段、其他渠道以及修改已有版本说明文件。

代码、依赖、权限、CI、签名配置、文件模式、符号链接、删除、重命名或其他额外变更均不能成为候选。
Git 对象缺失、分支分叉、解析失败也返回非候选。每条 Git 命令最多 5 秒、输出最多 2 MiB；
只读白名单元数据 blob，不执行 Gradle，不读取工作区文件或凭据，不输出发布说明正文。
手工编辑但不符合规范字节的合法升版可能被拒绝，这种情况继续完整检查即可。

## 启用后的提议边界（仍待决定）

| 环节 | 提议 |
|---|---|
| 本地 `prepare` | 保留单测与 lint |
| 纯升版 PR | 仅在可信分类与基础提交检查都通过时，省去该 PR 的 Android 单测、lint；保留元数据检查 |
| 普通代码 PR、无法判断的 PR | 继续完整 Android 检查 |
| 合并后的 main | 继续完整 Android 单测与 lint，合并闸仍等待 main 全绿 |
| 版本与发布决策 | 保留用户确认，纯升版仍是 `INTEGRATION.md` 表 2 的红灯变更 |
| 正式发布 | 保留完整 package gate、签名、反馈配置、版本一致性、干净树与公网 size/hash/index 校验 |

未来启用时必须另行修改并同步现行规则，不能把本次 summary 直接当作跳过依据：

1. 只适用于 PR 事件，以该 PR 的精确 BASE/HEAD 为准，BASE 必须是 HEAD 的祖先。
   基础提交必须已有成功的 main CI，检查信息无法确认时走完整路径；HEAD 改动后重新分类。
2. **分类器必须从可信 BASE 提交加载**，包含其升版工具与 schema 依赖。
   可以从 BASE 的 Git 对象导出这三个模块到 runner 临时目录，再针对 checkout 的仓库读取提交。
   BASE 缺少模块、依赖或无法运行时走完整路径，禁止退回使用 PR 修改后的分类器。
   本次 observer 从 checkout 执行仅因它没有任何门禁权限。
3. 改动工作流或分类器的 PR 仍完整运行，不得自行修改后给自己授予豁免。
   整个路径保持普通 CI 的只读权限，不接触发布 secrets。
4. 用真实获批升版 PR 对比：记录相同阶段的 PR 检查耗时、main 耗时、正式发布耗时及最终产物校验。
   只比较相同阶段与可比缓存条件，区分 runner 排队和执行耗时。

最近一次审计中，0.1.194 的版本 PR CI 约 6 分 20 秒、main 约 7 分 15 秒、发布约 8 分 48 秒。
省去 PR 那一轮预计可减少约 6 分钟；这是基于历史关键路径的估算，**尚非实际发布提速实测**。
本次 observation 不减少任何 Android 检查，因此暂时没有这部分收益。
缓存状态和改动内容会显著影响耗时：只改工具的 PR 不会改变 Android BuildConfig，不能用它的
热缓存检查耗时直接代替真实升版 PR 的耗时，也不能保证每次节省 6 分钟。

## 验证与历史回放

2026-11-05，以 `b4833de9f85cd6a89a27eb685ea2c37340722581` 的 main 历史为样本：

| 样本 | 结果 |
|---|---|
| 最近 20 个非 merge、标题匹配 `build(android): release` 或 `chore(android): release` 的提交，与各自第一父提交比较 | 19 个候选，1 个回退 |
| 回退的 0.1.177（`d84c1656543ef2d340a7d68e33549b23159036b9`） | README 还改写了下方 HG-230 说明，超出标准升版条目，因此回退正确 |
| 最近 20 个触及 `android/app/src` 的非 merge 提交，与各自第一父提交比较 | 20 个非候选；其中 HG-237 含删除，触发只接受新增/修改的 diff 解析回退 |
| 20 次升版分类本机耗时 | 每次约 85–103 ms，不含启动 CI、checkout 或测试 |

这证明的是样本内容匹配结果；第一父提交不保证就是当时 PR 的实际 BASE，回放也不证明基础提交 CI
或批准状态。不能把 19 个匹配解释成已经豁免了 19 次 CI。

可在完整历史的 checkout 中复核单次结果：

```bash
node scripts/ci/android-release-metadata.mjs \
  c309ad537189d9059c550c1afeb461e683251633 \
  350c07d391d910bee2f7637853dd498f3e501d9f --json
```

合成 Git 仓库回归覆盖正常输出、长摘要、代码和依赖夹带、异常字段、渠道、版本跳跃、重复 JSON、
符号链接、执行权限、非法 UTF-8、缺失文件、分叉 BASE、不可读对象、工作区污染与 CLI 输出隐私。
CI 门禁断言另外确认两个 Android job 仍只由原有组件分类结果触发。

```bash
node --test scripts/test/android-release-metadata.test.mjs \
  scripts/test/changed-components.test.mjs \
  scripts/test/bump-android-release.test.mjs scripts/test/workflows.test.mjs
```

以上聚焦检查 72 项通过；首轮本机 `RUN_NETWORK_TESTS=1 npm run test:ci` 为 977 项通过、
21 项跳过、0 失败（跳过项主要需要 PostgreSQL）；严格 UTF-8 修正后的脚本基线为 486 项通过、
1 项平台跳过、0 失败。该新增回归先在旧实现上复现失败，再在修正后通过。
本次只改检查工具与说明，没有 Android UI、
协议或用户可见错误行为变化，不新增错误码；未做 L2 真机或 L3 模拟器验证，也未生成分发 APK。
