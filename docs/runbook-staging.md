# Runbook — Staging 环境搭建（长期有效，与生产并列，换库不换地址）

> 目标：在 `62.146.239.156` 上与生产 `petgo-server` **并列**起一套**长期 staging 环境**，上线前先在此验，**零影响生产用户**。**不回收**。
> 数据：staging **从生产完整克隆一份**（用户 + 帖子等，均内部人员，PII 可接受）。
> 原则：同一台主机、同一个 Postgres/Redis 实例（**换库不换地址**）、同一个 OSS 桶（**用 key 前缀命名区分**）、独立端口、独立容器、独立镜像 tag、独立 env 文件。
> 本文替代已作废的 `runbook-l2-test-instance.md`（那份是另起 postgres 容器 + dev 桩、且用后即毁，方案不同）。

---

## 0. 架构前提（务必先读）

- **「后台管理」(`/admin/**`) 与「后台服务」(`/api/v1/**`) 是同一个 Spring Boot 进程**，只有一个 `server.port`。staging = 起**一个新容器、占一个新端口**（8085），这一个端口两者都提供，不是两个端口。
- **换库不换地址**：连接串 `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`。staging 复用生产的 `petgo-postgres`（host/port 不变），只把 `DB_NAME` 指向新库 `petgo_stag`；Redis 复用生产 `redis` 容器，只把逻辑 DB 从 `2` 换成 `3`。
- **数据从生产克隆**：`petgo_stag` 由 `pg_dump petgo | psql petgo_stag` 灌入。→ 用户、帖子、`admin_accounts`（含你的 Lark 管理员）全部带过来，staging 不需单独 bootstrap 超管。
- **OSS 同桶、命名区分**：staging 与生产**共用 `tailtopia` 桶**，不建新桶。好处：克隆帖子的老媒体对象无需同步、直接读得到。staging **新上传**用 key 前缀（`stag/`）与生产对象区分（需后端支持，见 T1）。
  - ⚠️ **残留风险**：老对象（克隆帖子的媒体，无 `stag/` 前缀）与生产同桶同 key。staging 里删除这些老帖子会删掉共享对象 → **影响生产**。前缀只隔离 staging 新写入，隔离不了老对象删除。内部测试可接受，但别在 staging 拿克隆的老帖子做删除测试。
- **代码来源 = `stag` 分支**：staging 镜像从 `stag` 分支构建（`petgo-server:stag`），与生产（`main` → `:latest`）解耦。上线流程：改动进 `stag` → 部署 staging 验证 → 通过后合入 `main` → 部署生产。
- **⚠️ 命门：不能直接跑 `scripts/deploy-backend.sh`**。该脚本写死生产——会覆盖 `petgo-server:latest` 镜像、重启 `petgo-server` 容器（生产）。staging 必须走 `scripts/deploy-backend-stag.sh`（独立镜像 tag `petgo-server:stag` + 容器 `petgo-server-stag` + env `~/.env.petgo-stag`），全程不碰生产的 `petgo-server` / `:latest` / `:previous`。

### 资源分配对照

| 资源 | 生产 | Staging |
|---|---|---|
| 分支 | `main` | `stag` |
| 容器名 | `petgo-server` | `petgo-server-stag` |
| 镜像 tag | `petgo-server:latest` | `petgo-server:stag` |
| 应用端口（本机） | `127.0.0.1:8084` | `127.0.0.1:8085` |
| Postgres | `petgo-postgres` :5432 · 库 `petgo` | **同容器同端口** · 库 `petgo_stag`（克隆自 `petgo`） |
| Redis 逻辑 DB | `2` | `3` |
| env 文件 | `~/.env.petgo` | `~/.env.petgo-stag` |
| OSS 桶 | `tailtopia` | **同桶 `tailtopia`** · 新上传 key 前缀 `stag/` |
| profile | `prod` | `prod` |
| API hostname | `api.tailtopia.id` | `api-stag.tailtopia.id` |
| Admin hostname | `ops.tailtopia.id` | `ops-stag.tailtopia.id` |

---

## A. 决策（已定稿）

- [x] **A1 · Profile** = `prod`（真 Google 登录、安全规则同生产，保真最高）。
- [x] **A2 · 代码来源** = 新建 `stag` 分支，staging 镜像从 `stag` 构建。
- [x] **A3 · 第三方隔离** = OSS **同桶 + key 前缀命名区分**（不建新桶）· Gemini **复用生产 live key** · 腾讯 IM **复用生产 SDKAppID**。
- [x] **A4 · Admin** = 走 **Lark 登录**（需 `ops-stag` 域名 + 注册 `LARK_REDIRECT_URI`；管理员账号随数据克隆带入，无需 bootstrap）。
- [x] **A5 · 数据** = 从生产 `petgo` 完整克隆 DB（OSS 同桶不需同步对象）。

