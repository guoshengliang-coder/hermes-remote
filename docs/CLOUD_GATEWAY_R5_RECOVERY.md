# Cloud Gateway R5-B 旧服务恢复基线

R5-B 为香港主机现有的旧 Node Gateway 建立可验证的回滚制品。它不会把 R3/R4 的 staging 命令开放到
production，也不会停止、重启或切换线上服务。生产捕获会读取明确列出的运行时、配置、lifecycle、
Nginx 和 systemd 文件，把 tar 数据流直接送入 OpenSSL CMS AES-256-GCM；香港主机不会落地明文归档。

## 安全边界

- 恢复私钥必须在 Mac 或另一台恢复主机生成并以 `0600` 保存，绝不能复制到香港主机或提交 Git。
- 香港主机只接收公开证书。加密归档和清单均视为私密运维数据，不得提交；清单包含文件路径和哈希，
  但不包含文件内容或 Token。
- 捕获要求 `production:<sourceHostname>` 精确确认、Linux 主机名匹配、旧 systemd 服务在捕获前后均为
  active，并且 R5-A 使用的 identity 文件哈希完全一致。
- 被恢复执行的 Gateway 入口文件必须位于这组 identity 文件中；归档清单会重新计算 identity digest，不能
  只修改清单字段来伪造 R5-A 绑定关系。
- 捕获根必须覆盖 `runtime`、`configuration`、`lifecycle`、`nginx` 和 `systemd` 五个角色；同一角色可列出
  多个精确路径，以免为了两个 Secret 文件捕获整个配置目录或 TLS 私钥。根路径本身不能经过符号链接；
  内部只接受普通文件、目录和目标仍位于捕获根内的相对符号链接。设备、socket、FIFO、绝对链接、路径
  穿越、超限文件集和捕获期间发生变化的内容都会失败关闭。为保证 Linux 捕获能在 macOS 异机严格校验，
  目录遍历会忽略 Finder/AppleDouble 生成且不参与服务运行的 `._*` 元数据文件。
- 输出使用独占创建，不覆盖已有归档、清单或证据。失败时删除本次部分输出，线上文件保持只读。
- 恢复必须在主机名不同的机器上，以 `isolated:<sourceHostname>` 精确确认。工具先校验密文大小、SHA-256、
  收件证书和归档文件清单，再解密到一次性 `0700` 根目录；无论成功失败都会停止临时进程并删除明文。
- 临时 Gateway 强制绑定 `127.0.0.1` 的指定高位端口，并强制关闭账号认证和绑定开关。兼容 smoke 要求
  `/health` 返回 200、错误旧 Token 返回 401、恢复出的正确旧 Token 返回 200 或预期的 Connector 离线
  状态 502/503/504。它不连接公网 Nginx，也不要求 Connector 在线。

## 操作顺序

先在异机的受保护目录生成一次性恢复密钥对：

```bash
umask 077
openssl req -x509 -newkey rsa:3072 -nodes -days 31 \
  -subj /CN=Hermes-R5-Legacy-Recovery \
  -keyout recovery-recipient-key.pem \
  -out recovery-recipient-cert.pem
```

把公开证书复制到香港主机，按照 `ops/legacy.capture.example.json` 创建不入库的私密配置。`identityFiles`
必须与已审计的 R5-A 生产配置逐字一致。配置根应只列出 Gateway 恢复必需的环境与 Token 文件，不得顺带
捕获 TLS 私钥；Nginx 根应填写真实普通文件，而不是 `sites-enabled` 符号链接。
确认输出目录空间和权限后运行：

```bash
node scripts/hermesctl.mjs legacy-capture \
  --config /secure-input/hermes-go/legacy-capture.json \
  --confirm production:<source-hostname>
```

只有命令返回 `ok: true` 后，才把 `.cms` 和 `.manifest.json` 一起复制到异机。复制后再次比对命令返回的
密文 SHA-256。按照 `ops/legacy.restore.example.json` 创建恢复配置并运行：

```bash
node scripts/hermesctl.mjs legacy-restore \
  --config /secure-input/hermes-go/legacy-restore.json \
  --confirm isolated:<source-hostname>
```

成功后产生 `hermes-go-legacy-recovery-v1` 证据，其中检查项严格为 `archive_hash`、`files_restored` 和
`service_start`。将该证据放到 R5-A 配置指定的异机证据路径；手写或同机生成的 JSON 不构成证据。

## 生产执行门禁

代码、测试或 PR 合并不授权上述捕获。真正执行前必须重新确认以下内容：

1. 精确的只读捕获路径、总大小上限、输出位置和执行账户读取权限；
2. 影响是读取线上文件并短暂消耗 CPU/磁盘 I/O，不停止服务、不重载 Nginx、不切流；
3. 回滚仅为删除本次新建的加密输出，线上运行状态无需恢复；
4. 恢复私钥仍只在异机，且有足够空间完成解密与临时启动；
5. 用户明确授权此次生产快照后再运行。

失败统一返回 `HR-OPS-011` 的双语结构化错误，技术原因经过 Token、密码、私钥和用户路径脱敏。
