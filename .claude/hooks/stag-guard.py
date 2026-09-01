#!/usr/bin/env python3
"""stag 分支专属 PreToolUse hook：拦截可能触碰生产环境的 Bash 命令。

背景：生产(petgo-server/8084/petgo库/redis DB2)与 staging(petgo-server-stag/8085/
petgo_stag/redis DB3)同在 62.146.239.156。本 hook 供非技术同事用 Claude 部署验证
staging 时兜底，命中规则直接拒绝执行（exit 2），理由回显给模型自行改道。

⚠️ 只存在于 stag 分支，勿合回 v1.1-dev / main。
   —— 但「勿合回」全靠人守，实际已经随 stag→dev 的合并流到了 dev/dev_1.1.6，
   而 settings.json 里的注册也一并跟了过来。所以规则判定前先做一次分支自检
   （见 _on_stag_branch），让代码自己兜住这条契约：在别的分支上注册了也不动作，
   不把 staging 的运维约束强加给在那边工作的人。stag 上的拦截强度不变。
"""
import json
import os
import re
import subprocess
import sys


RULES = [
    (r"deploy-backend\.sh",
     "deploy-backend.sh 是生产部署脚本，禁止执行。staging 部署只能用 scripts/deploy-backend-stag.sh 或 scripts/deploy-backend-stag-pm.sh"),
    (r"petgo-server(?!-stag|:stag)",
     "命令疑似触碰生产容器/镜像 petgo-server。只允许 petgo-server-stag 容器与 petgo-server:stag* 镜像"),
    (r"\.env\.petgo(?!-stag)",
     "~/.env.petgo 是生产环境变量文件，禁止读取或引用。staging 用 ~/.env.petgo-stag"),
    (r"(?<!\d)8084(?!\d)",
     "8084 是生产端口，禁止访问。staging 走 127.0.0.1:8085 或 https://api-stag.tailtopia.id"),
    (r"-d\s*=?\s*petgo(?![\w-])",
     "psql 目标库疑似生产库 petgo。staging 库是 petgo_stag（-d petgo_stag）"),
    (r"\bpg_dump\b",
     "pg_dump 涉及生产库导出，本会话禁止（需要克隆 staging 库请找 Dai）"),
    (r"\bflush(all|db)\b",
     "Redis FLUSH 命令禁止（生产与 staging 共用同一 Redis 实例）"),
    (r"redis-cli(?!.*-n\s*3\b)",
     "redis-cli 必须显式带 -n 3（staging 逻辑库）。生产在 DB2，禁止触碰"),
    (r"docker\s+(stop|rm|kill|restart|update)\s+[^|;&]*\b(petgo-postgres|redis)\b",
     "petgo-postgres / redis 是生产共用基础容器，禁止停/删/重启"),
    (r"docker\s+(system|volume|network|image)\s+(prune|rm)\b",
     "docker 清理与删除卷/网络/镜像的操作禁止（可能波及生产）"),
    (r"\bALLOW_BRANCH\b",
     "ALLOW_BRANCH 逃生门禁止使用：staging 必须从 stag 分支部署"),
    (r"(?i)drop\s+database\s+(?!(?:if\s+exists\s+)?petgo_stag\b)",
     "DROP DATABASE 仅允许 petgo_stag"),
    (r"\b(reboot|shutdown|poweroff)\b|\bsystemctl\b|\bcloudflared\b",
     "服务器系统级操作禁止"),
    (r"git\s+push\s+[^|;&]*\b(main|master)\b",
     "禁止推送 main 分支"),
    (r"git\s+push[^|;&]*(\s--force\b|\s--force-with-lease\b|\s-f\b)",
     "禁止 force push"),
]


def _on_stag_branch() -> bool:
    """当前是否在 stag 线上。

    🔴 判不出来时返回 True（**继续拦**）：detached HEAD、git 不可用、超时——这些情况下
    宁可误伤一条命令，也不能静默放行生产操作。本 hook 的失败方向只有一个是安全的。
    """
    try:
        r = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=os.environ.get("CLAUDE_PROJECT_DIR") or None,
            capture_output=True, text=True, timeout=5,
        )
    except Exception:
        return True
    if r.returncode != 0:
        return True
    return r.stdout.strip().startswith("stag")


def main() -> int:
    # 分支自检放在最前面：不在 stag 线上就直接放行，连 stdin 都不必读。
    if not _on_stag_branch():
        return 0
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # 输入异常不拦，避免误伤
    if payload.get("tool_name") != "Bash":
        return 0
    command = (payload.get("tool_input") or {}).get("command") or ""
    for pattern, reason in RULES:
        if re.search(pattern, command):
            print(
                f"[stag-guard] 已拦截：{reason}。"
                "本会话只允许操作 staging 资源（petgo-server-stag/8085/petgo_stag/redis DB3），"
                "生产资源一律禁止。请改用 staging 等价物或联系 Dai。",
                file=sys.stderr,
            )
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
