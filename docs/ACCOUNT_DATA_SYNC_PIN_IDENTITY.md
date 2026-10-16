# HG-216 · 置顶同步的源身份门禁

核对日期：2026-10-16。Hermes GO 基线 `738b3cf4`；上游源码基线
`17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121`。

**本轮完成身份可行性核对，没有实现或开启云端置顶。**
置顶无需等待完整已读的内容修订版本，但仍需可信、不可复用的会话实例身份。
当前核对的上游接口与文件标记不足以满足 [P0](ACCOUNT_DATA_SYNC_P0.md) §3–7 的这个条件。
不把这个结论推广到未核对的上游版本，也不把源码实验当成生产或完整 HTTP 联调。

## 1. 为什么不能直接上传现有置顶键

Android 的 [PinStore](../android/app/src/main/java/com/hermes/client/data/repository/PinStore.kt)
保存 `deviceId/profile/sessionId` 组合；Web 的 [pins](../web/src/app/pins.ts)
按 Mac 保存 `profile/sessionId`。两者都是本地偏好，不带账号和会话实例代际。
旧数据不能仅凭“当前登录了哪个账号”就自动归属并上传。

云端键仍需账号主体、Mac 授权代际，以及可信来源给出的 `profile/sessionId/sessionEpoch`。
否则同名 Profile 重建、同 ID 会话重建或数据库恢复之后，旧的 true、false 墓碑和离线请求
可能被套到新的实例。给不可靠的复合键做 HMAC，或由客户端随意生成 UUID，都不补足源身份。

本轮不改变当前“仅此设备”的置顶契约，不新增迁移提示、同步错误或能力字段。
后续接入仍按已讨论的推荐推进：能力满足后同步；旧置顶需用户确认导入；Legacy 保持本机。
导入确认不能代替归属和源身份校验，也不能覆盖已有云端墓碑。

## 2. 逐项核对过的来源

下表路径均指上述上游提交，只读查看源文件，没有读取用户配置、凭据或数据库。

| 来源 | 实际语义与限制 |
| --- | --- |
| `hermes_cli/web_routers/profiles.py:_profile_to_dict`、`hermes_cli/profiles.py:ProfileInfo` | 公开 name/path、默认标记和展示配置，没有 Profile 实例 UUID/epoch；同名不是不可复用身份 |
| `hermes_cli/profile_identity.py:purge_profile_identity` | 这里的 identity 是路由/会话键等身份收尾；代码还专门防止删到同名新 Profile，不能把模块名当 UUID 证据 |
| `hermes_cli/web_routers/sessions.py:get_session_detail` | 返回数据库会话行并盖上实际 serving profile；ID 支持前缀解析，未来验证必须要求返回完整 ID 精确匹配，不能签发用户输入的前缀 |
| `hermes_state_sessions.py:_insert_session_row/create_session/delete_session` | ID 由调用方提供；新行的 `started_at` 来自 `time.time()`，删除后可以再次使用同 ID；不是不回绕的实例计数器 |
| `hermes_state.py:_ensure_db_file_generation` | `state_meta.db_file_generation` 首次写入后复用，`PRAGMA application_id` 只在 0 时写入；它们随备份复制，不是恢复外部的防回滚凭据，也不是每会话代际 |
| `hermes_state_common.py:conversation_generations` | 按 source/session_key 的路由 peer 计数，用于会话路由/缓存亲和；不是每个历史 session 的实例标识，表本身也在数据库中 |
| `hermes_cli/web_routers/profiles.py:_stat_fingerprint` | dev/inode/size/mtime 是侧栏缓存失效依据，内容更新会变化；不是稳定实例契约 |

注意：当前 `hermes profile create --clone-all` **明确排除** state.db、会话历史等目录。
不能用它来声称会复制会话身份。下面的复制/恢复实验使用测试创建的 SQLite 备份，
没有运行 Profile 克隆、导入或删除命令。

Connector 只读补偿方案同样不能靠路径、inode、mtime 或轮询“没看见删除”推断永久连续性。
inode 可在原地恢复时保留；mtime 等又会在普通编辑时改变。
缺少证据时重新猜一个 epoch，无法同时证明正常重启后保留置顶、恢复后不复活。

## 3. 使用真实上游存储代码的隔离实验

测试：[hermes-session-identity.test.py](../scripts/test/hermes-session-identity.test.py)。
它导入指定、可信且 tracked 文件干净的上游 checkout 的真实 `SessionDB`，没有复制其实现。
在导入前设独立临时 `HERMES_HOME`、开启上游测试隔离保护、禁用源码目录的字节码写入；
所有数据库路径都由测试在新临时目录中创建，没有接受现有数据库路径的入口。
这是显式选择的开发实验，**不由普通 `npm test` 下载/执行本机上游代码**。

| 实验 | 实际结果与证明范围 |
| --- | --- |
| 删除后重建相同 ID，注入相同墙钟值 | 两个生命周期的完整 `get_session()` 返回行相同，数据库代际也相同；证明时间戳不是唯一签发器。相同时间由 fault injection 构造，不宣称普通连续两次创建必然相同 |
| 备份 → 删除并以不同时间重建 → 关闭句柄 → 原地恢复 → 重新打开 | 恢复了原完整会话行、db_file_generation 和 application_id；inode 保留。没有在活跃 WAL 句柄下覆盖文件，也没有恢复真实数据 |
| 复制测试数据库 | 新文件的 inode 不同，但完整行和两个持久标记相同；证明文件内标记会被备份复制，不宣称该复制经过 Profile CLI |
| 普通修改标题 | ID、started_at 和数据库代际不变，文件 mtime 改变；不能把每次 mtime 变化当成新会话 |

