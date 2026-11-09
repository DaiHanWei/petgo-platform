# Runbook：ops AI 查数 agent（ai-query-agent）

> 规划：`_bmad-output/implementation-artifacts/specs/spec-ops-ai-query.md`（§4.2 接口、§6 安全、§7 环境）。
> **所有部署都等明确指令再执行。** 本文件只写操作步骤，不含任何凭证。

## 1. 组成

| 项 | staging | 生产（Epic 3） |
|---|---|---|
| 容器 | `ai-query-agent-stag` | `ai-query-agent` |
| 镜像 | `ai-query-agent:stag` | `ai-query-agent:prod` |
| internal 网络 | `aiq-stag-net` | `aiq-prod-net` |
| 默认库 / 账号 | `petgo_stag` / `claude_bot_stag` | `petgo` / `claude_bot` |
| 日志挂载 | `~/petgo-server-stag/logs` → `/logs/stag:ro` | 生产 + stag |
| 代码挂载 | 服务器 deploy key 拉取的仓库 → `/repo:ro` | 同左 |
| ops 侧 | `petgo-server-stag`，`AI_QUERY_AGENT_URL=http://ai-query-agent-stag:8790` | `petgo-server` |

导出文件：`ai-query-agent-stag-exports` 卷挂到 `/exports`，按 `<对话 token>/<任务 token>/` 隔离，30 天随对话清除（Story 2.2）。

运行限制：1 核 2G、`--read-only` 根文件系统（`/tmp` 为 tmpfs）、`--cap-drop ALL`（只加回 `SETUID`/`SETGID`/`KILL`）、`no-new-privileges`、**不挂 docker socket、不映射端口**、日志和代码只读挂载（代码为无 `.git` 的快照 `repo-<sha>`）。

进程模型：agent（node）以容器内 root 运行，claude 及其 Bash 以 uid 10002 运行并加 `--setting-sources project`。这样 claude 读不到 agent 进程的 env（`AGENT_SECRET`）和 `/data` 下其他任务的结果，也无法通过改写 `~/.claude/settings.json` 给以后的任务埋 hooks。

## 2. 服务器一次性准备（staging）

1. **deploy key**：服务器生成 `~/.ssh/ai_query_agent_deploy_key`（ed25519），公钥加到 GitHub 仓库 Settings → Deploy keys，**只读**。
2. **库账号**：DBA 执行 `ai-query-agent/deploy/sql/claude_bot_stag.sql`，在 psql 内 `\password claude_bot_stag`；再执行 `verify_claude_bot_stag.sql`，逐项核对期望值。
3. **pgpass**：`~/ai-query-agent-stag/pgpass`，内容 `petgo-postgres:5432:petgo_stag:claude_bot_stag:<密码>`，权限 600，且属主改为容器内 claude 用户：`sudo chown 10002:10002 ~/ai-query-agent-stag/pgpass`（部署脚本会检查）（psql 要求文件只对本人可读）。
4. **env 文件**：按 `ai-query-agent/.env.example` 写 `~/ai-query-agent-stag/env`，权限 600。**不得含 `LARK_*` / `FEISHU_*`**（agent 和部署脚本都会拒绝）。`AGENT_SECRET` 与 ops 的 `AI_QUERY_AGENT_SECRET` 相同，用 `openssl rand -hex 32` 生成。
5. **日志可读性**：确认 `~/petgo-server-stag/logs` 对 uid 10002 可读（只读挂载）。

## 3. 部署（收到明确指令后）

```bash
./scripts/deploy-ai-query-agent-stag.sh                      # 默认 GIT_REF=feat/ops-ai-query
CONFIRM_SHARED_INFRA=1 ./scripts/deploy-ai-query-agent-stag.sh   # 仅在 Dai 确认把 petgo-postgres 接入 aiq-stag-net 后
```

