# Story 4.1: 分诊异步基建与 Gemini 接入

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **用户**,
I want **提交症状后系统可靠地完成 AI 分析**,
so that **我能在 15 秒内拿到分诊结果，即使偶发异常也能重试**。

> 本 Story 是 Epic 4「AI 智能分诊」的**后端地基**——交付分诊异步契约（`POST /triage`→202+triageId / 短轮询）、`triage_tasks` DB 状态机（PENDING/PROCESSING/DONE/FAILED + retry_count + 启动重扫）、`GeminiClient`（Developer API, gemini-2.5-flash, 签名 URL 直拉私密图）、JSONB 存原始响应，与 ≤15s SLA + 失败重试降级。**本 Story 不做安全规则层强制升红（Story 4.2）、不做问诊入口/上传 UI（Story 4.3）、不做结果三态/红色半屏 UI（Story 4.4/4.5）**——但要为 4.2 预留"Gemini 返回后置校验"的挂载点，为 4.3/4.4 提供可调用的契约。
>
> 前置依赖：Story 2.1（媒体基建——STS 直传私密桶 + 签名 URL 服务 `shared/media`）必须先就位（本 Story 复用其签名 URL 发放能力供 Gemini 直拉私密图）；Story 1.1（脚手架 / DB 状态机 `shared/async` / ProblemDetail / `@Async` 配置）已就位。

## Acceptance Criteria

> **验证层标注**：每条 AC 末尾标注验证层级与所需本地环境——
> **L0 静态**（编译/lint，无需 DB） · **L1 集成/运行时**（需 Docker daemon + postgres + redis） · **L2 端到端/外部凭证**（需真实 Gemini API key + 私密桶签名 URL + 真机/模拟器）。
> 本 Story 是 Epic 4 唯一**带 L2**（真实 Gemini）的后端 Story——AI 调用契约只有打到真实 gemini-2.5-flash + 真实签名 URL 直拉私密图才算端到端验收通过。状态机/重试/幂等的可靠性可在 L1 用打桩 Gemini 验证。

### AC1 — 提交分诊（异步受理 + 建表 + DB 状态机入队）

**Given** 客户端已 STS 直传分诊图至私密桶
**When** 调用 `POST /api/v1/triage`
**Then** 返回 202 + `triageId`，创建 `triage_tasks` 记录（本故事创建该表，status=PENDING + retry_count，danger_level 待定，JSONB 存原始响应）（FR-1 后端）
**And** `@Async` 监听领域事件，置 PROCESSING → 调 Gemini Developer API（gemini-2.5-flash，签名 URL 直拉私密图）→ 解析绿/黄/红 → 写库 status=DONE
> 验证层：建表/202 受理/状态迁移 PENDING→PROCESSING→DONE/幂等去重 = **L1**（postgres + redis + 打桩 Gemini 即可验状态机）；真实调 gemini-2.5-flash + 签名 URL 直拉私密图解析出绿/黄/红 = **L2**（需真实 Gemini key + 真实私密桶签名 URL）。

### AC2 — 短轮询取结果（处理中 / 结构化结果）与 ≤15s SLA

**Given** 客户端
**When** 短轮询 `GET /api/v1/triage/{id}`
**Then** 在结果就绪前返回处理中、就绪后返回结构化结果；提交后 ≤15s 返回（NFR-1）
> 验证层：处理中/就绪两态响应契约 + 越权（非本人 task 取结果）403 = **L1**（MockMvc + 打桩 Gemini 即时返回可验两态与契约）；端到端 ≤15s SLA（真实 Gemini 往返 + 印尼↔德国延迟）= **L2**（真实 key + 真机网络计时）。

### AC3 — 失败重试、启动重扫与降级落 FAILED

