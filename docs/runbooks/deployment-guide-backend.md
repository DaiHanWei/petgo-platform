# TailTopia 后端部署指南（权威文档）

**部署目标**：`62.146.239.156`（与 Logistic 项目**共用同一台 Docker 主机**，Ubuntu + Docker + Cloudflare Tunnel）
**部署模式**：本地 `mvn package` build jar → tar+scp 上传 → 服务器 `docker build`（仅 COPY jar）+ 重启容器
**核心命令**：`./scripts/deploy-backend.sh`

> 本文档只覆盖 `petgo-backend`。前端是 Flutter 移动端（APK 走 CI），不部署到此服务器。
> Logistic 项目的部署见 `/Users/dai/work/pomogo/Logistic/docs/deployment-guide.md`，两者共用 `jbp-net` 网络与 redis 容器，**但各自独立的应用容器 + 独立数据库**（petgo=PostgreSQL，logistic=MySQL）。

---

## 0. TL;DR — 日常部署

```bash
cd /Users/dai/work/petgo-platform
./scripts/deploy-backend.sh          # 改了 petgo-backend 后
```

跑完检查：
```bash
ssh dai@62.146.239.156 'curl -s http://127.0.0.1:8084/actuator/health'
# 期望: {"status":"UP",...}
```

第一次部署 / 换机器 / 清盘 → 先做 **§2 一次性初始化**。

---

## 1. 架构现状（与 Logistic 共生）

```
                互联网 (petgo 域名，待定 → Cloudflare 控制台配)
                          │
                          ▼  Cloudflare Tunnel (systemd: cloudflared)
                          │
                          ▼  127.0.0.1:8084 (仅本机)
        ┌─────────────────────────────────────────────┐
        │              docker network: jbp-net          │
        │                                               │
        │  petgo-server  ──┬──> petgo-postgres (新)     │
        │  (Java 21)       │     127.0.0.1:5432         │
        │  :8084→8080      │     vol: petgo_pgdata      │
        │                  └──> redis (共享, DB 2)      │
        │                        127.0.0.1:6379         │
        │                                               │
        │  ── 同主机其他项目（勿动）──                    │
        │  logistic-server :8081   logistic-web :8083   │
        │  logistic-app :8082      jbp-kf :8080         │
        │  mysql (logistic 专用)   redis (共享)          │
        └─────────────────────────────────────────────┘
```

**关键点**：
- 服务器**没装 mvn / java build 工具** — jar 在本地（你的 Mac）构建。
- petgo 用 **PostgreSQL**（独立容器 `petgo-postgres`），与 logistic 的 MySQL 互不相干。
- petgo 与 logistic/jbp-kf **共用同一个 redis 容器**，靠**逻辑 DB 序号隔离**：petgo 用 `REDIS_DB=2`（logistic/jbp 默认 0）。⚠️ 共用 redis 意味着别在该 redis 上跑全局 `FLUSHALL`。
- 应用容器换 jar 必须 `docker build` 重做镜像（脚本自动）；DB/redis 容器一次性建好后不动。
- 外网入口是 **Cloudflare Tunnel**（不是 nginx 反代），域名→端口映射在 Cloudflare 控制台。
- 端口分配：`8080` jbp-kf · `8081` logistic-server · `8082` logistic-app · `8083` logistic-web · **`8084` petgo-server**。

---

## 2. 一次性服务器初始化

> 若 `petgo-postgres` 容器、`~/.env.petgo` 都已存在，**跳过本节**，直接用 §0 部署。

### 2.1 确认共享基建已就位

```bash
ssh dai@62.146.239.156
docker network inspect jbp-net >/dev/null 2>&1 && echo "jbp-net OK" || docker network create jbp-net
docker ps --format '{{.Names}}' | grep -qx redis && echo "redis OK" || echo "缺 redis（见 Logistic runbook §2.3 起一个 redis:7-alpine 到 jbp-net）"
```

### 2.2 准备 `~/.env.petgo`（关键！凭证全部在此，绝不入库）

真实凭证从你本地 `petgo-backend/.env` 拷过来（OSS / Gemini live key 等）。`JWT_SECRET` 生产必须强随机。

```bash
cat > ~/.env.petgo << 'EOF'
# === Spring profile ===
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

# === PostgreSQL（容器名即 DNS）===
DB_HOST=petgo-postgres
DB_PORT=5432
DB_NAME=petgo
DB_USER=petgo
DB_PASSWORD=<改成强密码，要与 2.3 建库时一致>

# === Redis（共享容器，用逻辑 DB 2 隔离 keyspace）===
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DB=2

# === JWT（生产必填强随机，≥32 字节）===
JWT_SECRET=<openssl rand -base64 48>
JWT_ISSUER=tailtopia

# === Google 登录（需真实登录才填，来自本地 .env）===
GOOGLE_OAUTH_CLIENT_ID=

# === AI 问诊 Gemini（live 才填，来自本地 .env）===
GEMINI_MODE=stub
GEMINI_API_KEY=

# === 阿里云 OSS 媒体（启用上传/图片才填，来自本地 .env）===
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
OSS_ENDPOINT=https://oss-ap-southeast-5.aliyuncs.com
OSS_REGION=ap-southeast-5
OSS_PUBLIC_BUCKET=
OSS_PRIVATE_BUCKET=
OSS_CDN_BASE_URL=

# === 腾讯 IM 实时会话（live 才填）===
IM_MODE=stub
TENCENT_IM_SDK_APP_ID=
TENCENT_IM_SECRET_KEY=
TENCENT_IM_CALLBACK_TOKEN=
EOF
chmod 600 ~/.env.petgo
```

