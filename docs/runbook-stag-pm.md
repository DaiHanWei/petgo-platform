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

## §A Dai 一次性安装（层 1）

1. PM 在自己电脑生成专用 key（不要复用任何已有 key）：
   ```
   ssh-keygen -t ed25519 -f ~/.ssh/tailtopia_stag -N "" -C stag-pm
   ```
   把 `~/.ssh/tailtopia_stag.pub` 的内容发给 Dai。

2. Dai 安装服务器侧包装脚本：
   ```
   ssh dai@62.146.239.156 'mkdir -p ~/bin'
   scp scripts/stag-ops-server.sh dai@62.146.239.156:~/bin/stag-ops.sh
   ssh dai@62.146.239.156 'chmod +x ~/bin/stag-ops.sh'
   ```

3. Dai 在服务器 `~/.ssh/authorized_keys` **追加一行**（`<PM公钥>` 替换为第 1 步内容，整行一条）：
   ```
   command="/home/dai/bin/stag-ops.sh",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty <PM公钥> stag-pm
   ```

4. PM 电脑 `~/.ssh/config` 追加（之后 ssh 自动选这把 key）：
   ```
   Host 62.146.239.156
     User dai
     IdentityFile ~/.ssh/tailtopia_stag
     IdentitiesOnly yes
   ```

5. 验证受限性（PM 电脑执行）：
   ```
   ssh dai@62.146.239.156 health     # 应返回 {"status":"UP"...}
   ssh dai@62.146.239.156 hostname   # 应返回 DENIED（任意命令均被拒）
   ```

> PM 电脑另需：git、JDK 21、Maven（或直接用仓库 `petgo-backend/mvnw`）、Claude Code。

## §B PM 日常操作

克隆仓库后**切到 stag 分支**，对 Claude 说：

> 部署 staging 并确认健康：跑 `./scripts/deploy-backend-stag-pm.sh`，最后贴出 health 结果。

验证入口：
- 后端健康：`https://api-stag.tailtopia.id/actuator/health`
- App 侧：debug 包默认连 staging，装包后直接测功能。
- 看日志：让 Claude 跑 `ssh dai@62.146.239.156 logs 200`。

受限通道全部可用动作：`put-build`（上传构建包）/ `deploy` / `logs [N]` / `health` / `ps` / `restart`。

## §C 边界（出问题时对照）

- PM 的 key **做不到**：登 shell、跑任意命令、scp、端口转发、碰 `petgo-server`（生产容器）/`petgo`（生产库）/redis DB2/`~/.env.petgo`。
- Claude 被 `stag-guard` 拦截属正常，让它改用 staging 等价物；反复被拦 → 找 Dai。
- 部署失败先 `logs 200` 看原因；回滚 = 让 Dai 用 `petgo-server:stag-previous` 镜像重启。
- ⚠️ 本 runbook 与层 2/3 文件只存在于 **stag 分支**，勿合入 v1.1-dev / main。
