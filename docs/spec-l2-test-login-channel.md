# SPEC — L2 测试登录通道（绕真 Google OAuth）

> **状态**：ready-for-dev（自包含，可交云端/本地实现）
> **目的**：让自动化 L2 验收能在**真实后端**上拿到合法会话 token，无需人工过 Google OAuth 同意屏，从而解锁需登录态的 ~80% 用例（见 [`L2-acceptance-emulator-real-backend.md`](./L2-acceptance-emulator-real-backend.md)）。
> **护栏（不可违反）**：CLAUDE.md §Enforcement —— **安全规则层只升不降不可绕过**；凭证 env 注入绝不入库；日志严禁 PII/token。本通道**绝不可在真公网生产暴露**。

---

## 0. 背景：能力已存在，但 gated 在 dev profile

代码里**已经有**一套「免真实 Google 登录」机制（勿重造）：

| 组件 | 作用 | gating |
|---|---|---|
| `shared/security/GoogleTokenVerifier`（接口） | Google 身份校验抽象 | — |
| `NimbusGoogleTokenVerifier` | 真实 JWKS 校验 | prod 用 |
| `DevGoogleTokenVerifier` | **忽略 idToken，恒返回固定测试身份** `DEV_SUB="dev-stub-user"` | `@Profile("dev")` `@Primary` |
| `auth/service/DevUserSeeder` | dev 启动幂等 seed 一个已完成 onboarding 的测试账号 | `@Profile("dev")` |
| 前端 `PETGO_DEV_STUB_LOGIN` | 点 Google 登录发假 idToken | debug 默认 true |

**问题**：你部署的服务器是 `SPRING_PROFILES_ACTIVE=prod`，上述 dev bean **不注册** → 真后端只认真实 Google JWKS 校验 → 自动化无法登录。

---

## 1. 目标与非目标

**目标**
- 自动化测试能用一个**非交互**方式换取与正式登录**同结构**的会话 token（`LoginResponse`：accessToken/refreshToken + 用户视图），后续 `/api/v1/me` 等鉴权接口正常可用。
- 跑在**真实业务代码 + 真实 Gemini live / OSS live** 之上（这才是 L2 的意义）。
- 对真实公网生产**零攻击面**：默认关闭、可证明不可达。

**非目标**
- 不替代真 Google 登录的 L2（那条仍由人工/真机收尾，见验收文档 §6）。
- 不改动 `NimbusGoogleTokenVerifier` 的真实校验逻辑（只升不降）。

---

## 2. 方案选型（二选一，推荐 A）

### ✅ 方案 A（推荐）：独立「测试环境」实例，复用现有 dev 机制，**零新增鉴权代码**

起一个**与生产并列、隔离的** `petgo-server-test` 容器，以 **dev profile** 运行（复用 `DevGoogleTokenVerifier` + `DevUserSeeder`），但**接真实 live 凭证**（Gemini/OSS 同一套 key）+ **独立测试库** + 独立隧道 hostname。

```
api.tailtopia.id       → petgo-server      (prod profile, 真 Google 校验)   ← 正式
api-test.tailtopia.id  → petgo-server-test (dev  profile, stub 登录)        ← L2 自动化打这里
                          ├─ 独立 DB: petgo-postgres-test (隔离，不污染正式数据)
                          └─ 共用 live 凭证: Gemini / OSS（同 key）
```

- **优点**：生产鉴权链路一行不动（最符合"只升不降"）；不污染正式库；几乎零代码（纯 ops）；想销毁随时 `docker rm`。
- **代价**：多一个容器 + 一条隧道 hostname + 一个测试库。
- 前端测试包指向 `api-test.tailtopia.id`，`PETGO_DEV_STUB_LOGIN=true`（点登录即落 seed 测试账号）。

### 方案 B（备选）：单生产实例 + env 门控的专用 dev-login 端点

若坚持只维护一个部署，则新增一个**与 profile 解耦、env 双重门控**的端点，可与 prod 并存：

