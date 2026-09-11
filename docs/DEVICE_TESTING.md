# Android 真机操作手册

`AGENTS.md` 定**哪几层验证要跑**（L1/L2/L3），`docs/DESIGN.md §7` 定 **UI 改动的验证规程**，
`docs/ANDROID_SMOKE.md` 记**规程跑不到、仍待设备确认的用例**。这份文件只管一件事：**怎么把一台
真机操作起来** —— 装哪个包、各家 ROM 装机时要点什么、怎么连到本地后端、测完怎么复原。

这些经验原先只存在某一台开发机的私有笔记里，换一台机器、换一个 agent 就要重踩一遍。凡是在
真机上踩到的新坑，**写回这里**，不要只留在对话或本机笔记里。

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
- 配方只在前台是安装器、且页面上同时出现应用名和这次的版本号时才会动手，所以不会确认别的应用的安装。
- 页面出现**密码、验证码、登录、支付**等字样时，什么都不点 —— 配方不点，`--tap-text` 也会拒绝。
- 不按写死的坐标点。曾经有一次盲点点开了荣耀账号中心和浏览器，出事的原因正是点之前没看屏幕。
- 同一个页面连点三次还没变化就停下，交给人。

### 各厂商的确认页

| 厂商 | 确认流程 | 状态 |
|---|---|---|
| **vivo** V2166BA（Android 13 / SDK 33） | 一页「安全守护提示您 · 外部来源应用」。勾「已了解应用的风险检测结果」后再点「继续安装」。不要求账号密码，装完直接回到桌面、没有「完成」页。按钮在控件树里一直是 enabled，灰色只是画出来的，所以要以勾选框读到 `checked=true` 为准 | **已收录**配方 `vivo-external-source`（2026-09-11） |
| **HONOR** CLK-AN00（Android 14 / SDK 34） | 两层：先点「继续」；再勾「已了解…」并点「继续安装」；最后点「完成」。可能同时弹出系统更新对话框（`com.hihonor.ouc`），与安装无关 | 仅有文字记录（2026-09-03），**未收录**：第一次用驱动装 HONOR 时会走一遍上面的第 2、3 步 |

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

- 在 Claude Code 里用后台方式启动，否则它会随命令超时一起被杀掉。日志在
  `$TMPDIR/hermes-dev-stack/`，不在 `/tmp`。
- mock 把每一条回复都流进同一个固定会话「Mock 会话」，不管你从哪个会话发出；其它会话会一直
  停在"生成中"。多轮对话要在「Mock 会话」里造。
- mock 会弹出审批和澄清弹层，挡住滑动手势 —— 先点「拒绝」或「跳过，让 agent 自行判断」关掉。

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