**Given** Gemini 超时或异常
**When** 任务失败
**Then** DB 状态机驱动重试 ≤3 次、应用启动时重扫未完成任务；最终失败置 FAILED 供前端降级（NFR-10）
> 验证层：**L1**（打桩 Gemini 抛超时/5xx → 验 retry_count 递增至上限、超过 3 次置 FAILED；杀进程留 PROCESSING 残留任务 → 重启 `TriageTaskScanner` 重扫并续跑 / 置 FAILED）。真实 Gemini 偶发超时触发重试路径可在 **L2** 观察但不强制（不可控触发）。

---

## Tasks / Subtasks

> **按"后端子任务 / 前端子任务 / 联调验收"三段组织**。本 Story **后端重**（分诊异步基建 + Gemini 接入几乎全在后端）；前端仅做"能驱动契约验收"的最小薄客户端（真正的入口/上传/结果 UI 在 4.3/4.4/4.5）。建议执行顺序：后端 → 前端薄客户端 → 联调（含 L2 真实 Gemini）。

### 🟦 后端子任务（petgo-backend / triage 模块 + shared/ai）

- [ ] **B1. 建 `triage_tasks` 表（Flyway）** (AC: 1, 3)
  - [ ] `db/migration/V<序号>__init_triage.sql`（命名严格 `V<序号>__<描述>.sql`，序号接当前最大值）。
  - [ ] 字段（全 snake_case / UTC `timestamptz` / 枚举 varchar+UPPER_SNAKE）：`id`(bigint PK)、`user_id`(fk)、`pet_id`(nullable fk，存档绑定见 4.4/2.5)、`status`(varchar ∈ `PENDING/PROCESSING/DONE/FAILED`)、`danger_level`(varchar ∈ `GREEN/YELLOW/RED`，nullable 待定)、`symptom_text`(text)、`image_object_keys`(JSONB 或 text[]，私密桶对象 key 列表 ≤3)、`gemini_raw`(JSONB，Gemini 原始响应)、`parsed_result`(JSONB，解析后绿/黄/红 + 观察建议 + 用药参考结构)、`retry_count`(int default 0)、`idempotency_key`(varchar)、`created_at`/`updated_at`。
  - [ ] 索引：`idx_triage_tasks_user_id`、`idx_triage_tasks_status`（供启动重扫扫 PENDING/PROCESSING）；唯一 `uq_triage_tasks_idempotency_key`（幂等去重）。
  - [ ] ⚠️ **存私密桶对象 key（不可枚举），不存签名 URL**（签名 URL 会过期且属敏感，绝不入库、不落日志）。
- [ ] **B2. 领域实体 / DTO / 仓储** (AC: 1, 2)
  - [ ] `triage/domain/TriageTask`(JPA `@Entity`)、`TriageStatus`/`DangerLevel` 枚举（复用 `shared/async` TaskStatus 语义但 triage 自有 `danger_level`）。
  - [ ] `triage/repository/TriageTaskRepository`（含 `findByStatusIn(PENDING,PROCESSING)` 供重扫、`findByIdempotencyKey`）。
  - [ ] DTO（record，camelCase）：`TriageSubmitRequest`{symptomText, imageObjectKeys(≤3), petId?}、`TriageAcceptedResponse`{triageId, status}、`TriageResultResponse`{status, dangerLevel?, advice?, medicationRef?, disclaimer?}（结构留足 4.4 三态展示字段，处理中态只回 status）。
- [ ] **B3. `POST /api/v1/triage` 受理端点** (AC: 1)
  - [ ] `triage/web/TriageController#submit`：Bean Validation（imageObjectKeys ≤3、symptomText 非空且有上限）；读 `Idempotency-Key` 头 → 命中已存在 task 直接回原 triageId（幂等，不重复入队）。
  - [ ] 写 `triage_tasks`(status=PENDING) → 发 `TriageSubmittedEvent`(不可变 record，过去式) → **返回 `202` + `{triageId}`**（HTTP 语义：202 异步受理）。
  - [ ] 鉴权：需 `role=user` JWT（游客 401）；`user_id` 取自 JWT，不信客户端传值。