- `POST /api/v1/auth/dev-login`，仅当 `app.dev-login.enabled=true`（env `DEV_LOGIN_ENABLED`，**默认 false**）注册。
- 必须携带强随机共享密钥 `DEV_LOGIN_SECRET`（env，≥32 字节），服务端**常量时间比较**；不匹配 404（不泄漏存在性）。
- 校验通过 → 幂等 upsert 专用测试用户 → 复用 `AuthService` 正式签发 `LoginResponse`。
- **代价/风险**：在生产代码里引入一条鉴权旁路，必须证明默认关 + 强密钥 + 不可枚举；风险高于 A。

> **决策建议**：选 **A**。它把"测试旁路"关在独立实例里，正式 `api.tailtopia.id` 的鉴权零妥协。下文以 A 为主、B 为附。

---

## 3. 方案 A 实现任务（后端 ops 为主）

> 全部在服务器 `62.146.239.156` 上，与现有 `petgo-server` 并列；勿动现有 prod 容器。

### T1. 测试库容器
```bash
docker run -d --name petgo-postgres-test --network jbp-net \
  -p 127.0.0.1:5433:5432 -v petgo_pgdata_test:/var/lib/postgresql/data \
  -e POSTGRES_DB=petgo -e POSTGRES_USER=petgo -e POSTGRES_PASSWORD=<测试库强密码> \
  --restart unless-stopped postgres:16-alpine
```

### T2. `~/.env.petgo-test`（基于 `~/.env.petgo` 改 4 处）
- `SPRING_PROFILES_ACTIVE=dev`（**关键**：启用 DevGoogleTokenVerifier + DevUserSeeder）
- `DB_HOST=petgo-postgres-test`、`DB_PASSWORD=<T1 的密码>`
- `REDIS_DB=3`（与生产 petgo 的 2、logistic/jbp 的 0 再隔离一格 keyspace）
- Gemini/OSS 凭证**沿用真实 live**（同 key，验真实第三方）

> ⚠️ 确认 `application-dev.yml`/dev profile 下 `ddl-auto` 仍是 `validate`（schema 归 Flyway）；dev seeder 只 seed 用户行，不碰 schema。

### T3. 起测试应用容器（复用现有镜像，仅换 env + 端口）
```bash
docker run -d --name petgo-server-test --network jbp-net \
  -p 127.0.0.1:8085:8080 --restart unless-stopped \
  --env-file ~/.env.petgo-test petgo-server:latest
# 等就绪
sleep 10 && curl -fs http://127.0.0.1:8085/actuator/health   # 期望 UP + dev seed 日志
```
日志应出现 `⚠️ DEV Google 校验桩已启用` 和 `DEV seed：已创建测试用户`。

### T4. 隧道 hostname
Cloudflare Zero Trust → 现有 tunnel → Public Hostname → Add：
`api-test.tailtopia.id` → `HTTP 127.0.0.1:8085`。

### T5.（可选）DevUserSeeder 增强
当前只 seed 一个 `HAS_PET` 用户。若 L2 需覆盖 B/C 状态用户、或需带宠物档案/历史的更真实账号，可在 dev profile 下**扩展 seed**（多账号 + 预置宠物/时间线/问诊历史），便于免手造数据直接验各页。**仅 dev profile**，prod 不受影响。

---

## 4. 方案 B 实现任务（若选 B）

| 步 | 内容 |
|---|---|
| B1 | 新增 `auth/web/DevLoginController`，`@ConditionalOnProperty("app.dev-login.enabled")`，路径 `POST /api/v1/auth/dev-login`，**放行链**（同 google/refresh）。 |
| B2 | 入参 `{secret, subject?}`；`secret` 与 `DEV_LOGIN_SECRET` 常量时间比较，不匹配→404 ProblemDetail。 |
| B3 | 通过后幂等 upsert 专用测试用户（独立 sub，如 `l2-test-user`，**不复用** prod 真实账号），调 `AuthService` 现有签发逻辑返回 `LoginResponse`。 |
| B4 | 启动校验：若 `DEV_LOGIN_ENABLED=true` 同时检测到"真公网生产"标记，则**WARN 并拒绝启用 / 启动失败**（防误开）。 |
| B5 | 限流复用 `RedisRateLimiter`；日志严禁记 secret/token。 |

