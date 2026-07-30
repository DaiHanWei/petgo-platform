# PetGo Platform — Agent 工作约定（本地 + 云端通用）

> 本文件随仓库 commit，**云端 session（Codex.ai/code）clone 后会自动加载**。本地 / 云端 dev agent 都以此为准。
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
4. **严格按 Epic 1→7、story 编号升序**。Flyway 序号按执行顺序**单调分配**（占位 `V<n>__`，实际号顺延，勿照搬 architecture 示例号，会撞 → 决策 E2）。

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