- [ ] **B4. `@Async` 分诊处理器（状态机驱动）** (AC: 1, 3)
  - [ ] `triage/service/TriageService`：`@Async @EventListener(TriageSubmittedEvent)` → 置 `PROCESSING` → 为每张图向 `shared/media/SignedUrlService` 取**短 TTL 签名 URL** → 调 `GeminiClient` → 解析 → 写 `gemini_raw`(JSONB 原始) + `parsed_result`(JSONB 解析) + `danger_level` → 置 `DONE`。
  - [ ] **⚠️ 为 Story 4.2 预留挂载点**：在"Gemini 返回解析后、写库 DONE 前"留出后置校验调用位（4.2 的 `SafetyRuleLayer.enforce(...)` 插于此；本 Story 先放一个 no-op 占位或直接预留方法签名，注释标注"4.2 在此强制升红，只升不降"）。
  - [ ] 异常处理：Gemini 超时/异常 → 不置 DONE，置回 PENDING 并 `retry_count++`（或单独 RETRY 语义）；`retry_count > 3` 置 `FAILED`。失败路径**不外泄堆栈、不落健康数据/签名 URL 到日志**。
- [ ] **B5. `GeminiClient`（shared/ai，接口抽象便于迁 Vertex）** (AC: 1, 2)
  - [ ] `shared/ai/GeminiClient`：Developer API、模型 `gemini-2.5-flash`；入参=症状文字 + 图片**签名 URL 列表（直拉私密图，不三角绕行、不经后端下载再上传）**；出参=结构化绿/黄/红 + 观察建议 + 用药参考（用 responseSchema / 结构化输出约束模型回 JSON）。
  - [ ] key 走 env 注入（`GEMINI_API_KEY`），**绝不入库/不入日志**；接口抽象保留以便未来迁 Vertex（架构 G-3 挂账）。
  - [ ] 超时设置（贴合 ≤15s SLA，预留重试余量，如单次 ~8-10s 超时）；超时/非 2xx 抛可重试异常交 B4 状态机。
  - [ ] L1 用打桩实现（`@Profile` 或测试替身）即时返回固定 JSON，使状态机/重试可在无外部凭证下验证。
- [ ] **B6. `GET /api/v1/triage/{id}` 短轮询取结果** (AC: 2)
  - [ ] `triage/web/TriageController#get`：按 id 取 task；**鉴权 = 仅 task 所属 `user_id` 可读，越权返 403 ProblemDetail**（不暴露 task 是否存在差异，防枚举：他人 task 与不存在 task 文案不区分，参 UX-DR18 ④）。
  - [ ] status ∈ {PENDING,PROCESSING} → 回处理中态（仅 status，HTTP 200）；DONE → 回 `TriageResultResponse` 完整结构；FAILED → 回 status=FAILED 供前端降级（4.3 重试 UI）。
  - [ ] 404（id 不存在）/403（越权）走统一 ProblemDetail。
- [ ] **B7. `TriageTaskScanner` 启动重扫 + 重试调度** (AC: 3)
  - [ ] `triage/service/TriageTaskScanner`：应用启动（`ApplicationReadyEvent`）扫 status ∈ {PENDING,PROCESSING} 残留任务 → 重新触发处理（崩溃/重启不丢任务）；可选轻量 `@Scheduled` 周期重扫 stuck 任务（**仅 DB 状态机，禁引入 MQ/定时中间件**）。
  - [ ] 重试上限 ≤3（与 B4 一致）；超限置 FAILED。
- [ ] **B8. OpenAPI 契约** (AC: 1, 2)
  - [ ] springdoc 暴露 `POST /api/v1/triage`(202)、`GET /api/v1/triage/{id}` 于 `/v3/api-docs`，含三态响应 schema，供 4.3/4.4 前端对齐。

### 🟩 前端子任务（petgo_app / features/triage —— 仅最小薄客户端，UI 在 4.3/4.4/4.5）

