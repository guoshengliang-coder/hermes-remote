# Hermes Remote 环境信息汇总

> 收集日期：2026-08-29
> 方式：SSH / 本机命令实测（非转述、非估算）
> 标记约定：`【事实】` = 有命令输出可核对；`【推断】` = 基于事实的合理推理；`【判断】` = 我的观点，可能错。

---

## 一、本机（Mac mini，Hermes 服务端）

### 1. 硬件与系统

| 项目 | 值 | 来源 |
|---|---|---|
| Mac 型号 | Apple M2（Mac14,3） | 【事实】`sysctl hw.model` |
| 架构 | arm64（Apple Silicon） | 【事实】`uname -m` |
| macOS | 14.8.9（Sonoma，Build 23J631） | 【事实】`sw_vers` |

### 2. Hermes 安装

| 项目 | 值 | 来源 |
|---|---|---|
| 安装方式 | git 安装 | 【事实】`hermes --version` |
| 安装目录 | `/Users/bs/.hermes/hermes-agent` | 【事实】 |
| 版本 | v0.20.6（2026-08-27），upstream `299c652a` + 本地 `420e156b`（+1 carried commit） | 【事实】 |
| 可执行文件 | `/Users/bs/.hermes/hermes-agent/venv/bin/hermes` | 【事实】 |

### 3. 运行中的常驻进程

| 进程 | 命令 | 说明 |
|---|---|---|
| gateway | `python -m hermes_cli.main gateway run --replace`（PID 2693，launchd 管理） | 常驻网关，绑定 loopback |
| Desktop.app | Electron 桌面应用 | 本机 GUI |
| backend ×2 | `hermes serve --isolated --host 127.0.0.1 --port 0 --ssh-session-token-file …` | MacBook 经 SSH 隧道过来的会话后端，端口动态 |
| dashboard | `hermes dashboard --host 100.119.73.80 --port 9119 --skip-build --no-open` | Web 面板，绑定 Tailscale 网卡 |

### 4. 当前访问地址与端口

| 服务 | 地址 | 说明 |
|---|---|---|
| Web Dashboard | `http://100.119.73.80:9119` | 仅绑定 Tailscale IP，【事实】未暴露公网 |
| Desktop 后端 | `127.0.0.1` 动态端口 + SSH 隧道 | 由 Desktop 自动建隧道 |
| gateway | loopback | 不对外 |

### 5. 认证

- Web Dashboard：已配置 **Basic Auth**（`~/.hermes/.env` 中 `HERMES_DASHBOARD_BASIC_AUTH_USERNAME` / `HERMES_DASHBOARD_BASIC_AUTH_PASSWORD`）。凭据值不在此列出。
- Desktop / backend：SSH 隧道 + session token 鉴权（`--ssh-session-token-file`）。

### 6. 协议 / 流式

- **支持 WebSocket + 流式响应**。【事实】协议为 Hermes 私有（非 OpenAI 兼容）：
  `POST /auth/password-login`（cookie 会话）→ `POST /api/auth/ws-ticket`（30s 有效 ticket）→ `ws://…/api/ws` 事件流。
- 第三方客户端需实现上述私有协议，或经 relay 做协议/传输转发。

### 7. 网络（Tailscale）

| 项目 | 值 |
|---|---|
| 主机名 | `LGS-MACMINI.local` |
| Tailscale IP | `100.119.73.80` |
| tailnet | `tail7ccfec.ts.net` |
| SSH 用户 | `bs` |

### 8. 安全隐患（待处理）

- 【事实】`Python *:8767` 用 `http.server` 把 `~/.hermes/plans` 目录绑在 **0.0.0.0** 上，公网可达。建议改为绑定 `127.0.0.1` 或停用。

---

## 二、香港服务器（relay 宿主）

### 1. 基本信息

| 项目 | 值 | 来源 |
|---|---|---|
| 云服务商 | 阿里云（香港区域） | 【推断】公网 IP 属阿里云 IP 段 + 既往部署记录（阿里云 metadata 探测未返回有效 vendor 字段，无法机上强证） |
| 系统 | Ubuntu 26.04 LTS | 【事实】`/etc/os-release` |
| CPU | x86_64，4 核 | 【事实】`uname -m` / `nproc` |
| 内存 | 7.1Gi 总，5.7Gi 可用 | 【事实】`free -h` |
| 公网 IP | `47.239.30.x`（末段隐藏） | 【事实】`ifconfig.me` |
| SSH 用户 | `kkk`（sudo 免密） | 【事实】 |
| Docker | 无（未安装） | 【事实】 |
| Caddy / Nginx | 无（均未安装） | 【事实】 |

### 2. 已开放端口（机内监听）

| 端口 | 进程 | 用途 |
|---|---|---|
| 22/tcp | sshd | SSH |
| 80/tcp | derper | HTTP（derper） |
| 443/tcp | xray | VLESS Reality（翻墙） |
| 8443/tcp | derper | DERP 中继（TLS） |
| 3389/tcp | xrdp | 远程桌面（⚠ 公网暴露风险） |
| 3478/udp | derper | STUN【推断：配置带 `--stun`，TCP 视角不可见】 |

### 3. 防火墙

- 【事实】UFW `inactive`，当前仅靠阿里云安全组。建议启用 UFW 并仅放行必要端口。

### 4. 域名 / 证书

- 域名：`47.239.30.253.sslip.io`
- 【事实】certbot 证书有效至 **2026-11-19**（剩约 81 天，截至采集日）。

### 5. 已部署服务

- **derper**（自建 Tailscale DERP 中继）：监听 8443，`--certmode=manual --certdir=/etc/derper/certs`，`--stun --verify-clients=false`。
- **Xray Reality**：VLESS 监听 443，SNI=`www.microsoft.com`。
- tailnet：`tail7ccfec.ts.net`，DERP Region 900 `myhk`。

---

## 三、对本项目（Hermes Remote）的直接影响

| 结论 | 依据 | 类型 |
|---|---|---|
| relay 部署方式：**systemd + 编译二进制**（非 Docker） | 服务器无 Docker/Caddy/Nginx，现有 derper/xray 均为 systemd 模式 | 【判断】 |
| relay 端口：**8444/TCP** | 443=xray、8443=derper、80=derper 已占 | 【判断】 |
| TLS 证书：复用现有 `47.239.30.253.sslip.io` 证书 | 同 IP 域名可共用一张证书 | 【判断】 |
| 服务器资源余量充足 | 内存仅用 1.4Gi/7.1Gi，4 核基本空闲 | 【事实】 |

### 安全待办清单

- [ ] `~/.hermes/plans` 8767 端口改为仅本机（或停用）
- [ ] 香港服务器 3389（xrdp）限制来源 IP 或关闭
- [ ] 香港服务器启用 UFW（先放行 22 再操作，避免锁死）
