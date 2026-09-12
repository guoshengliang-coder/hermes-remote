# Android 真机与开发机操作手册

`AGENTS.md` 定**哪几层验证要跑**（L1/L2/L3），`docs/DESIGN.md §7` 定 **UI 改动的验证规程**，
`docs/ANDROID_SMOKE.md` 记**规程跑不到、仍待设备确认的用例**。这份文件管的是**动手那一层**：
怎么把一台真机操作起来（装哪个包、各家 ROM 装机时要点什么、怎么连到本地后端、测完怎么复原），
以及一台开发机缺了哪一层时怎么补、哪些事已经实测过不必再试。

这些经验原先只存在某一台开发机的私有笔记里，换一台机器、换一个 agent 就要重踩一遍。凡是在
真机或开发机上踩到的新坑、测出的结论，**写回这里**，不要只留在对话或本机笔记里。

**写什么、不写什么。** 写对任何参与这个项目的人都成立的事，包括"在 macOS 上会这样"。不写只属于
某一台机器或某个人的事实 —— 内存多大、连着哪几台手机、装了哪些 AVD，这些 `android-capabilities.sh`
运行时就能读到，写死只会过时；代理端口、个人习惯也不写。密码、token 与 `environment.md` 的内容
永远不进这里（见 `AGENTS.md`）。

## 0. 先问本机有什么

```bash
./scripts/dev/android-capabilities.sh
```

它列出连着的每台真机（品牌、型号、SDK）、没授权或离线的设备、以及真机最高 SDK 是否达到
`targetSdk`。多台时默认目标是按 serial 排序的第一台，用 `ANDROID_SERIAL=<serial>` 改。

**所有 adb 命令都带 `-s <serial>`。** 真机和模拟器、或两台真机同时在线时，不带 `-s` 的 adb 会
直接拒绝执行，或者作用到你没打算操作的那台上。

遍历全部真机用下面这个形式，别的写法会**静默地只跑第一台**（原因见 `AGENTS.md` 的 L2 小节）：

```bash
for s in $(./scripts/dev/android-capabilities.sh --serials); do
  adb -s "$s" <command>
done
```

## 1. 构建与装哪个包

```bash
cd android && ./gradlew :app:assembleDebug
```

- 报 `Unable to locate a Java Runtime`：把 `JAVA_HOME` 指向一个 JDK 21。
- **新建的 git worktree 里没有 `android/local.properties`**（它被 gitignore 了），Gradle 会找不到
  SDK。从主工作树拷一份过去。
- 装这个带版本号的包：`android/app/build/outputs/apk/distribution/debug/Hermes-Remote-<version>-debug.apk`
  （`assembleDebug` 本身就会生成它）。这只用于**本地真机测试**；要交给测试者或发布的包必须走
  `AGENTS.md` 的发布门禁，那是另一回事。

**签名证书是锁死的。** 所有构建机共用一张 debug 证书（`docs/SIGNING.md`），手机上已装的版本
如果是别的证书签的，覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。装之前可以先核对：

```bash
~/Library/Android/sdk/build-tools/<version>/apksigner verify --print-certs <apk> | grep SHA-256
```

输出要和 `android/app/build.gradle.kts` 里的 `expectedDebugCertificateSha256` 一致。**遇到证书不一致，
不要生成新的 keystore 来"解决"** —— `AGENTS.md` 明令禁止。

## 2. 安装：各家 ROM 的确认页

```bash
./scripts/dev/device-install.py --serial <serial>    # 或 --all 装到每一台
```

它自己找最新的 `Hermes-Remote-<version>-debug.apk`，核对包名和签名证书，然后 `adb install -r`
（覆盖安装，**保留 App 数据**），并处理手机上弹出的确认页。结束时打印一行 `RESULT`，写明装上的
版本、是覆盖升级还是全新安装。

### 确认页：脚本处理已知的，AI 或人处理未知的，处理完再沉淀成脚本

各家 ROM 的确认页不同，还会随系统更新变化，所以分成三段处理：