> 最小可启动只需 DB / Redis / JWT_SECRET；OSS/Gemini/Google/IM 留 stub/空也能跑起来（对应功能降级）。

### 2.3 起 PostgreSQL 容器（petgo 专用）

```bash
ssh dai@62.146.239.156
set -a; source ~/.env.petgo; set +a

docker run -d \
  --name petgo-postgres \
  --network jbp-net \
  -p 127.0.0.1:5432:5432 \
  -v petgo_pgdata:/var/lib/postgresql/data \
  -e POSTGRES_DB="$DB_NAME" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  --restart unless-stopped \
  postgres:16-alpine

# 等就绪
sleep 5 && docker exec petgo-postgres pg_isready -U "$DB_USER" -d "$DB_NAME"
```

### 2.4 首次部署应用

```bash
# 回到本地 Mac
cd /Users/dai/work/petgo-platform
./scripts/deploy-backend.sh
```

脚本会：本地 `mvn package -DskipTests` → scp `Dockerfile.deploy + jar` → 服务器 `docker build` → `docker run`（`--env-file ~/.env.petgo`，挂 jbp-net，端口 127.0.0.1:8084）→ 等 `/actuator/health` UP（含 Flyway 迁移全量执行）。

### 2.5 Cloudflare Tunnel 暴露公网

`cloudflared` 已作为 systemd 服务在跑（token 在 systemd unit）。在 **Cloudflare 控制台**加路由：

> Zero Trust → Networks → Tunnels → 选现有 tunnel → Public Hostname → Add
> - Subdomain/Domain：填 petgo 的域名（自定）
> - Service：`HTTP` → `127.0.0.1:8084`

保存后公网即可访问 `https://<你的域名>/actuator/health`。

---

## 3. 日常部署

```bash
cd /Users/dai/work/petgo-platform
./scripts/deploy-backend.sh
```

可选：
```bash
SKIP_BUILD=1 ./scripts/deploy-backend.sh   # 已 build 过，直接用现有 target/*.jar
SKIP_TESTS=0 ./scripts/deploy-backend.sh   # build 时跑测试（默认跳过）
DEPLOY_HOST=dai@别的IP ./scripts/deploy-backend.sh
```

---

## 4. 回滚

### 4.1 应用回滚（最常见）

```bash
ssh dai@62.146.239.156 bash -se << 'EOF'
docker stop petgo-server && docker rm petgo-server
docker run -d --name petgo-server --network jbp-net \
  -p 127.0.0.1:8084:8080 --restart unless-stopped \
  --env-file ~/.env.petgo petgo-server:previous
sleep 10 && curl -fs http://127.0.0.1:8084/actuator/health
EOF
```

回滚到具体时间戳镜像：`ssh dai@62.146.239.156 docker images petgo-server` 看可用 tag，把 `previous` 换成 `<timestamp>`。

### 4.2 数据库回滚

Flyway 不支持自动 down migration。改大 schema 前先备份：

```bash
ssh dai@62.146.239.156 \
  'docker exec petgo-postgres pg_dump -U petgo petgo' \
  > petgo-backup-$(date +%Y%m%d-%H%M%S).sql
```

---

## 5. 故障排查

| 症状 | 检查 | 解决 |
|---|---|---|
| 卡在「等待 actuator/health」 | `ssh dai@62.146.239.156 docker logs petgo-server` | 看 Flyway 迁移是否成功 / DB 连不上 / ddl validate 失败 |
| DB 连接失败 | `docker logs petgo-server \| tail -50` | `~/.env.petgo` 的 `DB_PASSWORD` 与 petgo-postgres 建库密码是否一致；`docker exec petgo-postgres pg_isready` |
| Redis 报错 / 串数据 | `docker exec petgo-server env \| grep REDIS` | 确认 `REDIS_DB=2`（与 logistic/jbp 的 0 隔离） |
| `ddl-auto=validate` 启动失败 | 日志找 `Schema-validation` | 实体与 Flyway schema 不一致 → 看是否漏迁移（决策 E2：加列要新起 ALTER） |
| 公网 502 | `docker ps` 看 petgo-server 在跑吗 | 跑着→查 Cloudflare 路由指向 127.0.0.1:8084；挂了→看日志 |
| 端口冲突 | `ss -ltnp \| grep 8084` | 8084 被占则改脚本 `HOST_PORT` 与 CF 路由 |

常用日志：
```bash
ssh dai@62.146.239.156 docker logs -f --tail 100 petgo-server
# Flyway 历史
ssh dai@62.146.239.156 \
  "docker exec petgo-postgres psql -U petgo -d petgo -c 'SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10'"
```

---

## 6. 上线前 checklist

- [ ] `~/.env.petgo` 里 `JWT_SECRET` 已换强随机（非 dev 默认值）
- [ ] `DB_PASSWORD` 强密码且与 petgo-postgres 一致
- [ ] `REDIS_DB=2`（隔离共享 redis）
- [ ] 需要的 live 凭证已填（Gemini / OSS / Google）；不填则对应功能 stub/降级
- [ ] Cloudflare Tunnel 路由已配并验证公网 `/actuator/health`
- [ ] 改 schema 前已 `pg_dump` 备份
- [ ] ⚠️ 共享 redis：任何运维**禁止** `FLUSHALL`（会清掉 logistic/jbp 的数据）
```
