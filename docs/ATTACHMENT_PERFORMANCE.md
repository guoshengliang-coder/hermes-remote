# HG-232 · 附件下载和播放性能

## 范围与实现

本轮优化 Android 的附件下载、下载后打开和原画视频读取，以及 Gateway / Connector 的独立文件通道。
Web 的 Blob 下载路径只做兼容性回归，后续接入分段下载仍是独立范围。没有新增 Mac 监听端口、云端整文件
暂存、转码或 HLS 服务，也没有改上游 Hermes。普通文件仍在完整下载后交给外部查看器。

### 双端协商与回退

Connector 的准备响应增加可选 `maxConcurrentRanges: 4` 和 `maxRangeBytes: 1048576`。
Gateway 在原有独立通道能力响应上增加 `X-Hermes-File-Concurrency: 4`。
Android 取双方并发上限的较小值，最多 4；缺少字段/响应头时按单路处理。旧描述符的分块默认仍为 256 KiB。
`maxRangeBytes` 是新客户端的请求上限；服务端原有 8 MiB Range 安全上限保留，旧客户端不受影响。

| Gateway | Connector | 新 Android |
|---|---|---|
| 新 | 新 | 最多四路，256 KiB～1 MiB 自适应请求 |
| 旧 | 新 | 单路，使用 Connector 宣告的分块上限 |
| 新 | 旧 | 单路、256 KiB |
| 旧 | 旧 | 原有独立通道或小文件旧 GET 回退 |

Gateway 与 Connector 均只允许同一 opaque reference 的四个范围重叠；另一个引用（包括另一个手机准备的
引用）仍收到既有 `HR-FILE-013`，不会抢占正在传输的文件。Gateway 的全局 64 个活动范围容量不变，
每个返回 capability 仍独立、单次使用，取消、权限撤销和控制连接断开继续逐范围清理。

Connector 通过私有有界 Keep-Alive 连接池复用 Mac→Gateway 的 HTTP(S) 回传连接，最多四条连接。
控制断开时销毁连接池并清空引用。所有范围共用一个按字节预约的限速时钟：四路仍共用
`FILE_DOWNLOAD_BYTES_PER_SECOND`（默认 4 MiB/s），不是四路各占 4 MiB/s。

### 下载与持久化

Android 先用 256 KiB 范围；一个批次完成少于 1.5 秒时逐次加倍，最多 1 MiB，超过 3 秒则减半，
最小 256 KiB。每批最多四路、最多 4 MiB 有效载荷。小文件不会下载超出文件边界的数据。
逐字节进度回调汇总为临时进度，每 500 毫秒刷新；它不写入续传 checkpoint。

先收齐并校验本批所有范围，再按顺序追加文件、fsync，最后持久化连续前缀。没有稀疏文件或新数据库格式。
一段失败、源文件变化、切换账号、播放抢占、暂停/取消均不提交本批空洞；重试从原有已验证前缀继续。
全部数据仍校验 206、ETag、Content-Range、长度及 owner，绝不拼接 200 响应。文件换代标识不是内容摘要。

下载前逐块查询播放缓存，遇到缓存命中则切断网络范围，避免重下已经播放的数据。
仅视频下载写入播放器缓存，普通文档/压缩包不再挤占视频缓存。下载后的完整文件仍可直接本地播放或分享。

### 播放与缓存

HG-232 的首播及 seek 索引读取保持按需小范围，不让预取先于索引发现。当时确认连续读取后，只预取后一个 256 KiB 块，
与当前块交付解码重叠；前台读取直接消费这个任务，不重复请求。close/seek、暂停和后台释放 loader 及其预取。
源身份、引用续期和缓存 key 规则沿用原实现。播放期间仍保留保守的本机下载让行策略，
没有引入「缓冲足够就抢跑下载」的新交互。

磁盘缓存为独立 LRU，2026-10-24 后默认 3 GiB、可选 1/3/5 GiB，详见 `ARTIFACT_CHANNEL.md`。
启动或显式刷新建立内存索引，普通批次不再全目录扫描；容量／30 天闲置／低空间清理共用该索引。写入使用原子 rename 和读取时长度/SHA-256 校验；易失缓存不再逐块 fsync，
断电留下的缺失/损坏块会作为 miss 重取。持久下载的 fsync 不变。批量发布缓存按索引淘汰，
已命中的块不重复写入。

## 可复现基准

工具：`scripts/benchmark-artifact-download.mjs`。只创建临时合成文件和 loopback HTTP 服务，使用实际
ArtifactDownloadBroker / ArtifactDownloadSource，校验完整 SHA-256，不接触生产或真实附件。

