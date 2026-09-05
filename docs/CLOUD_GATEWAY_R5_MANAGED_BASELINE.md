# Cloud Gateway R5-D 生产受管基线

R5-D 为香港主机现有的旧 Node/systemd Gateway 提供一次性、失败关闭的受管接管入口。它不会把
`hermesctl deploy/rollback` 的 staging 限制放宽，也不会启用账号认证、账号绑定或数据库。代码与
一次性环境测试不构成生产授权；真正运行会停止旧 Gateway、交接 lifecycle 状态、重载 Nginx 并切换
公开流量，因此必须另行确认维护窗。

## 接管模型

1. 严格读取 `ops/production.managed-baseline.example.json` 对应的私密配置，要求
   `production:<host.hostname>` 精确确认、root、Linux x86_64 和真实主机名一致。
2. 在任何写入前重新计算 R5-A 使用的旧运行时 identity digest，验证 30 天内的 R5-B 异机恢复证据，
   并执行旧公开服务的 Token/REST/WebSocket 兼容 smoke。
3. 将旧 identity 登记成 `hermes-go-managed-legacy-v1` 回退描述符并建立初始 `current`。描述符只保存
   兼容版本、identity digest、服务名与状态目录，不复制 Secret 或运行时内容；真正灾难恢复仍使用
   R5-B 加密制品。
4. 复用 R4 已验证的部署锁、顺序 journal、不可变 OCI 校验、蓝绿槽位、私有 Connector/REST/WSS
   smoke 和 lifecycle 单 writer 交接。R5-D 只允许第一次从 `activeSlot: null` 进入 blue 槽，不能成为
   任意 production deploy 后门。
5. 候选通过后，在停止任何服务前再次校验旧文件、旧服务活动状态以及候选 Nginx 文件哈希。随后停止
   候选与旧 writer，复制最终状态，重启候选，原子安装 upstream 与候选 Nginx 文件，再执行两次公开
   smoke 和观察窗。
6. 成功后 `current` 指向不可变 OCI release，`previous` 指向旧 identity 描述符，journal 为
   `committed`，旧 unit 被 disable 但保留。切换中任一步失败都会停止候选、反向交接最新状态、重启旧
   unit、恢复 Nginx 与 release links，并用专门的 legacy smoke 复核公开服务。

## Nginx 安全边界

生产入口不会用仓库里的 staging 模板猜测线上配置。操作者必须先从真实 Nginx 站点文件制作一份完整的
候选文件，保留同域名下所有既有发布、证书和其他路由，只把 Gateway upstream 改为
`hermes_go_gateway_production`，并且恰好包含一次配置中声明的 upstream include。配置记录候选文件的
SHA-256；接管开始和停服前都会重新校验，实际安装时再校验一次。候选文件不得直接代理回旧 8444 端口。

`configFile` 应指向 Nginx 实际普通文件，例如 `sites-available` 中的源文件，而不是 `sites-enabled` 的
符号链接。切换检查点以内容、存在性和哈希保存原主配置与 upstream；`nginx -t` 或 reload 失败时恢复
原始字节。候选配置属于受保护运维输入，不得提交仓库。

生产现有的 `/etc/nginx/conf.d/hermes-edge.conf` 是历史部署的真实主配置，因此严格 parser 只在原有
`hermes-go-*`、`hermes-remote-*` 命名规则之外额外允许这个精确 basename；其他任意文件名仍拒绝。
不能把候选写成第二个并行 `*.conf` 来绕过检查，否则 Nginx 会同时加载两个 443 default server。

## 运维执行 bundle

香港主机不以 Git checkout 或 npm 工具链作为生产依赖。`Gateway OCI` 门禁除了目标 OCI archive 与
manifest，还生成 `Hermes-R5D-Ops-<commit>.tar.gz` 和同名 manifest。运维 bundle 只包含 R5-D 入口、
`ops/lib`、候选/兼容 smoke、已构建 Connector/Protocol 和 production-only Node 依赖；不包含 Token、
TLS 私钥、生产配置、恢复证据、主机地址或 Mac Hermes 凭据。

运维 manifest 用 archive SHA-256 绑定完整内容。R5-D3 的 schema v2 将入口固定为
`scripts/production-baseline.mjs`、测试 Connector 固定为 `connector/dist/index.js`，并将内置 smoke runtime
固定为 `ops/lib/production-smoke-runtime.mjs`；schema v1 只为旧审计制品保持可读，不能用于新的生产接管。
上传前后都必须运行
`scripts/verify-production-baseline-bundle.mjs`，且运维 bundle、Gateway bundle 和当前 `main` 必须是同一
个完整提交。不能在生产主机临时安装 npm、复用旧 Connector 或从另一个提交拼接脚本。