本机结果：**4/4 通过**，Python 3.11.15、SQLite 3.53.1。
通过表示这些反例在该源码成立，**不表示源身份门禁通过**。
未启动 Hermes 服务、未触碰生产、未运行 Android L1/L2/L3 或浏览器跨端置顶测试。
P0 的规范模型已经假定传入 epoch 可信；它的成功不能替代这里缺少的签发/恢复证明。

复现时只使用可信源码与其已有 Python 环境。指定源码有 tracked 修改时测试直接拒绝，
不清理或覆盖改动；可用一次性本地 clone 固定提交（不访问网络、不改变原 checkout）：

```sh
upstream_fixture=$(mktemp -d /private/tmp/hg216-upstream-identity.XXXXXX)
git clone --shared --no-checkout <可信上游源码目录> "$upstream_fixture/source"
git -C "$upstream_fixture/source" checkout --detach 17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121
<上游Python环境>/bin/python -B scripts/test/hermes-session-identity.test.py --source "$upstream_fixture/source"
```

命令从 Hermes GO 根目录执行；测试打印实际提交和匿名断言结果。
本机使用源码 clone 是因为原 checkout 有两处与本次无关的 tracked 修改；未改动或纳入它们。
实验结束仅移除自己创建的临时 clone；数据库 fixture 由测试退出时清理。
新版本若不能重现反例，需检查真正改变的身份语义，再决定是否解除门禁，不能机械更新预期。

## 4. 解除门禁所需的最小证据

不在本轮发明已上线的接口名。新增来源能力应有独立版本和有界、只返回身份元数据的读取，
禁止为置顶读取或上传整段消息、system prompt、路径或配置。
上游不支持、返回不完整、身份不匹配或连续性无法证明时，本地置顶照常可用，云端能力不宣告。

1. **明确实例边界。** 普通 append、标题修改、进程重启保留身份；同名重建、同 ID 重建和
   需要隔离的恢复生成新实例。恢复旧字节不能重新签发旧实例；仅在 DB 中加 UUID 仍不足。
   必须说明恢复信任边界，例如不随该备份回滚的代际账本，及无法证明连续性时的处理。
   对 Connector 离线期间发生、且所有可见证据均相同的恢复，不能宣称靠只读轮询必能识别。
2. **原子来源与作用域。** Profile 和会话实例必须来自同一次可信读取；返回完整会话 ID，
   绑定实际 Mac/profile。混合读取前后代际、缺省 Profile 猜测、旧缓存或客户端自报都不能签发。
   Gateway 再绑定认证账号和当前授权代际；Mac 凭据仍不离开 Mac。
3. **恢复与重启实验。** 在来源实现上验证普通重启保持、删除/重建改变、同名跨 Profile/Mac 隔离、
   离线期间重建、备份回滚、来源账本丢失/损坏、元数据读取与重建竞争。
   若选择“连续性不明就重置/重新确认”这种体验退让，需要先说明影响并让用户选择，不能默认降低原契约。
4. **权限线性化。** 云端持久层要与真实 grant/revoke/unbind 共用事务边界，先检查权限再重放终态。
   不能把 binding generation 当作共享 grant 的 authorization_generation；重新授权创建新代际，
   旧缓存、队列、cursor 及幂等结果不恢复。30 天清理覆盖实体、日志和终态中的私人 payload。
5. **真实端接入。** 来源满足后，才落实独立多实体有序日志/固定快照、CAS/墓碑、耐久离线队列、
   显式旧数据导入和 Web/Android 消费测试。P1a 的 accountProfile 单例 cursor 不能直接复用。
   增量、撤销、丢 ACK、旧请求和离线重启均需真实 PostgreSQL 与消费者验证。

这份需求可作为未来上游协作输入，本轮没有向上游发消息、建 issue、修改源码或升级 Hermes。
遵守 [one Hermes per Mac](MANAGED_HERMES_STRATEGY.md)：不建第二套用户数据库或恢复已移除的本地补丁体系。

## 5. 迭代和发布判断

P1b 置顶与完整已读是**两个分别未通过的门禁**：置顶缺可靠实例身份；完整已读还缺内容修订/快照证明。
不再笼统写“置顶可独立实施”而省略身份前提。P1c 账号级模型收藏/推理偏好可以先行，
但仍需它自己的持久化、跨端与发布验证，不代表在本轮实现。

本轮仅文档与显式运行的测试，不修改产品代码、能力、schema、版本源或当前 UI。
Gateway Docker 构建和客户端打包不会包含本测试，无新 APK 或服务/Web 包需要分发。
此前 P1a 的实际启用仍需独立的生产与发布核验；发布/安装一个新版本不能补足本轮发现的源能力。
HG-216 保留原文档 PR #590 的开发完成证据，本轮进展用评论记录，不覆盖原状态或声称已验收。