```sh
npm run build
node scripts/benchmark-artifact-download.mjs --connections=1 --range-bytes=262144
node scripts/benchmark-artifact-download.mjs --connections=2 --range-bytes=1048576
node scripts/benchmark-artifact-download.mjs --connections=4 --range-bytes=1048576
node scripts/benchmark-artifact-download.mjs --connections=4 --range-bytes=1048576 --adaptive=true
```

2026-10-23，本机 8 MiB 文件，每个请求额外注入 40 ms 控制消息延迟，Mac 合计限速 4 MiB/s：

| 配置 | 请求数 | 回传连接数 | 耗时 | 吞吐 MiB/s |
|---|---:|---:|---:|---:|
| 改动前，单路 256 KiB（基线提交 19fb249c） | 32 | 32 | 3453 ms | 2.32 |
| 连接复用后，单路 256 KiB | 32 | 1 | 3560 ms | 2.25 |
| 两路，固定 1 MiB | 8 | 2 | 2138 ms | 3.74 |
| 四路，固定 1 MiB | 8 | 4 | 2060 ms | 3.88 |
| 四路，模拟 Android 自适应批次 | 13 | 4 | 2224 ms | 3.60 |

本组自适应用时减少约 36%，不是线上提速承诺。单独复用连接未在本次 loopback 条件下带来吞吐提升，
可以确定的是连接数减少；吞吐收益来自减少等待和重叠传输。本次固定四路仅略快于两路，不能据此推断真实
公网链路也需要四条连接。工具输出的 probeP95Ms 是同进程合成 HTTP 探针，**不是真实 Hermes 聊天延迟**。

附件速度还受 Mac 上行、手机网络、Gateway 链路和总限速约束。不能拿 APK 静态服务器直下载的收益比例套用。

## 测试影响与交付边界

回归覆盖：双端协商与旧端默认值；四路并发与容量释放；同引用/不同引用隔离；总限速；池复用/断开销毁；
乱序响应只提交连续前缀；失败与取消不提交空洞；账号变化前后检查；缓存复用；进度不作为 checkpoint；
首播不抢先预取、连续读取命中预取和关闭取消；缓存损坏与限额。原有 200/206/416、源变化和播放抢占用例保留。

需要分别交付 Gateway、Connector（签名组件）和 Android；Desktop GUI、上游 Hermes、release-server、Web
及其认证路径无需修改。协议新增字段均可选，不改协议版本、最低客户端版本、数据库或版本号。
本轮开发和本地测试不授权生产部署或分发 APK。

真实家庭/蜂窝/NAT、原始 HONOR 编码、各 OEM 后台策略仍须真机 smoke。最终记录应区分 L1、L2、L3，
不把模拟器或本地注入延迟结果写成真实用户测速。

### 2026-10-23 本地验证记录

- `npm run build` 通过。Node 基线：Protocol 18/18、Connector 153/153、Gateway 223 通过 + 32 个
  opt-in 跳过、release-server 41/41；脚本 432 通过、1 跳过，2 个既有 legacy-recovery 解密失败。
  在未修改的主分支也重现同样两个失败，本机为 LibreSSL 3.3.6；没有修改恢复工具或绕过测试。
- 独立进程网络集成：在 `gateway/` 执行
  `RUN_NETWORK_TESTS=1 node --test --test-name-pattern='HG-222: real Mac' dist/legacy-routing.integration.test.js`，
  通过。验证了双端能力、实际四路范围、旧 GET 上限、权限/文件变化，以及 1 GiB 有界传输期间的 mock
  控制命令。全次用时约 30 秒，不能解释为家庭网络吞吐。
- Web `typecheck`、994 项测试和 build 通过；没有改变 Web 数据读取路径。
- L3：Pixel_9_API_36_1 / emulator-5554，真实 Media3 解码及播放器场景 3/3，通过小视频、超过
  100 MiB 的尾索引 MP4、seek、下载缓存复用、Pause/Play/Download 和后台停止网络读取。
  134,986,891 与 4,963,467 字节的合成视频，首帧均只读取 1,031,307 字节；预取没有增加这组夹具的
  首帧字节需求。这不是原始 HONOR 视频或蜂窝网络验证。
- L2：本机没有连接物理手机，未执行。未增加 targetSdk 门控行为，API 36 不作为 API 37 平台认证。
- 本地 debug APK 的证书 SHA-256 与 `SIGNING.md` 一致。没有分发该包、增加版本号或操作生产服务。

## HG-236 · 安卓视频并发预取

本轮只修改 Android 播放读取及测试，不调整画质、播放器缓冲时长、服务端限速或跨端契约。
替换播放互斥锁后的读取最多占四个槽位，下载独占四槽，播放租约仍让本机下载让行。
Source 再以协商的并发上限约束实际 HTTP；旧端保持单路。续期前排空旧引用请求，同批过期只续期一次，
ETag/大小变化与账号变化继续拒绝。整个窗口随 close/seek、暂停和后台取消。