1. **已知页面 → 配方自动确认。** `scripts/dev/install-recipes.json` 里每条配方描述一个见过的页面
   和确认步骤。命中就自动处理，不需要有人在场。
2. **未知页面 → 停手，报告，原地等。** 驱动不点任何东西，保存截图和控件树，打印
   `NEEDS_ATTENTION`，但**继续持有这次安装**。AI 会话（或人）用下面两条命令看清页面并处理，
   驱动随后从下一个页面接着走：

   ```bash
   ./scripts/dev/device-install.py --inspect   --serial <serial>   # 前台、每个元素的文字/可点/勾选状态/位置
   ./scripts/dev/device-install.py --tap-text "<文字>" --serial <serial>
   ```

   AI 看截图理解页面，但**点击一律按控件树里的位置**，不按截图估坐标 —— 给模型看的截图是缩放过的。
   人不在机器旁边时，AI 可以把截图发到主人手机上问。
3. **处理完 → 会反复出现的就收录成配方。** 判断标准：

   - **收录**：每次安装都会出现的安装器确认页。
   - **不收录**：一次性的弹窗（系统更新提醒、广告、账号中心）。关掉或返回即可，不点里面的确认。
   - 收录时把首次遇到时保存的控件树放进 `scripts/dev/install-pages/` 当 fixture（先确认它只含
     安装器窗口、没有通知或个人信息），配方里写上观察日期和机型，然后跑
     `./scripts/dev/device-install.py --self-test`。自检会确认配方仍然命中自己的页面，并且**不会**
     命中别的应用的安装、也不会命中非安装器的前台。

### 红线（脚本和 AI 都一样）

- **授权范围**：主人授权脚本和 AI 在开发手机上确认**本仓库构建的 Hermes GO 安装包**的安装确认页，
  包括勾选「已了解风险」一类的提示。只限这一件事，不是"可以随便操作手机"。
- **只确认我们自己的安装**。配方点击前要同时满足：前台是安装器；手机上有一个由 adb 发起、字节数
  与这个 APK 完全一致的待确认安装会话（`dumpsys package` 的 Active install sessions）；页面上能读到
  应用名和版本号的，还要求两者都在。会话这一条不依赖厂商页面怎么画 —— HONOR 的第一个弹窗是独立
  窗口，控件树里根本没有应用名，靠的就是它。
- 页面出现**密码、验证码、登录、支付**等字样时，什么都不点 —— 配方不点，`--tap-text` 也会拒绝。
- 不按写死的坐标点。曾经有一次盲点点开了荣耀账号中心和浏览器，出事的原因正是点之前没看屏幕。
- 同一个页面连点三次还没变化就停下，交给人。

### 各厂商的确认页

| 厂商 | 确认流程 | 状态 |
|---|---|---|
| **vivo** V2166BA（Android 13 / SDK 33） | 一页「安全守护提示您 · 外部来源应用」。勾「已了解应用的风险检测结果」后再点「继续安装」。不要求账号密码，装完直接回到桌面、没有「完成」页。按钮在控件树里一直是 enabled，灰色只是画出来的，所以要以勾选框读到 `checked=true` 为准 | **已收录**配方 `vivo-external-source`（2026-09-11） |
| **HONOR** CLK-AN00（Android 14 / SDK 34） | ① 底部弹窗「来历不明的应用……负有全部责任」→「继续」（独立窗口，控件树里没有应用名）② 「未经荣耀安全审核」，勾「已了解此应用未经荣耀应用市场检测，可能存在风险。」→「继续安装」（「继续安装」正上方有个很大的「玩过该游戏的也爱玩」推荐按钮）③ 「安装成功」→「完成」④ 点「完成」后再弹「为了安全着想，请开启安全模式」。②③④ 上都有「安全模式 · 开启」推广。另外可能弹出系统更新对话框（`com.hihonor.ouc`），与安装无关 | ①② **已收录**配方 `honor-unknown-source-dialog`、`honor-unreviewed-app`（2026-09-11）。③④ **故意不收录**：到 ③ 时 `adb install` 已经返回成功，点「完成」只会引出 ④ 的推销；装完就停在结果页，需要收拾时按两次返回键退出。**任何时候都不点「开启」/「开启并退出」** —— 那是在改手机的安全设置，不在授权范围内 |