---

## T. 前置代码改动（stag 分支上）

- [ ] **T1 · OSS 可配置 key 前缀**（让 staging 新上传落到 `stag/` 下，与生产对象区分）
  - 加配置 `media.oss.key-prefix`（env `MEDIA_OSS_KEY_PREFIX`，默认空字符串 → 生产行为不变）。
  - 在两处对象 key 构造前置该前缀：
    - `PresignedUploadService.java:56`（`scope.prefix() + "/" + userId + "/" + randomToken()...`）
    - `ImToOssArchiver.java:66`（`"private/health/" + petId + "/" + token + ".jpg"`）
  - 读路径（`SignedUrlService` / `AliyunOssClient.publicUrl`）无需改：DB 里存的 key 已含前缀，签名/拼 URL 原样透传。
  - staging env 设 `MEDIA_OSS_KEY_PREFIX=stag/`；生产不设（保持空）。

---

## B. 分支准备（本地，已完成）

- [x] **B1** 从最新 `origin/main` 切 `stag` 分支，并解绑错误上游。
  ```bash
  git checkout -b stag origin/main
  git branch --unset-upstream          # 防 git push 误推到 main
  # 推送时用：git push -u origin stag   （需你明确同意）
  ```

---

## C. 服务器一次性初始化（staging 专用资源，均在同实例上）

> 均在同实例上操作，不新增 Postgres/Redis 容器、不新建 OSS 桶。

- [ ] **C1 · 从生产克隆 staging 库**（同容器、新库、数据完整复制）
  ```bash
  ssh dai@62.146.239.156
  docker exec petgo-postgres psql -U petgo -d petgo -c 'DROP DATABASE IF EXISTS petgo_stag;'
  docker exec petgo-postgres psql -U petgo -d petgo -c 'CREATE DATABASE petgo_stag OWNER petgo;'
  # 生产 → staging 全量克隆（pg_dump 只读生产，不改生产）
  docker exec petgo-postgres sh -c 'pg_dump -U petgo petgo | psql -U petgo -d petgo_stag'
  # 校验
  docker exec petgo-postgres psql -U petgo -d petgo_stag -c '\dt' | head
  ```
  > 克隆后 `flyway_schema_history` 停在生产当前版本；stag 分支若有更高版本迁移，应用启动时 Flyway 顺延执行。

- [ ] **C2 · staging env 文件**（基于生产 env 覆盖差异项；DB_HOST/PORT、REDIS_HOST/PORT、OSS 桶名全不动）
  ```bash
  cp ~/.env.petgo ~/.env.petgo-stag
  sed -i \
    -e 's/^SPRING_PROFILES_ACTIVE=.*/SPRING_PROFILES_ACTIVE=prod/' \
    -e 's/^DB_NAME=.*/DB_NAME=petgo_stag/' \
    -e 's/^REDIS_DB=.*/REDIS_DB=3/' \
    ~/.env.petgo-stag
  # JWT_SECRET 换独立值（staging token 与生产互不通用）
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -base64 48)|" ~/.env.petgo-stag
  # Lark 回调改 staging 域名
  sed -i 's|^LARK_REDIRECT_URI=.*|LARK_REDIRECT_URI=https://ops-stag.tailtopia.id/admin/oauth/callback|' ~/.env.petgo-stag
  # OSS 新上传前缀（T1 落地后生效；同桶，仅区分 staging 新对象）
  echo 'MEDIA_OSS_KEY_PREFIX=stag/' >> ~/.env.petgo-stag
  chmod 600 ~/.env.petgo-stag
  # 核对（不打印密码/密钥）；OSS_PUBLIC_BUCKET/PRIVATE_BUCKET 应仍是 tailtopia（与生产同）
  grep -E '^(SPRING_PROFILES_ACTIVE|DB_NAME|REDIS_DB|OSS_PUBLIC_BUCKET|OSS_PRIVATE_BUCKET|MEDIA_OSS_KEY_PREFIX|LARK_REDIRECT_URI|DB_HOST|REDIS_HOST)=' ~/.env.petgo-stag
  ```
  > Gemini/腾讯 IM 复用生产：`GEMINI_MODE=live` + 同 key、`IM_MODE` 同生产、同 `TENCENT_IM_SDK_APP_ID`（直接沿用生产 env 值，不改）。
  > `GOOGLE_OAUTH_CLIENT_ID`、`LARK_APP_ID/SECRET/TENANT_KEY`、`OSS_*` 均沿用生产同值（只 `LARK_REDIRECT_URI` 换 staging 域 + 追加 `MEDIA_OSS_KEY_PREFIX`）。
  > ⚠️ 生产 env 若没有 `LARK_REDIRECT_URI` 这行，`sed` 替换会无效——需先确认再改用 `echo '...' >> ~/.env.petgo-stag` 追加。