- [ ] **F1. triage data 层：契约接入** (AC: 1, 2)
  - [ ] `features/triage/data`：`TriageRepository`（dio）实现 `submitTriage(req)`→triageId（带 `Idempotency-Key`）、`pollTriage(id)`→结果/处理中三态映射。DTO 对齐 B2。
  - [ ] ProblemDetail（403/404/429）→ 本地化错误经 `core/network` 统一映射（沿用 1.1 约定）。
- [ ] **F2. triage domain 层：轮询用例与状态** (AC: 2, 3)
  - [ ] `features/triage/domain`：`triageResultProvider`（Riverpod，`AsyncValue<TriageResultState>`）封装"提交→短轮询直到 DONE/FAILED/超时"；轮询间隔与 ≤15s 总超时（超时映射 4.3 的「分析时间较长」降级，FAILED 映射「AI 服务暂时不可用」降级——具体 UI 文案在 4.3）。
  - [ ] 状态不可变（copyWith），副作用进 provider 不写 widget build。
  - [ ] ⚠️ 本 Story 只需"能驱动契约跑通"的最小 provider + 一个临时调试页/测试触发，**真正的上传页/spinner/三态卡/红色半屏在 4.3/4.4/4.5**。

### 🟨 联调验收子任务（端到端 + L2 真实 Gemini）

- [ ] **J1. L1 状态机与可靠性验收（打桩 Gemini）** (AC: 1, 2, 3 / **L1**)
  - [ ] Docker 起 postgres+redis → `POST /triage` 验 202+triageId、`triage_tasks` 落 PENDING；打桩 Gemini 返回固定 JSON → 验状态 PROCESSING→DONE、`gemini_raw`/`parsed_result` 落 JSONB、`danger_level` 写入。
  - [ ] 幂等：同 `Idempotency-Key` 重复 POST → 同一 triageId、不重复入队（`uq_triage_tasks_idempotency_key`）。
  - [ ] 重试：打桩 Gemini 抛超时/5xx → 验 `retry_count` 递增、>3 置 FAILED；杀进程留 PROCESSING 残留 → 重启 `TriageTaskScanner` 续跑。
  - [ ] 越权：用户 B 取用户 A 的 `GET /triage/{id}` → 403 ProblemDetail（与"不存在"文案不可区分）。
- [ ] **J2. L2 真实 Gemini 端到端** (AC: 1, 2 / **L2**)
  - [ ] 配置真实 `GEMINI_API_KEY` + 真实私密桶签名 URL（复用 2.1）→ 提交一组真实症状文字 + 私密桶图 → 验 gemini-2.5-flash 经签名 URL 直拉到图、解析出绿/黄/红结构化结果、写 DONE。
  - [ ] 计时端到端提交→结果 ≤15s（真机/模拟器 + 印尼↔德国网络路径），记录实际耗时于 Completion Notes。
  - [ ] 验日志/对象存储 URL **不落** 症状健康数据 / 签名 URL / Gemini key（抽查 logback JSON 输出）。
- [ ] **J3. 契约对齐确认**
  - [ ] `/v3/api-docs` 含 triage 两端点三态 schema；前端 DTO 与之一致，供 4.3/4.4 直接消费。

---

## Dev Notes

### 关键架构约定（本 Story 必须落实）

**分诊数据流（架构 §Integration Points 数据流-1，权威）**：
Flutter 压缩图 → STS 直传②私密桶 →（本 Story 起）`POST /triage`(202+id) → `TriageSubmittedEvent` → `@Async` 调 Gemini（**签名 URL 直拉图**）→ 解析绿/黄/红 → **SafetyRuleLayer 后置强制升红（Story 4.2 接入）** → 写库 status=DONE → 客户端轮询/IM 推送取结果。本 Story 负责除"SafetyRuleLayer"外的全链路，并在解析后写库前**预留其挂载点**。

**异步统一 DB 状态机（架构 §API & Communication / §Decision Critical）**：`status` PENDING→PROCESSING→DONE/FAILED + `retry_count` + 启动重扫；进程内 `ApplicationEventPublisher` 发 `TriageSubmittedEvent` → `@Async @EventListener` 消费。**分诊异步契约**：`POST /api/v1/triage`→202+triageId，客户端短轮询 `GET /api/v1/triage/{id}` 或收 IM 推送。

