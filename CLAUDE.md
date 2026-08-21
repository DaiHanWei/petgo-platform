# TailTopia Platform — Agent 工作约定（本地 + 云端通用）

> 本文件随仓库 commit，**云端 session（claude.ai/code）clone 后会自动加载**。本地 / 云端 dev agent 都以此为准。
> 与用户沟通用**中文**。

## 这是什么项目

**双产物 monorepo**，两套工程并列在仓库根下：

| 目录 | 技术栈 | 版本基线（架构已联网核实，权威，勿降级） |
|---|---|---|
| `petgo_app/` | Flutter 移动端 | Flutter **3.44.x** / Dart **3.12** · Riverpod + go_router + dio + intl · portrait-only · V1 仅浅色 |
| `petgo-backend/` | Spring Boot 后端 | Spring Boot **4.0.6** · Java **21 LTS** · Maven · PostgreSQL + Redis + Flyway · 部署留德国单机 |

> ⚠️ 版本说明：以 `start.spring.io` / `pub.dev` 实际可解析版本为准；若 `4.0.6` / `3.44.x` 不可用，取同大版本最新 patch 并在 story 的 Completion Notes 记录。**底线是保持 Spring Boot 4 / Spring 7（勿退回 SB3）**。
> Java 基线为 **21 LTS**（2026-06-02 决策：原拟 25，但无任何功能需求依赖 25，且云端自带 21、生态更成熟、Temurin 21 LTS 支持到 ~2029；改 21 消除前沿摩擦，符合 V1 轻量姿态。Boot 4 官方支持 Java 17+，21 完全兼容）。

**这不是纯前端原型** —— 后端是真实 Spring Boot 服务，不要用 mock 数据糊弄后端。前端在没有后端时可用占位/mock，但每个 story 的后端子任务要真实落库与接口。

## 规划产物在哪（单一事实源）

- **PRD / 架构 / UX**：`_bmad-output/planning-artifacts/`（`PRD.md`、`architecture.md`、`UX_DESIGN.md`、`epics.md`）
- **46 份 story**：`_bmad-output/implementation-artifacts/<epic>-<n>-<中文名>.md`，状态 `ready-for-dev`
- **跨 story 契约/数据生命周期决策**：`_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md` —— **遇冲突以此为准**
- **执行顺序与 Flyway 约定**：`_bmad-output/implementation-artifacts/sprint-status.yaml`

## 实现一个 story 的纪律

1. 用 `bmad-dev-story` 流程执行指定 story 文件，**不要自己临场发挥**。
2. **后端 → 前端 → 联调**三段推进（每个 story 跨双代码库，一次只碰一侧，最后联调）。
3. 每条 AC 标了验证层级：
   - **L0 静态**：`flutter analyze` / `flutter test` / `mvn -B compile|package`（无需 DB、无需凭证）
   - **L1 集成**：需 Docker daemon + postgres + redis 真跑（`mvn spring-boot:run` + `/actuator/health=UP`）
   - **L2 端到端**：需真实第三方凭证 / 真机 / 模拟器视觉
4. **严格按 Epic 1→7、story 编号升序**。
5. **Flyway 新迁移一律时间戳版本号**：`V<yyyyMMdd_HHmm>__<snake_case>.sql`（如 `V20260821_1435__init_shop_orders.sql`，取创建时刻），**禁止再用序列号**（决策 E7，取代 E6/E2；时间戳制为常设规则，不再有过渡条款）。
   - **存量序号迁移 V1–V108 一律保留原号，不改名、不返工**（名单见 `scripts/ci/flyway-legacy-versions.txt`）。它们多数已应用到 prod / `petgo_stag`，改名会让 Flyway 找不到已应用记录、启动即拒。**一切以数据库现状为准。**
   - **能不能改一个迁移文件，判据是「它有没有被任何环境应用过」，不是「它在不在 main 上」**：已应用的绝不能改（checksum 对不上，启动即失败），从未应用过的可以直接改。
   - **改 CHECK 约束必须重列全集**：`ck_notifications_type` 这类被多条工作线共用的约束，一律 `DROP + ADD` 全量重建，且值必须取自**当前树里最后一条重建它的迁移**，不是某个记忆中的旧列表。此处已出过三次事故（V72、V20260818_0358 各丢一批值），每次都是照着过期列表抄。
   - CI `flyway-guard` 强制：树内同号 / 动 main 上已有文件 / 新增非时间戳且不在 legacy 名单 / 与 main 撞号 → PR 不绿（本地自查：`bash scripts/ci/check-flyway-versions.sh origin/main`）。
   - `out-of-order=true` 已全环境常开，时间戳跨分支合并乱序是预期行为。
6. **构建产物必须 `clean`**：`mvn package` 不删 `target/classes` 的旧文件，迁移改名后新旧两份会一起进 jar，Flyway 报 `Found more than one migration with version X` 启动即崩（2026-08-21 实际踩过）。**打包一律 `mvn -B clean package`。**

## ☁️ 云端（headless）能做什么、不能做什么

云端 VM 是 **headless** 的：

- ✅ **能**：`flutter analyze`、`flutter test`、`flutter build apk --debug`、`mvn -B package`（即所有 **L0**）。
- ❌ **不能**：模拟器/真机视觉、Flutter UI 渲染、任何 GUI（**L2 视觉验收必须 teleport 回本地**）。
- ⚠️ **L1（Docker postgres+redis）**：云沙箱不保证有 Docker daemon。**默认把 L1/L2 留本地**；云端只跑到 L0 绿灯，在 story 的 Completion Notes 标注「L1/L2 待本地验收」。

## 强制护栏（架构 §Enforcement —— 违反即返工）

- 异步只用 `@Async` + DB 状态机，**禁止引入 MQ / 通用缓存层 / 新中间件**（Kafka/RabbitMQ/Caffeine 等一律不加）。
- `spring.jpa.hibernate.ddl-auto=validate` —— **schema 归 Flyway，禁用 `update`/`create`**。
- 对外暴露标识一律**不可枚举 token**，不用自增 id 直接外露。
- 凭证全部 **env 注入，绝不入库**（`.env.example` 只放占位）。
- **红色态零变现**、安全规则层**只升不降不可绕过**、注销**级联删除/匿名化**按 D1/D2 落实 —— 这三类（story 4.2 / 4.5 / 7.3）是安全攸关节点，写代码时勿埋违反点。

## 命名映射链（核心）

DB `snake_case` ↔ Java/Dart `camelCase` ↔ JSON `camelCase`（JPA + Jackson 自动桥接）。
- 表复数 snake_case；主键 `id`(bigint)；外键 `<单数>_id`；时间戳 `created_at`/`updated_at`(`timestamptz`, 一律 UTC)；枚举落库 `varchar` + UPPER_SNAKE。
- API：`/api/v1`，资源小写复数连字符（`/api/v1/pet-profiles`）；当前用户统一 `/api/v1/me`（**不用 `/users/me`** → 决策 C1）。
- 错误统一 **RFC 9457 ProblemDetail**（type/title/status/detail/instance/traceId），**绝不外泄堆栈**。
- 日志 SLF4J + logback **JSON**，**严禁记录 PII / 健康数据 / 令牌 / 签名 URL**。

> 完整目录树、正反例、状态机见 `_bmad-output/planning-artifacts/architecture.md`。
