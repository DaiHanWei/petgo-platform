# Runbook：PM 自助部署 staging（受限通道）

> 目标：让不懂技术的同事在 **stag 分支**用 Claude 自助部署并验证功能，
> 三层护栏保证碰不到同机的生产服务。2026-08-05 建立。

## 三层护栏一览

| 层 | 位置 | 强度 | 说明 |
|---|---|---|---|
| 1 | 服务器 `authorized_keys` 强制命令 → `~/bin/stag-ops.sh` | **硬**（AI 不可绕） | PM 的 key 只能触发 6 个白名单动作 |
| 2 | `.claude/hooks/stag-guard.py`（PreToolUse） | 硬（Claude Code 框架强制） | 命中生产资源的 Bash 命令直接拒绝 |
| 3 | `CLAUDE.md` staging 部署纪律小节 | 提示层 | 让 Claude 开局就知道边界 |

层 2/3 已提交在 stag 分支，clone 即生效。层 1 需 Dai 一次性安装（§A）。

## §A 一次性安装（层 1，服务器专用账号 hex）

服务器侧（Dai 执行，已于 2026-08-05 完成）：

1. 建独立系统账号 `hex`：`/bin/bash` shell、加 `docker` 组、**无 sudo、密码锁定**（只能凭 key 进，且 key 被强制命令绑死）。
2. `/home/hex/bin/stag-ops.sh` ← 本仓库 `scripts/stag-ops-server.sh`（755, hex:hex）。
3. `/home/hex/.ssh/authorized_keys` **每个被授权同事一行**（公钥 + 强制命令，末尾注释 `stag-<名字>` 用于 auth.log 区分）：
   ```
   command="/home/hex/bin/stag-ops.sh",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty ssh-ed25519 <公钥> stag-<名字>
   ```
   已授权：`stag-pm`（Hex，2026-08-05）、`stag-shawn`（Shawn，2026-08-18）。
   新增同事只需追加一行；吊销 = 删掉对应那行。账号本身的限制（无 shell/无 sudo/仅白名单动作）对每把 key 同等生效。
4. `/home/hex/.env.petgo-stag` ← 从 `/home/dai/.env.petgo-stag` 复制（600, hex:hex）。
   ⚠️ Dai 侧改 staging env 后要**同步重拷**这份副本，否则 hex 部署用的是旧配置。

PM 侧：

1. 生成专用 key（不要复用任何已有 key）：
   ```
   ssh-keygen -t ed25519 -f ~/.ssh/tailtopia_stag -N "" -C stag-pm
   ```
   把 `~/.ssh/tailtopia_stag.pub` 的内容发给 Dai（服务器侧第 3 步用）。

2. `~/.ssh/config` 追加：
   ```
   Host 62.146.239.156
     User hex
     IdentityFile ~/.ssh/tailtopia_stag
     IdentitiesOnly yes
   ```

3. 验证受限性：
   ```
   ssh hex@62.146.239.156 health     # 应返回 {"status":"UP"...}
   ssh hex@62.146.239.156 hostname   # 应返回 DENIED（任意命令均被拒）
   ```

> PM 电脑另需：git、JDK 21、Maven（或直接用仓库 `petgo-backend/mvnw`）、Claude Code。

## §B PM 日常操作

克隆仓库后**切到 stag 分支**，对 Claude 说：

> 部署 staging 并确认健康：跑 `./scripts/deploy-backend-stag-pm.sh`，最后贴出 health 结果。

验证入口：
- 后端健康：`https://api-stag.tailtopia.id/actuator/health`
- App 侧：debug 包默认连 staging，装包后直接测功能。
- 看日志：让 Claude 跑 `ssh hex@62.146.239.156 logs 200`。

受限通道全部可用动作：`put-build`（上传构建包）/ `deploy` / `logs [N]` / `health` / `ps` / `restart`。

## §C 边界（出问题时对照）

- PM 的 `hex` 账号**做不到**：登 shell、跑任意命令、scp、端口转发、sudo、读 dai 家目录（含生产 env）、碰 `petgo-server`（生产容器）/`petgo`（生产库）/redis DB2。
- Claude 被 `stag-guard` 拦截属正常，让它改用 staging 等价物；反复被拦 → 找 Dai。
- 部署失败先 `logs 200` 看原因；回滚 = 让 Dai 用 `petgo-server:stag-previous` 镜像重启。
- ⚠️ 本 runbook 与层 2/3 文件只存在于 **stag 分支**，勿合入 v1.1-dev / main。