### 已知行为

- **超时杀掉 `adb install` 不会取消手机上的安装会话。** 驱动放弃之后，确认页如果被点掉，
  手机照样会装完（vivo 上实测，驱动退出 20 秒后版本落地）。所以超时报告写的是手机上的实际版本，
  而不是笼统的"失败"。默认超时 900 秒：已知页面几秒就结束，超时只在等人远程处理时才起作用。
- 手动用 `adb install` 时，这条命令会一直阻塞到确认页被点掉为止，自动化里要放后台跑。

确认是覆盖升级而不是重装（数据还在）：`firstInstallTime` 不变、只有 `lastUpdateTime` 变了。

```bash
adb -s <serial> shell dumpsys package com.hermes.remote </dev/null | grep -E "versionName|firstInstallTime|lastUpdateTime"
```

查看当前前台和屏幕上的文字（判断卡在哪一步）：

```bash
adb -s <serial> shell dumpsys activity activities </dev/null | grep -m1 topResumedActivity
adb -s <serial> shell uiautomator dump /sdcard/ui.xml </dev/null >/dev/null
adb -s <serial> exec-out cat /sdcard/ui.xml | grep -o 'text="[^"]\+"'
adb -s <serial> exec-out screencap -p > screen.png
```

## 3. 连到本地后端

真机做 UI 验证**不需要生产 Relay token**：

```bash
./scripts/dev/dev-stack.sh start
```

它起 mock Hermes → connector → gateway，并对每台在线设备做 `adb reverse`。然后在 App 里配置
`http://127.0.0.1:8787`，token 填 `dev-app-token`。端口被别的项目占用时用
`HERMES_DEV_GATEWAY_PORT=<port>`，`emulator.sh` 会跟随同一个变量。

这项连接配置会保存在 App 中；停止开发栈不会自动恢复原来的 Relay。测试结束后，应通过账号登录
重新选择远程设备，或重新扫描 Desktop 配对二维码。未保存过账号会话时，账号登录入口会忽略残留
的 loopback 开发地址并使用默认公网 Relay；冷启动若先显示连接失败，点「检查连接设置」也会进入
账号登录，避免开发栈停止后被困在旧版连接配置里。

- **新建的 worktree 里也没有 `node_modules`**（和 §1 的 `local.properties` 是同一类坑）。这时
  `dev-stack.sh start` 只会说 `gateway/dist missing — run npm run build`，而 `npm run build` 又会
  先报一堆 `Cannot find module 'pg' / 'svix'`。顺序是 **`npm install` → `npm run build` →
  `dev-stack.sh start`**，三步都在 worktree 根目录跑。
- 在 Claude Code 里用后台方式启动，否则它会随命令超时一起被杀掉。日志在
  `$TMPDIR/hermes-dev-stack/`，不在 `/tmp`。
- mock 把每一条回复都流进同一个固定会话「Mock 会话」，不管你从哪个会话发出；其它会话会一直
  停在"生成中"。多轮对话要在「Mock 会话」里造。
- mock 会弹出审批和澄清弹层，挡住滑动手势 —— 先点「拒绝」或「跳过，让 agent 自行判断」关掉。

## 3b. 卡片页的「反馈与建议」行需要本机配置

这一行只在构建携带 MissionGo 的 endpoint 与 SDK token 时存在（`android/app/build.gradle.kts` 配置期读取，
两者任一为空 = 功能不存在，不报错）。新克隆、他人机器与普通 CI 都没有，所以**本机打的调试包默认看不到这一行**，
这是设计，不是 bug。要在真机上验它，在 `android/missiongo.properties` 写：

```properties
missiongoEndpoint=https://missiongo.mrlgs.net
missiongoSdkToken=<从 MissionGo 控制台取>
```