**命名映射链**：DB snake_case ↔ Java/Dart camelCase ↔ JSON camelCase。表 `triage_tasks`、列 `danger_level`/`retry_count`/`created_at`(UTC)、枚举 varchar+UPPER_SNAKE（`PENDING/PROCESSING/DONE/FAILED`、`GREEN/YELLOW/RED`）、JSON `{"dangerLevel":"YELLOW","createdAt":"...Z"}`、实体 `TriageTask`、DTO `TriageResultResponse`、provider `triageResultProvider`。Flyway `V<序号>__init_triage.sql`。

**Gemini 接入（架构 §External / Core Decisions）**：Google Gemini **Developer API**、模型 **gemini-2.5-flash**、**签名 URL 直拉私密图**；接口抽象 `shared/ai/GeminiClient` 便于未来迁 Vertex（G-3 数据出境挂账）。JSONB 存 Gemini 原始响应（`gemini_raw`）+ 解析后字段（`parsed_result`）。

**HTTP 语义**：提交 = **202 异步受理**；取结果 = 200（含处理中/就绪两态）；越权 = 403；不存在 = 404；限流 = 429（写端点 Redis 令牌桶 + Idempotency-Key，NFR-12）。

### 🔒 安全攸关约束（不可协商）

> 本 Story 不直接实现安全规则层，但它是分诊安全链路的**承载基座**，以下约束为 4.2 强制升红铺设不可绕过的地基。dev agent 实现 B4/B5 时 MUST 遵守，违反即返工。

- **为 SafetyRuleLayer 预留唯一后置挂载点**：强制升红只能发生在「Gemini 返回解析后、写库 DONE 前」这一处。B4 必须把这一步做成**单一、显式、不可被旁路**的调用位（4.2 在此插 `SafetyRuleLayer.enforce`）。**禁止**在多个地方分散决定 danger_level，防止 4.2 接入后存在绕过路径。
- **danger_level 是后置校验的产物，不是模型的最终裁决**：B4 写入的 `danger_level` 仅为"模型解析值"，必须可被后置层覆盖（只升不降）。**严禁**把"模型说绿就直接对外当绿"的快路径暴露给 `GET /triage/{id}`——取结果一律读经过（未来）后置校验后落库的最终值。
- **私密图只走签名 URL 直拉**：Gemini 拉图只能用 `shared/media` 发放的**短 TTL 签名 URL**；签名 URL 绝不入库（`triage_tasks` 只存对象 key）、绝不落日志。
- **分诊 ≤15s（NFR-1）**：超时由 DB 状态机重试 ≤3 兜底（NFR-10），不得让用户无限等待；超时/失败置 FAILED 供 4.3 降级（重试复用上次提交内容 + 软引导兽医）。
- **日志不落健康数据**：症状文字、图片内容、解析结果、Gemini key、签名 URL 一律不入 logback JSON（MDC 只带 traceId/userId）。
- **免责声明前置可留证（NFR-9）**：本 Story 输出契约的 `TriageResultResponse` 预留 `disclaimer` 字段位（实际前置展示在 4.4），结果数据流设计不得遗漏免责声明承载。

### 强制护栏（架构 §Enforcement —— 违反即返工）