- [ ] **C3 · Lark 应用注册 staging 回调**
  - 在 Lark 开放平台该应用的 **重定向 URL allowlist** 追加 `https://ops-stag.tailtopia.id/admin/oauth/callback`（与生产回调并存，不删生产的）。

- [ ] **C4 · 端口占用检查**
  ```bash
  ssh dai@62.146.239.156 'ss -ltnp | grep 8085 || echo "8085 空闲"'
  ```

---

## D. 部署链路（与生产物理隔离）

- [ ] **D1 · 部署脚本**：`scripts/deploy-backend-stag.sh`，固定 jar 来源 `stag` 分支、`petgo-server:stag`、`petgo-server-stag`、`127.0.0.1:8085`、`~/.env.petgo-stag`。
- [ ] **D2 · 护栏**：脚本只 stop/rm `petgo-server-stag`；严禁触碰 `petgo-server` 容器与 `:latest`/`:previous` 镜像 tag；非 `stag` 分支拒绝部署（`ALLOW_BRANCH=1` 强制）。
- [ ] **D3 · 首启验证**：`petgo_stag` 库 Flyway 校验通过（克隆版本 + stag 增量）、`/actuator/health` UP。
  ```bash
  ssh dai@62.146.239.156 'curl -s http://127.0.0.1:8085/actuator/health'
  ```

---

## E. 域名 / 隧道（Cloudflare 控制台，手动）

- [ ] **E1** Public Hostname：`api-stag.tailtopia.id` → `HTTP` `127.0.0.1:8085`。
- [ ] **E2** Public Hostname：`ops-stag.tailtopia.id` → `HTTP` `127.0.0.1:8085`（与 api-stag 同端口同容器，靠路径 `/admin` 区分）。
- [ ] **E3** 隧道生效后与 C2 的 `LARK_REDIRECT_URI` 域名一致。

---

## F. 验证 & 护栏

- [ ] **F1** 公网 `curl https://api-stag.tailtopia.id/actuator/health` = UP。
- [ ] **F2 · Admin 登录**：开 `https://ops-stag.tailtopia.id/admin`，用你的 Lark 账号登入（管理员账号已随克隆带入）。
- [ ] **F3 · 数据克隆确认**：staging 用户/帖子数与生产一致；帖子图片正常显示（同桶直读，无需同步）。
- [ ] **F4 · 隔离验证**：staging DB 写入只进 `petgo_stag`（生产库 `petgo` 不受影响）；Redis key 只在 DB 3；staging 新上传的媒体 key 带 `stag/` 前缀。
- [ ] **F5 · 生产未削弱**：`petgo-server`（生产容器）仍在跑；`petgo-server:latest` 镜像未被覆盖；生产 `/actuator/health` 仍 UP。
- [ ] **F6 · 共享 redis 红线**：任何运维**禁止** `FLUSHALL`；staging 只能 `redis-cli -n 3 FLUSHDB`。
- [ ] **F7 · OSS 共享桶红线**：别在 staging 删除克隆来的老帖子/媒体（会删共享桶真实对象，影响生产）。
- [ ] **F8 · staging APK 指向 staging 域**：
  ```bash
  flutter build apk --release \
    --dart-define=PETGO_MOCK=false \
    --dart-define=PETGO_API_BASE_URL=https://api-stag.tailtopia.id
  ```

---

## G. 日常运维（长期环境，不回收）

- **重新部署 staging**（改动进 `stag` 分支后）：
  ```bash
  git checkout stag && git pull
  ./scripts/deploy-backend-stag.sh
  ```
- **从生产重新同步数据**（周期性刷新 staging 为最新生产快照）：
  ```bash
  ssh dai@62.146.239.156 'docker rm -f petgo-server-stag'
  ssh dai@62.146.239.156 "docker exec petgo-postgres psql -U petgo -d petgo -c 'DROP DATABASE petgo_stag; CREATE DATABASE petgo_stag OWNER petgo;'"
  ssh dai@62.146.239.156 "docker exec petgo-postgres sh -c 'pg_dump -U petgo petgo | psql -U petgo -d petgo_stag'"
  ssh dai@62.146.239.156 'docker exec redis redis-cli -n 3 FLUSHDB'   # ⚠️ 只带 -n 3，绝不 FLUSHALL
  ./scripts/deploy-backend-stag.sh
  ```
  > OSS 同桶，无需同步对象。
- **改 schema 前备份 staging 库**：
  ```bash
  ssh dai@62.146.239.156 'docker exec petgo-postgres pg_dump -U petgo petgo_stag' > petgo_stag-backup-$(date +%Y%m%d-%H%M%S).sql
  ```
- 生产资源（`petgo-server` 容器、`:latest` 镜像、`petgo` 库、Redis DB2）一概不动。