---

## 5. 验收标准（AC，按 L0/L1/L2 分层）

| AC | 描述 | 层级 |
|---|---|---|
| AC1 | 现有 `NimbusGoogleTokenVerifier` 真实校验逻辑**零改动**；prod profile 下 dev bean 不注册（已有单测/启动验证） | **L0** `mvn -B test` |
| AC2 | （A）`petgo-server-test` 以 dev profile 启动 health=UP，日志含 dev 桩 + seed 提示 | **L1** 服务器 docker run |
| AC3 | （A）`curl api-test... /api/v1/auth/google`（任意假 idToken）→ 返回合法 `LoginResponse`；持其 accessToken 调 `/api/v1/me` → 200 真实测试用户 | **L1** |
| AC4 | 测试库与生产库**物理隔离**：测试期写入只进 `petgo-postgres-test`，`petgo-postgres`（正式）零变化 | **L1** 对比两库 |
| AC5 | 正式 `api.tailtopia.id`（prod）**仍只认真实 Google**：发假 idToken → 401/校验失败 | **L1** 回归 |
| AC6 | 自动化 L2：测试包指向 `api-test`，模拟器点 Google 登录直接进登录态，跑通验收文档 §4 需登录态用例 | **L2** 模拟器 |
| AC7 |（B 专属）`DEV_LOGIN_ENABLED` 默认 false 时端点 404；开启需正确 `DEV_LOGIN_SECRET`，错误恒 404 | L0+L1 |

---

## 6. 安全 checklist（合并前逐条过）

- [ ] 正式 `api.tailtopia.id` 仍 prod profile，发假 token 被拒（AC5）
- [ ] 测试实例为独立容器 + 独立库 + 独立 hostname，可随时销毁
- [ ] 测试库密码 ≠ 生产库密码；`REDIS_DB` 再隔离（建议 3）
- [ ] （B）`DEV_LOGIN_ENABLED` 默认 false；secret 强随机、常量时间比较、错误不泄漏存在性
- [ ] 任何门控开关**走 env/profile，绝不入库**
- [ ] 日志无 idToken / JWT / secret / email / PII
- [ ] ⚠️ 共享 redis：测试实例任何运维禁止 `FLUSHALL`

---

## 7. 云端执行须知（headless 自包含）

- 本 spec 主体是 **ops（docker/Cloudflare）+ 少量可选代码**，云端 headless 能做的是 **L0**（`mvn -B test` 验 AC1：prod 不注册 dev bean）；**AC2–AC6 属 L1/L2，须在服务器 / 本地模拟器执行**，云端只标注"待服务器/本地验收"。
- 若选 **B**：`DevLoginController` + `@ConditionalOnProperty` + 单测（默认关→404、开+对密钥→200、开+错密钥→404）云端可全 L0 跑绿。
- 现有相关类位置：`auth/web/AuthController`、`auth/service/AuthService`、`auth/service/DevUserSeeder`、`shared/security/{GoogleTokenVerifier,DevGoogleTokenVerifier,NimbusGoogleTokenVerifier}`、`auth/dto/LoginResponse`。
- Flyway 冻结纪律（决策 E2）：本 spec **不应**新增迁移；测试库由现有 V1–V28 迁移自动建。

---

## 8. 给打包方的衔接（方案 A 落地后）

L2 测试 APK 改指向测试入口即可：
```bash
flutter build apk --release \
  --dart-define=PETGO_MOCK=false \
  --dart-define=PETGO_API_BASE_URL=https://api-test.tailtopia.id \
  --dart-define=PETGO_DEV_STUB_LOGIN=true        # dev 实例下点登录即落 seed 测试账号
# 注意：测试包用 api-test；正式分发包仍用 api.tailtopia.id + 真 Google 登录
```