- 异步**只用 `@Async` + DB 状态机**——`PENDING/PROCESSING/DONE/FAILED` + `retry_count` + 启动重扫（`TriageTaskScanner`）。**禁止引入 MQ / Kafka / RabbitMQ / Redis Stream / 通用缓存层 / 新中间件**（违背 V1 轻量姿态）。
- 私密图只走签名 URL 直拉；日志/对象存储 URL **不落 PII/健康数据/令牌/签名 URL**。
- 写端点（`POST /triage`）走 Redis 令牌桶限流 + `Idempotency-Key` 去重（NFR-12）。
- `ddl-auto=validate`，schema 归 Flyway；新增对外暴露标识用不可枚举 token（本 Story `triageId` 仅授权本人可读，非公开枚举入口）。
- 凭证（Gemini key）全部 env 注入，绝不入库。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不做 SafetyRuleLayer 强制升红（**Story 4.2**）——只预留挂载点。
- ❌ 不做问诊 Tab 双入口卡 / 上传页 / 权限申请 / 等待 spinner / 超时重试 UI（**Story 4.3**）。
- ❌ 不做绿/黄结果三态卡 / 黄色条件倒计时协议块 / 免责声明前置展示（**Story 4.4**）。
- ❌ 不做红色半屏强提醒 overlay / 导航 / 无障碍播报（**Story 4.5**）。
- ❌ 不做存档入库（FR-16，**Story 2.5/4.4** 承接）——`triage_tasks.pet_id` 字段预留但本 Story 不实现存档流。
- ❌ 不做 IM 推送完成通知（Epic 6）——本 Story 客户端只用短轮询取结果。
- ✅ 只做：`triage_tasks` 建表 + 异步状态机 + `POST/GET /triage` 契约 + `GeminiClient`(真实接入) + 重试/重扫/幂等 + 后置校验挂载点预留 + 最小薄客户端驱动联调。

### Project Structure Notes

- 后端：`com.tailtopia.triage.{web,service,domain,repository,dto,event}` + `triage/service/{TriageService, SafetyRuleLayer(4.2 填充), TriageTaskScanner}` + `shared/ai/GeminiClient` + `resources/safety/high_risk_symptoms.yml`（**4.2 填充，本 Story 仅确保 `resources/safety/` 目录存在**）。
- 前端：`lib/features/triage/{data,domain,presentation}`（本 Story 仅 data + domain 薄层；presentation 的 `shared/widgets/{triage_result_card, red_alert_overlay}` 在 4.4/4.5 实现）。
- 模块边界：triage 模块只经自身 service / 领域事件通信，禁跨模块直接访问对方 repository；`shared/ai`、`shared/media` 为跨模块基础设施。

### References