首次/跳转读保持 256 KiB；确认连续读取后先预取最多两段，快段 (<1.5s) 逐步扩到四段、每段最多
1 MiB，慢段 (>3s) 减半段大小并降到一路，同时取消远端预取。最多保留 4 MiB 预取载荷与当前
<=1 MiB 载荷。每段独立等待，后段慢不会阻塞前段交付；成功范围拆成原缓存块，不重下命中块。

回归重点：真实 HTTP 并行而非仅启动协程、旧端单路、窗口边界、乱序与复用、快慢适应、关闭/跳转取消、
并发续期、账号变化后不发布缓存、缓存空洞与下载排空。具体验证及测量记录见本节后续记录。

真机待验：同一原始视频、同一手机和网络交替测试基线与优化包，分别记录冷缓存首帧时间、拖动到 25%/
75% 的恢复时间（至少各五次，报告 p50/p95）、连续播放两分钟的缓冲次数/总时长、请求数和传输字节数。
覆盖 Wi-Fi/蜂窝/弱网、连续快速跳转、暂停恢复、后台与网络切换。区分首次索引、连续读取和缓存重播；
不把本地合成延迟、模拟器或附件整文件下载的比例当作公网视频收益。

### 2026-10-24 验证与测量

- L1：定向 23/23、完整 JVM **2681/2681**，`assembleDebug` 和 `lintDebug` 通过。
  首轮并行构建期间 Kotlin lint/FIR 在 instrumentation 源文件内部崩溃；停止 Gradle daemon、
  隔离本 worktree 的 lint 缓存后，源码不变的独立 `lintDebug` 通过，没有禁用规则或检查。
  APK 公共证书 SHA-256 与 `SIGNING.md` 一致；本地包只用于模拟器验证，不分发。
- L3：**Pixel_9_API_36_1 / emulator-5554 / API 36**，`VideoStreamingDeviceTest` **5/5**。
  覆盖真实 Media3 首帧、超过 100 MiB 的尾索引 MP4、seek、下载缓存复用、实际 Pause/Play/Download
  控件和后台停止读取。首帧读取 769,163 字节，未等完整文件；总帧样本大小分别为 4,963,467 与
  134,986,891 字节。没有新增 targetSdk 门控行为；API 36 不构成 API 37 平台认证。
- L2：没有连接物理手机，未执行。真实网络与 OEM 场景保持待验证。

可复现命令（需本地 SDK/JDK 和空闲模拟器；仅使用合成视频，不访问生产）：

```sh
./scripts/dev/emulator.sh start
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hermes.client.ui.chat.VideoStreamingDeviceTest
```

夹具给每个请求注入 120 ms 延迟，各 HTTP 响应**共用** 4 MiB/s 总预算。对比同一新版读取器的
单路/256 KiB 回退策略与四路/自适应策略，交替顺序，各三次；不是签出旧提交的整包 A/B。
连续读取走真实 ArtifactVideoDataSource、认证 HTTP 和缓存，完整读取 4,963,467 字节并校验 SHA-256：

| 策略 | 三次连续读取耗时 ms | 中位 ms | 请求数 | 峰值并发 |
|---|---|---:|---:|---:|
| 单路，256 KiB | 5091 / 4499 / 4485 | 4499 | 19 | 1 |
| 协商四路，自适应 | 1949 / 1864 / 1892 | 1892 | 9 | 4 |

本组合成条件下中位耗时减少约 **58%**，没有增加下载字节；六次摘要均为
`4d3829b654003baec92e0d966193c146dd64da9ca86944676d95d0be620aafd3`。
这是连续范围读取结果，不能作为手机公网吞吐、首帧或卡顿改善比例。

真实 Media3 的 120 ms 夹具冷缓存样本（每组 n=3，p95 为 nearest-rank、即该组三次最大值）：

| 策略 | 首帧 p50 / p95 ms | seek p50 / p95 ms | 首帧字节 | seek 后额外缓冲次数 |
|---|---:|---:|---:|---:|
| 单路，256 KiB | 2083 / 2168 | 383 / 489 | 769163 | 0 |
| 协商四路，自适应 | 876 / 960 | 286 / 339 | 769163 | 0 |

这些首帧/seek 样本峰值并发仍为 1，不能把差值直接归因为并发；样本小且受解码器/调度抖动影响。
900 ms 请求延迟的单次弱链路对照首帧为 3234 / 3240 ms，seek 为 1084 / 1070 ms，基本一致，
说明按需索引的往返等待没有被并发窗口消除。短样本只覆盖一次 seek，不能证明两分钟连续播放卡顿率。
原始指标由 logcat 的 `HG236_PIPELINE` / `HG222_STREAM_PROOF` 标签输出；不记录用户路径或凭据。
