# Cloud Gateway R3 Cloud Ops 合同

R3 实现 Cloud C2 的内部安装与诊断基线：`preflight`、`bootstrap`、`status` 和 `doctor`。
它只面向受控 Ubuntu x86_64 staging 主机，不授权生产部署，也不实现已有环境的升级、蓝绿切换、
排空或回滚；后四项属于 R4。

## 安全边界

- `hermesctl bootstrap` 只接受 `environment=staging`，并额外要求命令行确认值精确为 `staging`。
- 主机不执行 `git pull` 或源码构建。输入必须是 R2 生成的 OCI archive 与严格 manifest；archive
  SHA-256、image ID、架构、Server 版本和源提交全部匹配后才可安装。
- bootstrap 不生成 Secret。App、Connector 和内部状态 Token 以及 TLS 证书/私钥必须通过配置引用
  已存在的普通文件；符号链接、过宽私钥权限、短 Token 和包含换行的 Token 均被拒绝。
- Gateway 容器以内容寻址 image ID 启动，使用只读根文件系统、有界 `/tmp`、移除 capabilities、
  `no-new-privileges`、CPU/内存/PID 上限和 loopback 端口映射。
- `doctor` 只收集白名单化的配置身份、制品身份、systemd/container 状态、探针结果和依赖状态。
  它不收集 journal、请求或响应正文、环境文件、Secret 文件内容或输入文件路径。
- 所有命令通过无 shell 的参数数组执行；配置中的服务名、容器名、域名、端口和路径均严格校验。

## 配置与制品

配置合同位于 `ops/hermesctl-config.schema.json`，仓库只提交占位示例
`ops/staging.example.json`。真实配置与 Secret 属于目标环境，不得提交。

OCI bundle manifest 结构如下：

```json
{
  "schemaVersion": 1,
  "kind": "hermes-go-gateway-oci",
  "serverVersion": "0.2.0",
  "sourceCommit": "<40 hex commit>",
  "imageReference": "hermes-remote-gateway:0.2.0-<commit>",
  "imageId": "sha256:<64 hex>",
  "architecture": "amd64",
  "archiveFile": "Hermes-Gateway-0.2.0-<commit>-linux-amd64.tar",
  "archiveSha256": "<64 hex>",
  "createdAt": "<commit timestamp in UTC>"
}
```

`scripts/package-gateway-bundle.sh <output-directory>` 从 clean commit 构建 image，保存 versioned OCI
archive，并以排他创建方式写入 manifest。仓库内的相对输出必须是 `outputs/<单层目录名>`，外部
绝对输出目录必须预先存在；已有同名文件不会被覆盖。

R3 的 manifest 提供受控传输中的完整性与内容身份校验，但不宣称具备独立的制品来源签名。
在后续签名与生产发布门禁完成前，此 bundle 只能进入隔离 staging，不能晋升到生产。

## 命令合同

```text
node scripts/hermesctl.mjs preflight --config <file>
node scripts/hermesctl.mjs bootstrap --config <file> --confirm staging
node scripts/hermesctl.mjs status --config <file>
node scripts/hermesctl.mjs doctor --config <file> --output <file>
```

- `preflight`：只读检查配置、Linux/x86_64、依赖、OCI archive、已加载 image（若存在）、Token、TLS
  文件、受管目录和端口冲突。
- `bootstrap`：先通过 preflight，再载入并复核 image；原子安装受管配置、Secret、TLS、systemd 和
  Nginx 配置；启动服务并完成 loopback/public smoke。相同配置可安全重复执行。
- `status`：返回分层 JSON 状态。服务不可用时仍返回有界结构，并以 `HR-OPS-004` 标识 degraded。
- `doctor`：创建 `0600` JSON 诊断包，包含 status 与脱敏检查结果以及 SHA-256，不含原始日志。

bootstrap 使用配置摘要与阶段 journal 识别中断状态。同一摘要会从幂等阶段继续；若已有另一个
release 或未完成 journal 属于不同配置，R3 会停止，交由后续 deploy/rollback 流程处理。
每次 bootstrap 都追加脱敏操作记录：run ID、操作人 ID、环境、版本、源提交、开始/结束时间、阶段
和结果，不记录 Secret。

## 测试影响

R3 新增的可测试边界包括：未知配置字段、production 误操作、archive/manifest/image 不匹配、错误
架构、符号链接或权限不安全的输入、端口冲突、中断后同配置恢复、异配置冲突、重复 bootstrap、
探针失败、诊断包敏感信息泄漏和错误码稳定性。

自动化测试必须覆盖配置与 manifest 严格解析、哈希校验、无 shell 命令执行、模板硬化、阶段恢复、
幂等写入、操作日志字段、`HR-OPS-001` 至 `HR-OPS-005` 双语语义以及 Token/Cookie/私钥/邮箱/
用户目录脱敏。真正执行测试或隔离 staging bootstrap 前需要项目所有者确认；生产部署不在 R3
授权范围内。