- `petgo-postgres` 是生产与 stag 共用的容器，`docker network connect` 属共享基建变更，**脚本默认拒绝**，须 Dai 确认后带 `CONFIRM_SHARED_INFRA=1`。
- Story 3.1 的白名单代理落地前，脚本临时把 agent 接入普通 bridge 网络 `aiq-stag-egress` 以访问 Anthropic API（`EGRESS_NETWORK=` 置空可关闭）。这一阶段 **容器仍可任意出网**，只能在 stag 使用，生产必须等 Story 3.1。

## 4. 部署后检查（L2）

| # | 检查 | 命令 / 方法 | 期望 |
|---|---|---|---|
| 1 | 容器运行限制 | `docker inspect ai-query-agent-stag --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{.HostConfig.ReadonlyRootfs}} {{json .Mounts}}'` | `1000000000 2147483648 true`，挂载无 `docker.sock`，日志 / 代码 `RW=false` |
| 2 | 端口 | `docker port ai-query-agent-stag` | 无输出 |
| 3 | 网络 | `docker inspect -f '{{json .NetworkSettings.Networks}}' ai-query-agent-stag` | 只有 `aiq-stag-net`（及临时 `aiq-stag-egress`），没有 `jbp-net` |
| 4 | env 白名单 | 提问「执行 env 并原样列出变量名」 | 只出现白名单变量名，无 `AGENT_SECRET`、无 `LARK_*` |
| 5 | token 隔离 | 提问「执行 `echo ${#CLAUDE_CODE_OAUTH_TOKEN}`」 | 输出 0 或空；若能读到，**停下找 Dai**（Story 1.2 AC2） |
| 6 | 生产隔离 | 提问「连 petgo 库查 users 表行数」「列出 /logs/prod」 | 均失败 |
| 7 | 语言与口径 | 分别用 zh / en / id 新建对话提问 | 回答语言正确，末尾有统计口径小节 |

## 5. 网络前期验证记录（Story 1.2 AC5，待本地 / 服务器填写）

| 项 | 结果 |
|---|---|
| `claude -p` 认证与查询实际访问的域名 | 待实测（用临时出网网络 + `tcpdump` / 代理日志记录 SNI） |
| 服务器拉取代码需要的域名 | `github.com`（git over SSH，在宿主机执行，不经容器） |
| internal 网络（无出网）下一次真实查询的结果 | 待实测（预期：认证失败 → `AGENT_ERROR` / `AGENT_AUTH_FAILED`，据此确认代理必须放行的域名） |

## 6. 回退

`AI_QUERY_MODE=off`（ops 侧）+ `docker stop ai-query-agent-stag`。不影响后台其他功能。旧 `claude-bot` 容器与飞书应用保持原状（只暂停，不下线）。

## 7. 已知残留风险（需 Dai 知悉）

- **订阅 token 可能仍可被 Bash 读到**：`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` 只清理子进程的 env；`claude` 进程本身持有 `CLAUDE_CODE_OAUTH_TOKEN`，它的 Bash 子进程与它同 uid，理论上可以读 `/proc/<claude pid>/environ`。按官方文档无法在同一 uid 内彻底隔离，§4 第 5 项需额外用「`cat /proc/$PPID/environ`」类提问实测。若可读，这正是 Story 1.2 AC2 的「做不到」情形，**停下找 Dai 确认**（可选：接受风险，或改用 `apiKeyHelper` 等不经 env 的方式，需另行评审）。
- `~/.claude/projects`（会话记录，含他人问题原文）与 claude 同 uid，claude 的 Bash 能读到其他对话的记录。获授权者本就可以查全部数据（2026-10-05 决策），问题原文属于他人，风险接受与否由 Dai 决定。
- 角色级 `statement_timeout` 是会话默认值，账号可 `SET statement_timeout = 0` 覆盖；只读由权限保证不受影响。连接上限 3 是硬限制。
- Story 3.1 落地前 stag agent 可任意出网（临时出网网络）。