该文件已被仓库根 `.gitignore` 忽略（第 12 行），**不要提交、不要把 token 贴进聊天或日志**。
发布包的这两个值由 `.github/workflows/android-release.yml` 从仓库 secrets 注入，与本文件同一对。
改完要重新构建：Gradle 在配置期读它并写进 `BuildConfig`。

**0.1.120 就是这么把这个功能弄丢的。** 它是从一棵没有 `missiongo.properties` 的工作树本机构建并手动
发布的，两个值编译成空串，`UnavailableFeedbackReporter` 生效，卡片页那一行整条不渲染。包名、版本、
签名、哈希全对，所以当时所有门禁都是绿的 —— 直到有人去找这个入口才发现。

因此发布门禁现在会证明**产物里真的带着这份配置**：`scripts/package-debug-apk.sh` 读取本次编译生成的
`BuildConfig.java`，再用 `scripts/lib/apk_feedback.py` 在 APK 的 dex 里同时查 endpoint 和 token，
缺任何一半都拒绝放行
（它拦下过真实的 0.1.120，放行了真实的 0.1.119）。查的是**产物不是构建输入**：配置期读取会被 Gradle
的 configuration cache 复用，输入对而编进去的 `BuildConfig` 是旧的，这种情况只查输入发现不了。

实践后果：**本机没有这个文件就发不了版**。正常路径是让 `android-release.yml` 从 secrets 构建发布，
而不是把密钥文件复制进发布工作树。

**已知行为（2026-09-11 vivo V2166BA 实测）**：编辑器是 MissionGo SDK 自己的 Activity；在编辑器里按返回会把
整个应用任务退到桌面，而不是回到卡片页。应用进程仍在，重新点图标即恢复原状态。与本仓的调起代码无关
（`ui/feedback/FeedbackEntry.kt` 只把宿主 Activity 交给 SDK），要修得在 SDK 侧。

## 3c. 验"杀掉 App 再冷启动"这一类

有一类状态只有真的经历一次进程死亡才验得到（跨进程持久化、通知栏在进程死后剩下什么）。要点：

- **"划掉 App" ≠ `force-stop`，两者结果不同，别混用。**
  - 从最近任务划掉：杀进程，**通知栏的卡还在**。这是用户日常做的事，也是绝大多数 bug 报告的场景。
  - `adb shell am force-stop <pkg>`：杀进程**并清掉该应用的全部通知**。它比用户的操作更狠，用它去验
    "冷启动后通知还在不在"会得到假阴性。
  - 脚本化地模拟"划掉"：`adb -s <serial> shell input keyevent KEYCODE_APP_SWITCH` 再滑掉卡片；
    要确定性更高就用 `am force-stop`，但**只在不关心通知的用例里**用。
- **`adb install -r` 保留应用数据**（`device-install.py` 走的就是它），这是验持久化的前提。
  一旦用了 `pm clear` 或卸载重装，本地快照就没了，用例直接失效。
- 冷启动后先看诊断日志里那一行 `[phase] restored N runtime(s) from disk` —— 它直接告诉你恢复了几条，
  比在界面上猜快得多（见 `docs/DIAGNOSTICS.md`）。
- 每一台单独记结果。"划掉 App"的语义和通知栏的清理策略正是各家 ROM 分歧最大的地方。

## 4. 驱动与取证

- `adb shell input text` 不接受非 ASCII 字符（会抛 NPE）。用 ASCII，空格写成 `%s`。
- 键盘弹起时发送按钮会移位：先输入，再 `input keyevent 4` 收起键盘，再点发送。
- 在会话或会话列表页连按两次返回会退出 App。
- `uiautomator` 读不到跳转气泡的 content description，这类元素用截图确认。
- 滚动循环一类的 bug，临时加 `android.util.Log.d` 再 `logcat -s <Tag>` 看逐帧数据，比截图有效。
- 往相册塞测试图片：`adb -s <serial> push <png> /sdcard/Pictures/`，它们会排在「最近」最前面。

## 5. adb 故障

