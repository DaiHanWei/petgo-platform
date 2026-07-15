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

## 0.5 集成分支模型（发布边界 · 长期有效）

物理隔离见上表；这里是**分支 / 发布边界**——staging 在研发流里扮演什么角色。

**`stag` = 长期集成线（integration branch），不是某个功能分支。** 多个功能分支在此汇流做集成测试，测好的**选择性**合入 `main` 上线。

```
  功能分支            汇总分支           集成线(=staging)      生产
  feat/content-mod ─┐
  v1.1-epic1..9 ────┼─→  v1.1-dev  ─────→   stag   ──选择性──→  main
  feat/X ───────────┘   (功能测好谁先上 main，其余留 stag 继续)
        merge              merge            merge        merge
```

**边界规则：**
- **不直接向 `stag` 提交代码**——一切经**合并**进入（保持 stag 可追溯为各功能分支之和）。
- **功能分支 → 汇总分支（如 `v1.1-dev`）→ `stag`**。同系列多 epic 先在 `v1.1-dev` 汇总，再整体合 `stag`。
- **`stag` → `main` 是选择性的**：某功能在 staging 验好了就单独合 `main` 上线，其它功能继续留 `stag` 迭代。
- **承重假设：功能之间 DB 不交叉**（各占独立表 / 迁移号段）。一旦两个功能改同一张表，选择性上线会很难——设计时保持功能间 schema 互不相干。
- **staging 库 = 所有在飞功能迁移的并集**（例：`petgo_stag` 同时有内容审核 `V47–V56` 与 v1.1 `V60–V83`）。这决定了 Flyway 的处理方式，见 §H。

> 具体一次合并 + 部署的实例留档见 `docs/deploy-record-2026-07-15-gempay-staging.md`（GemPay 收款接入那次）。

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

---

## H. 把一个功能分支合并进 stag（含 Flyway 并存）

> 场景：某功能分支开发完，要进 staging 做集成验证。这是 §0.5 模型的**执行步骤**。
> 实例参考：2026-07-15 把整条 v1.1（epic1-9）合入 stag（14 个并集式冲突，详见该次 commit）。

### H1 · 先汇总（若属某系列）
同系列多 epic 先合到汇总分支再整体入 stag，避免零散合并：
```bash
git checkout v1.1-dev && git merge --ff-only <feature-branch>   # 通常快进
```

### H2 · 合并进 stag
```bash
git checkout stag
git merge --ff-only origin/stag            # 先把本地 stag 同步到最新
git merge --no-ff --no-commit v1.1-dev     # 发起合并，先不提交，看冲突
git diff --name-only --diff-filter=U       # 冲突文件清单
```

### H3 · 解冲突（经验：绝大多数是并集）
两个功能往同一批**共享文件**各加各的，多数**保留两侧**即可：
- **枚举 / 权限码 / 审计动作 / i18n / 仓库方法 / getter**：并集保留（如 `NotificationType`、`AdminPermissions`、`AuditActions`、`messages_*.properties`、`UserRepository`、`User` getters、注销联动多块并存）。
- ⚠️ **真冲突要按语义定夺**（非简单并集）：
  - **方法签名分叉**（如 `CommentRepository.findRepliesForParents` 一侧带 `viewerId` 一侧不带）→ 看 `@Query` 引用的参数 + 调用点，取能自洽的那个签名，另一侧的**新方法**照常并入。
  - **构造器参数**（如 `AccountDeletionService` 测试）→ 取**两侧并集**、按目标构造器**字段顺序**排列。
  - **枚举末值补逗号**：一侧原是最后一个值（无逗号），并入另一侧后要补 `,`。
  - **重复字段去重**：两侧都加了同名字段（如 `_doneCount`）→ 只留一个。
  - **模板导航**：并入新链接 + 保留正确的 `sec:authorize` 授权。

### H4 · 验证（提交前必过）
```bash
cd petgo-backend && ./mvnw -B -o test-compile     # 期望 EXIT=0
cd ../petgo_app && flutter gen-l10n && flutter analyze lib   # arb 变了要先 gen-l10n；期望 No issues
```
> 关注「死码 warning」——可能是某侧功能被另一侧重写覆盖的**语义损失**（如某功能字段成 unused）。要么重新集成，要么移除并**在 commit 里显式标注该功能被覆盖**。

> ⚠️ **编译过 ≠ 能启动！** 两条分支各自新增的 Controller / Bean 可能在**运行时**撞车——最典型是**路由重复**（两个 Controller 映射同一 `METHOD 路径` → Spring `Ambiguous mapping` 启动即崩），`test-compile` / `analyze` **查不出**。合并后**必须做一次上下文加载检查**：
> - 快扫路由重复（无需 DB）：`grep` 全部 `*Controller.java` 的 `@(Get|Post|Put|Delete)Mapping` 路径找重复；
> - 或部署到 staging 后**立即查 `/actuator/health` + `docker logs` 里 `Application run failed` / `Ambiguous mapping`**（本环境 2026-07-15 GemPay 部署即因两侧各建「后台评论下架/恢复」Controller 撞 `/admin/comments/{id}/restore` 崩过一次）。
> - 功能重复（两条线各造同一后台功能）应由团队**协调合并成一个**；stag 上先禁用其一解冲突（该禁用只在 stag，不随功能上 main），并记 backlog。

### H5 · 提交合并 + push
```bash
git add <解决的冲突文件>
git commit                              # 完成合并（消息里记冲突解决要点 + 任何语义损失）
git push origin stag                    # ⚠️ 需你明确同意；shared 分支
```

### H6 · Flyway 并存铁律（关键，别踩）
- **stag 库 = 各功能迁移的并集**。只要**迁移文件都在 stag 分支上**，`validate` 就不会报「已应用但找不到」。
- ⚠️ **绝不要把裸功能分支直接部署到 `petgo_stag`**：功能分支缺其它功能的迁移文件 → Flyway 报 missing migration、容器起不来。**一律先合进 `stag`，再部署 `stag`**（部署走 §D/§G）。
- **`out-of-order=true` 已开**（`application.yml` `spring.flyway.out-of-order`）：功能按「谁测好谁先上」顺序合 `main`，生产 apply 顺序**非单调**（如先上 V60-83、后补 V47-56 的低号），必须开此开关，否则后上的功能在生产 `validate` 失败。
- **号段纪律（决策 E2）**：新功能迁移号取**当前全局 max 之后**顺延；已提交迁移**冻结**，改动一律**新起 `ALTER`**，绝不改旧文件（改旧文件会破坏已部署环境的 checksum 校验）。

### H7 · 部署到 staging
合并 + push 后，按 §D / §G 用 `scripts/deploy-backend-stag.sh` 部署（构建 stag → 8085 → Flyway 在 `petgo_stag` 上顺延应用新迁移）。
