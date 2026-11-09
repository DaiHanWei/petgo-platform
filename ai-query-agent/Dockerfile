# ai-query-agent 镜像：Node 24 任务服务 + 固定版本 Claude Code + psql 客户端
# 构建：在服务器上用 deploy key 拉取的仓库目录内执行（scripts/deploy-ai-query-agent-stag.sh），不从本机上传
FROM node:24-slim

# 与本地核实过参数的版本一致；升级时先在 stag 验证 -p / --resume / --append-system-prompt / 输出字段
ARG CLAUDE_CODE_VERSION=2.1.319

RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client ripgrep ca-certificates tini \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force \
 && useradd --create-home --uid 10002 --shell /usr/sbin/nologin claude \
 && chmod 700 /home/claude \
 && mkdir -p /data && chmod 700 /data

WORKDIR /app
# 代码与说明书归 root 所有，agent 用户只读，claude 无法改写说明书
COPY package.json ./
COPY src ./src
COPY work ./work

# 进程模型（Story 1.2 复审）：
#   - agent（node）以容器内 root 运行，部署时 --cap-drop ALL 后只加回 SETUID / SETGID / KILL，
#     用于以独立 uid 启动 claude 并在超时时结束其进程组；/data 归 root 且 700
#   - claude 及其 Bash 以 uid 10002 运行：读不到 agent 的 /proc/<pid>/environ（AGENT_SECRET）
#     和 /data 下其他任务的结果
ENV NODE_ENV=production \
    CLAUDE_UID=10002 \
    CLAUDE_GID=10002 \
    CLAUDE_HOME=/home/claude \
    DATA_DIR=/data \
    WORK_DIR=/app/work \
    PORT=8790 \
    TZ=Asia/Jakarta

EXPOSE 8790
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "src/main.mjs"]