- 设备显示 `unauthorized`：在那台手机上点「允许 USB 调试」，勾「始终允许这台电脑」可以免掉以后
  重复授权。`android-capabilities.sh` 会把它列成 "not usable"。
- `adb devices` 永久卡住（adb server 停在 `usb_init`，端口 5037 始终不打开）：macOS 原生 USB
  后端被卡住了，改用 `ADB_LIBUSB=1`（每一次 adb 调用都要带，包括 dev-stack）。2026-09-03 在
  HONOR 上遇到过，当时手机只暴露了 HonorSuite 的虚拟光驱。

## 6. 测完复原

```bash
adb -s <serial> reverse --remove-all
adb -s <serial> shell rm /sdcard/Pictures/<测试图片>
```

**`pm clear com.hermes.remote` 会清掉这台手机上 App 的全部数据，包括真实的连接配置和账号。**
开发用的手机上通常装着日常在用的版本，只有在你确定这台手机上没有要保留的状态、或者主人明确
同意时才执行。覆盖安装（`install -r`）本身不会清数据。

## 7. 本机缺一层时怎么补

`android-capabilities.sh` 会说缺哪一层。L2 缺的是手机或授权（见 §5）；**L3 缺的是模拟器**，按下面补。
几个 GB 的下载，每台机器装一次，不进 git。

1. **查镜像包名。** 包名带次版本号（`android-37.0`，不是 `android-37`，猜错只会得到一句 `not found`）；
   ABI 跟随本机 CPU（Apple 芯片用 `arm64-v8a`，Intel 用 `x86_64`）：

   ```bash
   sdkmanager --list | grep 'system-images.*arm64-v8a'
   ```

2. **装模拟器和镜像**，镜像选与 `targetSdk` 一致的 `google_apis` 版本：

   ```bash
   yes | sdkmanager --install emulator 'system-images;android-37.0;google_apis;arm64-v8a'
   ```

3. **建 AVD**，只用 Pixel 设备定义（HONOR 折叠屏 AVD 已整体弃用）：

   ```bash
   echo no | avdmanager create avd -n Pixel_API_37 -k 'system-images;android-37.0;google_apis;arm64-v8a' -d pixel_9
   ```

4. `./scripts/dev/emulator.sh start`，再跑一次 `android-capabilities.sh` 确认 L3 变成 available。

踩过的坑：

- **设了代理时 sdkmanager 可能下载失败**，报 `Failed to connect to https://dl.google.com/…: Connection refused`。
  先测直连能不能到：

  ```bash
  curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' --noproxy '*' https://dl.google.com/android/repository/repository2-3.xml
  ```

  直连通的话，就在去掉代理变量的环境里跑 sdkmanager：

  ```bash
  env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy sdkmanager --install …
  ```

  2026-09-11 在一台开发机上，直连 0.14 秒拿到 200，走代理 6.6 秒，而 sdkmanager 经代理直接失败。
- **macOS 没有 GNU `timeout`。** 用它包一条命令会得到 `command not found`，看起来像"超时没有输出"，
  很容易误判成网络问题。
- sdkmanager 会提示自己已弃用、改用同目录下的 `android sdk`；截至 2026-09-11 仍可正常使用。

## 8. 实测过的结论（别再重复试）

- **调大 Gradle heap 不会让构建变快。** 把 `org.gradle.jvmargs` 从 `-Xmx2048m` 提到 `-Xmx4096m`
  （写在 `~/.gradle/gradle.properties`，它的优先级高于项目根的 `gradle.properties`，实测生效），
  冷构建 `:app:testDebugUnitTest --rerun-tasks` 从 66 秒变成 67 秒，在误差之内（M4 / 24 GB，2026-09-11）。
  2048m 确实偏紧：Kotlin 编译守护进程**继承**这个值，峰值用到 1703 MB（83%），放开后用到 2339 MB。
  但时间花在 KSP、Hilt、Kotlin 编译的 CPU 上，不在 GC 上。调大只算防 OOM 的保险；项目里那份保持
  `-Xmx2048m`，因为 CI runner 也读它。想提速先测量，别从 heap 下手。