生产入口在任何接管动作前创建权限隔离的临时目录，随机生成一次性 Basic Auth 与 Cookie，只在随机
`127.0.0.1` 端口启动模拟 Hermes，并把白名单环境交给测试 Connector。Mac 的 Hermes 地址、用户名、
密码、Cookie 和 WS ticket 不会进入香港主机。成功、失败和正常进程退出都会关闭监听、终止仍存活的
smoke 子进程并删除临时目录；该 runtime 只验证候选 Gateway 的 Connector/REST/WebSocket 链路，不参与
真实用户流量。

## 本地与一次性测试

单元和故障注入覆盖严格 parser、错误确认值、主机不匹配、旧 identity 漂移、证据绑定、账号/数据库
关闭、受管描述符幂等、production capability 隔离、loopback-only smoke 认证与 WebSocket、失败后的
目录/监听/子进程清理，以及公开 smoke 失败后专用 legacy smoke、旧 unit、原 Nginx、release links 和
最新 lifecycle 状态的恢复。

手动 workflow `Gateway R5-D Managed Baseline` 在一次性 Ubuntu 24.04 runner 内建立 R3 legacy 服务、
本地 CA、Nginx、真实 Connector 与两个隔离槽，生成仅供本次 runner 使用的恢复证据，然后用 R4 0.3.0
不可变制品执行 R5-D。它没有 push/PR/schedule 触发器、仓库 Secret、SSH、生产域名、镜像推送或香港
服务器地址。成功必须同时验证 `current`、`previous`、committed journal、活动 blue unit、停止的旧 unit
以及两个账号标志均为 `0`。

## 生产执行门禁

生产运行前必须重新完成并人工复核：

1. 从匹配 `main` 成功构建下载目标 Gateway 与运维两个 bundle；两者 archive、manifest、commit 与
   image ID/入口完全一致；
2. R5-B 加密恢复证据仍在 30 天内，旧 identity 文件没有变化；
3. 候选 Nginx 完整配置的 diff 和 SHA-256，确认没有删除发布服务或其他既有路由；
4. blue/green 端口空闲、旧 8444 与 PostgreSQL 5432 仍只监听 loopback；
5. schema v2 运维 bundle 的内置 loopback smoke runtime 与 Connector 入口验证通过，维护窗内允许短暂
   停止旧 Gateway 与重载 Nginx；不得向香港主机复制 Mac Hermes 凭据；
6. 明确的失败判定：任何私有/公开 smoke、状态交接、`nginx -t`、reload 或观察失败都立即恢复旧服务；
7. 项目所有者明确授权本次 R5-D 生产接管后，才运行：

```bash
node scripts/production-baseline.mjs \
  --config /secure-input/hermes-go/production-managed-baseline.json \
  --confirm production:<configured-hostname>
```

失败入口统一向操作者返回 `HR-OPS-014`，内部 R4 阶段原因会经过凭据和用户路径脱敏后保留用于诊断。
代码阶段没有连接香港服务器，没有部署、重启、切流或启用 timer。

## 2026-09-04 部署前只读预检

经单独授权的白名单读取确认生产主机仍为 Linux x86_64，根盘约 100 GiB 且使用率 16%，可用内存约
5.4 GiB；旧 Gateway、Nginx、Docker 与 PostgreSQL 均 active/enabled，Nginx 配置检查通过，公开 Gateway
与发布服务健康。8444 和 PostgreSQL 5432 只监听 loopback，旧 identity 与当天 R5-B 异机恢复证据完全
匹配，三个新受管 root、两个候选 unit/container 和 upstream 文件均不存在，符合首次接管条件。

预检同时发现并保持 fail-closed：默认绿色端口 8788 已被既有业务容器占用，私密生产配置必须改用当前
空闲的 18787/18788 并在停服前复核；实际 Nginx 文件名需要上述精确兼容；生产主机没有与目标 `main`
绑定的运维 bundle；现有共享 Token 文件为组可读且内部状态 Token 尚不存在，不能直接作为 R5-D 输入。
后续需在获得写入授权后，将现有 App/Connector Token 复制到独立 `0600` 输入文件并生成独立内部状态
Token，不改动原文件。此次预检未上传文件、创建目录、安装软件、重启服务、重载 Nginx 或切换流量。
