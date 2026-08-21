#!/usr/bin/env python3
"""stag 分支专属 PreToolUse hook：拦截可能触碰生产环境的 Bash 命令。

背景：生产(petgo-server/8084/petgo库/redis DB2)与 staging(petgo-server-stag/8085/
petgo_stag/redis DB3)同在 62.146.239.156。本 hook 供非技术同事用 Claude 部署验证
staging 时兜底，命中规则直接拒绝执行（exit 2），理由回显给模型自行改道。

⚠️ 只存在于 stag 分支，勿合回 v1.1-dev / main。
"""
import json
import re
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


def main() -> int:
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