- [Source: epics.md#Story 4.1] — 三条原始 AC（Given/When/Then）。
- [Source: epics.md#Epic 4] — Epic 目标与 4.1~4.5 定位（4.1 后端地基，安全层在 4.2）。
- [Source: architecture.md#API & Communication Patterns] — 分诊异步契约 `POST /triage`→202+triageId / 短轮询 / DB 状态机驱动。
- [Source: architecture.md#Data Architecture] — JSONB 存 Gemini 原始响应；私密桶签名 URL；`triage_tasks` 命名。
- [Source: architecture.md#External / Integration Points 数据流-1] — Gemini Developer API gemini-2.5-flash 签名 URL 直拉私密图；`shared/ai/GeminiClient` 抽象（迁 Vertex）；分诊数据流五段（含 SafetyRuleLayer 后置）。
- [Source: architecture.md#不可协商地基 / Enforcement] — 安全规则层后置只升不降的承载约束；禁 MQ；日志不落健康数据/签名 URL。
- [Source: architecture.md#Authentication & Security] — 私密图签名 URL 直拉；写端点限流 + Idempotency-Key。
- [Source: PRD FR-1 / NFR-1 / NFR-10] — ≤15s 三项输出；超时重试 DB 状态机 ≤3 + 降级。
- [Memory: story-acceptance-layering] — 前后端分段 + AC 标 L0/L1/L2。
- [Memory: v1-architecture-posture] — 禁 MQ、DB 状态机、不过度设计。

## Dev Agent Record

### Agent Model Used

Claude（云端 headless dev agent）

### Debug Log References

- `./mvnw -B compile` → 绿。
- `./mvnw -B -Dtest='TriageServiceTest,TriageProcessorTest,TriageControllerTest,DangerLevelTest,StubGeminiClientTest' test` → 绿（重试日志可见 retryCount 1→4 后置 FAILED）。
- `./mvnw -B -DskipTests package` → 绿。
- `flutter analyze` → No issues found.
- `flutter test`（全量 122 用例，含 triage 8 用例）→ All tests passed.

### Completion Notes List

**已实现（L0 绿）：**
- ✅ AC1 — `triage_tasks` 建表（Flyway **V12**，接 main 已用到的 V11 之后单调分配，决策 E2）；`POST /api/v1/triage` **202 异步受理** + `triageId`；`status=PENDING` 入库后发 `TriageSubmittedEvent`；`@Async @TransactionalEventListener(AFTER_COMMIT)` 驱动 `TriageProcessor` 置 PROCESSING→调 GeminiClient→后置裁决→写 `gemini_raw`/`parsed_result`/`danger_level`→DONE。
- ✅ AC1 幂等 — `Idempotency-Key` 头命中既有任务回原 `triageId`，不重复入队（DB 唯一约束 `uq_triage_tasks_idempotency_key` 兜底）。
- ✅ AC2 — `GET /api/v1/triage/{id}` 短轮询：处理中仅回 `status`，DONE 回完整结构（级别+建议+用药参考+免责声明），FAILED 供降级。**鉴权防枚举**：他人 task 与不存在 task 均返**同一** 403 ProblemDetail（文案/状态码不可区分）。
- ✅ AC3 — 失败重试：超时/异常 → `retry_count++`，>3 置 FAILED；`TriageTaskScanner` 在 `ApplicationReadyEvent` 异步重扫 status∈{PENDING,PROCESSING} 残留任务续跑（崩溃/重启不丢任务）。**全 DB 状态机，禁 MQ**。
- ✅ 🔒 安全攸关挂载点 — `SafetyRuleLayer.enforce(...)` 为**唯一、显式、不可旁路**的后置升红挂载点（4.1 为 no-op 占位，**Story 4.2 填充**）；插在「Gemini 返回解析后、写库 DONE 前」一处；`DangerLevel.atLeast` 提供「只升不降」原语；取结果一律读经后置裁决后落库的最终值（无「模型说绿就当绿」快路径）。
- ✅ Gemini 接入 — `shared/ai/GeminiClient` 接口抽象（便于迁 Vertex，G-3）；`mode=live` 装配 `GeminiDeveloperApiClient`（RestClient 打 gemini-2.5-flash、结构化输出 responseSchema、`fileData.fileUri` 签名 URL 直拉私密图、key 经 `x-goog-api-key` 头）；`mode=stub`（默认）装配 `StubGeminiClient` 使状态机无凭证可 L0/L1 验。
- ✅ 前端薄客户端 — `TriageRepository`（submit 带 Idempotency-Key / poll 三态映射）+ `TriageController`（submit→短轮询直到 DONE/FAILED/超时，15s SLA）+ `/dev/triage` 调试页（仅手动深链，验收后可移除）。**真正的上传页/spinner/三态卡/红色半屏在 4.3/4.4/4.5**。
- ✅ 护栏 — 私密图只存对象 key（不存签名 URL）；失败日志只记异常类名，不落症状/图片/签名 URL/解析结果/Gemini key；`ddl-auto=validate` schema 归 Flyway；写端点 Redis 令牌桶限流；key env 注入不入库。

**待本地验收：**
- ⏳ **L1（需 Docker postgres+redis + 打桩 Gemini，`GEMINI_MODE=stub`）**：
  - J1 状态机：`POST /triage`→202+triageId、`triage_tasks` 落 PENDING；打桩返回固定 JSON→PROCESSING→DONE、`gemini_raw`/`parsed_result`/`danger_level` 落 JSONB。
  - J1 幂等：同 `Idempotency-Key` 重复 POST → 同一 triageId、不重复入队。
  - J1 重试/重扫：打桩抛超时/5xx→`retry_count` 递增、>3 置 FAILED；杀进程留 PROCESSING 残留→重启 `TriageTaskScanner` 续跑。
  - J1 越权：用户 B 取用户 A 的 `GET /triage/{id}`→403（与「不存在」不可区分）。
- ⏳ **L2（需真实 `GEMINI_API_KEY` + 真实私密桶签名 URL，`GEMINI_MODE=live`，真机/印尼↔德国网络）**：
  - J2 端到端：提交真实症状文字 + 私密桶图 → 验 gemini-2.5-flash 经签名 URL 直拉到图、解析出绿/黄/红、写 DONE。
  - J2 计时：提交→结果 ≤15s（NFR-1），实测耗时回填本节。
  - J2 抽查 logback JSON：不落症状健康数据 / 签名 URL / Gemini key。
  - ⚠️ **Gemini 图片摄取机制需 L2 核实**：当前按架构「签名 URL 直拉」用 `fileData.fileUri` 传签名 URL；若真实 Developer API 对任意签名 URL 的 `fileUri` 支持受限（历史上 `fileUri` 主要接 File API / Google 托管资源），需在 L2 调整为 File API 上传或 `inlineData` base64，`GeminiClient` 抽象已为此预留可调整点。
- ⏳ Gemini 模型/SB/Java 版本：用 gemini-2.5-flash（别名，若执行时有更新以实际为准勿降级）；Spring Boot 4.0.6 / Java 21（与基线一致）。

### File List

**新增（后端 shared/ai）：**
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/GeminiClient.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/GeminiTriageResult.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/GeminiException.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/GeminiProperties.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/GeminiDeveloperApiClient.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/StubGeminiClient.java`
- `petgo-backend/src/main/java/com/tailtopia/shared/ai/AiConfig.java`

**新增（后端 triage 模块）：**
- `petgo-backend/src/main/java/com/tailtopia/triage/domain/TriageStatus.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/domain/DangerLevel.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/domain/TriageTask.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/repository/TriageTaskRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/dto/TriageSubmitRequest.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/dto/TriageAcceptedResponse.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/dto/TriageResultResponse.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/event/TriageSubmittedEvent.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/service/SafetyRuleLayer.java`（4.2 填充挂载点）
- `petgo-backend/src/main/java/com/tailtopia/triage/service/TriageService.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/service/TriageProcessor.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/service/TriageEventListener.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/service/TriageTaskScanner.java`
- `petgo-backend/src/main/java/com/tailtopia/triage/web/TriageController.java`
- `petgo-backend/src/main/resources/db/migration/V12__init_triage.sql`

**新增（后端测试 L0）：**
- `petgo-backend/src/test/java/com/tailtopia/triage/TriageTestSupport.java`
- `petgo-backend/src/test/java/com/tailtopia/triage/service/TriageServiceTest.java`
- `petgo-backend/src/test/java/com/tailtopia/triage/service/TriageProcessorTest.java`
- `petgo-backend/src/test/java/com/tailtopia/triage/web/TriageControllerTest.java`
- `petgo-backend/src/test/java/com/tailtopia/triage/domain/DangerLevelTest.java`
- `petgo-backend/src/test/java/com/tailtopia/shared/ai/StubGeminiClientTest.java`

**新增（前端 triage 薄客户端）：**
- `petgo_app/lib/features/triage/data/triage_repository.dart`
- `petgo_app/lib/features/triage/domain/triage_result_state.dart`
- `petgo_app/lib/features/triage/domain/triage_result_controller.dart`
- `petgo_app/lib/features/triage/presentation/dev_triage_page.dart`
- `petgo_app/test/triage/triage_repository_test.dart`
- `petgo_app/test/triage/triage_controller_test.dart`

**修改：**
- `petgo-backend/src/main/resources/application.yml`（新增 `petgo.ai.gemini` 配置块）
- `petgo-backend/.env.example`（新增 `GEMINI_MODE`/`GEMINI_API_KEY` 等占位）
- `petgo_app/lib/core/network/api_paths.dart`（新增 triage 路径）
- `petgo_app/lib/core/router/app_router.dart`（新增 `/dev/triage` 调试路由）
