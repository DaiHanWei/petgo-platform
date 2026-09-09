# Epic 4：AI 智能分诊 — 人工测试流程文档

> **文档版本**：2026-06-10 · 基于 Story 4.1–4.5（均已 L0 绿，含 R2 回改）  
> **执行环境说明**：云端/本地 L0 可随时跑；L1 需本地 Docker（postgres+redis）；L2 需真机 + 真实 `GEMINI_API_KEY` + STS 凭证，生命安全面 L2 为**强制**。  
> **QA 执行前提**：Epic 1–3 基础功能（脚手架/认证/媒体基建）已通过验收。

---

## 范围与页面/路由清单

| 路由 | 页面/文件 | 所属 Story |
|---|---|---|
| `/triage`（问诊 Tab） | `triage_page.dart` | 4.3 |
| `/triage/upload` | `triage_upload_page.dart` | 4.3 |
| `/triage/upload`（DONE → 绿/黄） | `triage_result_view.dart` | 4.4 |
| `/triage/upload`（DONE → 红） | `triage_red_result.dart` + `red_alert_overlay.dart` | 4.5 |
| `/dev/triage`（开发调试页） | `dev_triage_page.dart` | 4.1 |
| 后端 `POST /api/v1/triage` | `TriageController` | 4.1 |
| 后端 `GET /api/v1/triage/{id}` | `TriageController` | 4.1 |

### ⚠️ 安全攸关声明

本 Epic 含 **TailTopia 第一根生命安全支柱（NFR-6）**。以下三类测试具有最高优先级，任一失败即构成**阻塞性缺陷（Blocker）**，必须在任何发布前修复：

1. **高危词强制升红**：命中 `high_risk_symptoms.yml` 清单 → 后端必落 `danger_level=RED`，无论 AI 模型返回何值。
2. **否定语境保守处理（E5）**：紧邻否定可抑制单次命中；但全文存在任何其他未否定命中，必须升红；存疑即升红，绝不漏真实急症。
3. **红色态零变现/零兽医入口/去导航（F3）**：红色 overlay 及关闭后的结果摘要页，绝对不出现兽医咨询入口、广告、付费、引流、地图导航、医院推荐。唯一出口是「我已知晓 / Saya mengerti」按钮。

---

## 4.1 分诊异步基建与 Gemini 接入

### 4.1.A 提交分诊（异步受理 + DB 状态机）

#### TC-4.101 首次提交分诊（202 受理 + PENDING 落库）

- **关联**：Story 4.1 · AC1 · B3
- **页面/入口**：`POST /api/v1/triage`
- **前置**：已登录用户；Docker postgres+redis 启动；`GEMINI_MODE=stub`；至少 1 张图已 STS 直传私密桶得对象 key
- **步骤**：
  1. 以有效 JWT 调 `POST /api/v1/triage`，body：`{"symptomText":"犬呕吐","imageObjectKeys":["pet/img001.jpg"]}`，Header：`Idempotency-Key: test-idem-001`
  2. 检查响应 HTTP 状态码
  3. 检查响应 body
  4. 查询数据库 `triage_tasks` 表
- **预期**：HTTP 202；body 含 `triageId`（整数）；DB 中对应记录 `status='PENDING'`，`retry_count=0`，`user_id` 与 JWT 一致，`symptom_text='犬呕吐'`
- **层级**：L1

#### TC-4.102 相同 Idempotency-Key 重复提交（幂等去重）

- **关联**：Story 4.1 · AC1 · B3（幂等约束）
- **页面/入口**：`POST /api/v1/triage`
- **前置**：TC-4.101 已成功执行，记录原 `triageId`；`Idempotency-Key: test-idem-001`
- **步骤**：
  1. 用**完全相同的** `Idempotency-Key: test-idem-001` 再次调 `POST /api/v1/triage`（内容可稍有不同）
  2. 对比两次响应中的 `triageId`
  3. 查询 `triage_tasks` 记录数量
- **预期**：第二次也返回 202 且 `triageId` 与第一次**完全相同**；DB 中无新增 `triage_tasks` 记录；不重复入队
- **层级**：L1

#### TC-4.103 游客（无 JWT）提交分诊（401 拒绝）

- **关联**：Story 4.1 · AC1 · B3（鉴权）
- **页面/入口**：`POST /api/v1/triage`
- **前置**：无 Authorization 头
- **步骤**：
  1. 不携带 JWT，调 `POST /api/v1/triage`
- **预期**：HTTP 401；body 为 RFC 9457 ProblemDetail 格式；无 `triageId`；DB 无记录创建
- **层级**：L1

#### TC-4.104 异步状态机流转（PENDING → PROCESSING → DONE）

- **关联**：Story 4.1 · AC1 · AC2 · B4（状态机）
- **页面/入口**：`POST /api/v1/triage` + `GET /api/v1/triage/{id}`
- **前置**：Docker postgres+redis；`GEMINI_MODE=stub`（打桩即时返回固定 GREEN 结果）
- **步骤**：
  1. 提交分诊，记录 `triageId`
  2. 立即轮询 `GET /api/v1/triage/{triageId}`（在 PROCESSING 阶段内）
  3. 等待约 1–2 秒后再次轮询
  4. 查询 DB `triage_tasks` 最终状态
- **预期**：第一次轮询返回 `{"status":"PROCESSING"}` 或 `{"status":"PENDING"}`（仅 status 字段，HTTP 200）；最终轮询返回 `{"status":"DONE","dangerLevel":"GREEN",...}`（含 advice/disclaimer 等字段）；DB `status='DONE'`，`danger_level='GREEN'`，`gemini_raw` 与 `parsed_result` 均非空 JSONB
- **层级**：L1

#### TC-4.105 越权访问他人分诊任务（403 防枚举）

- **关联**：Story 4.1 · AC2 · B6（越权防枚举）
- **页面/入口**：`GET /api/v1/triage/{id}`
- **前置**：用户 A 已提交分诊得 `triageId_A`；用户 B 的 JWT
- **步骤**：
  1. 用户 B 携带自己的 JWT，访问 `GET /api/v1/triage/{triageId_A}`
- **预期**：HTTP 403；body 为 ProblemDetail；**文案与"不存在"的 404 不可区分**（防枚举：他人 task 与不存在 task 响应语义不应泄露差别）
- **层级**：L1

#### TC-4.106 访问不存在的分诊 ID（403 防枚举）

- **关联**：Story 4.1 · AC2 · B6（防枚举）
- **页面/入口**：`GET /api/v1/triage/{id}`
- **前置**：有效用户 JWT；`triageId=9999999`（不存在）
- **步骤**：
  1. 用有效 JWT 访问 `GET /api/v1/triage/9999999`
- **预期**：HTTP 403（或 404）；body 为 ProblemDetail；**与 TC-4.105 越权场景的响应文案/状态码不可区分**（防枚举设计）
- **层级**：L1

### 4.1.B 失败重试与启动重扫

#### TC-4.107 Gemini 超时触发重试计数

- **关联**：Story 4.1 · AC3 · B4/B7（重试状态机）
- **页面/入口**：后端 triage 服务（内部验证）
- **前置**：Docker postgres+redis；配置打桩 Gemini **抛超时异常**
- **步骤**：
  1. 提交分诊，记录 `triageId`
  2. 每隔 500ms 查询 DB `triage_tasks.retry_count`，观察递增
  3. 等待 `retry_count > 3`
  4. 轮询 `GET /api/v1/triage/{triageId}`
- **预期**：`retry_count` 从 0 依次递增到 4（或具体实现上限）；超过 3 次后 `status='FAILED'`；API 轮询返回 `{"status":"FAILED"}`；日志**不含**症状健康数据、签名 URL、Gemini key
- **层级**：L1

#### TC-4.108 应用重启后 TriageTaskScanner 重扫残留任务

- **关联**：Story 4.1 · AC3 · B7（启动重扫）
- **页面/入口**：后端 TriageTaskScanner
- **前置**：Docker postgres+redis；在 PROCESSING 状态时**强制杀死后端进程**，留下残留任务
- **步骤**：
  1. 提交分诊，等 `status='PROCESSING'` 时 kill 后端进程
  2. 查询 DB 确认 `status='PROCESSING'` 残留
  3. 重启后端（`mvn spring-boot:run`）
  4. 等待约 5 秒后查询 DB
- **预期**：重启后 `TriageTaskScanner` 在 `ApplicationReadyEvent` 触发重扫；残留任务被续跑（`status` 最终到达 `DONE` 或 `FAILED`）；不会永久卡在 `PROCESSING`
- **层级**：L1

### 4.1.C 后端契约与日志安全

#### TC-4.109 OpenAPI 契约完整性（/v3/api-docs）

- **关联**：Story 4.1 · B8（OpenAPI）
- **页面/入口**：`GET /v3/api-docs`（springdoc）
- **前置**：后端启动
- **步骤**：
  1. 访问 `GET /v3/api-docs`
  2. 检查 paths 中是否包含 `POST /api/v1/triage` 与 `GET /api/v1/triage/{id}`
  3. 检查 GET 接口的 response schema 是否包含三态（PENDING/PROCESSING/DONE/FAILED）
- **预期**：两个端点均存在；GET 响应 schema 含 `status`、`dangerLevel`（nullable）、`advice`、`disclaimer` 等字段；供 4.3/4.4 前端对齐
- **层级**：L1

#### TC-4.110 日志不落敏感健康数据（L2 抽查）

- **关联**：Story 4.1 · 安全约束（日志审计）
- **页面/入口**：后端 logback JSON 输出
- **前置**：`GEMINI_MODE=live`；真实 Gemini key；完成一次真实分诊（含症状文字 + 私密桶图）
- **步骤**：
  1. 执行一次完整分诊请求，symptomText 包含明确健康描述（如「犬呕吐带血」）
  2. 检查 logback JSON 日志输出
  3. grep 关键词：症状文字原文、图片 URL/对象 key、`GEMINI_API_KEY` 值、签名 URL（含 `X-Goog-Signature` 或 `X-Amz-Signature`）
- **预期**：日志中**不出现**症状文字原文、私密图内容、签名 URL（含任何 presigned URL token）、Gemini key 字面量；MDC 仅含 `traceId`/`userId` 等无 PII 字段
- **层级**：L2

#### TC-4.111 端到端真实 Gemini 调用（≤15s SLA）

- **关联**：Story 4.1 · AC2 · J2（L2 性能验收）
- **页面/入口**：`POST /api/v1/triage` + `GET /api/v1/triage/{id}`
- **前置**：`GEMINI_MODE=live`；真实 `GEMINI_API_KEY`；私密桶签名 URL（来自 Story 2.1）；真机/模拟器 + 印尼↔德国网络
- **步骤**：
  1. 准备一张宠物症状图（已 STS 直传私密桶），记录对象 key
  2. 调 `POST /api/v1/triage`，记录提交时刻 T0
  3. 开始短轮询 `GET /api/v1/triage/{id}`，每 2s 一次
  4. 记录 `status='DONE'` 时刻 T1
  5. 计算 T1 - T0
- **预期**：T1 - T0 ≤ 15s；`dangerLevel` 为 GREEN/YELLOW/RED 之一（结构化 JSON）；`gemini_raw` 非空；验证 Gemini 经签名 URL 直拉私密图（不经后端中转）
- **层级**：L2

---

## 4.2 确定性安全规则层（高危强制升红）

> ⚠️ 本节为**安全攸关**核心，任何失败即 Blocker。

### 4.2.A ⚠️安全 高危词强制升红（SafetyRuleLayer 核心）

#### TC-4.201 ⚠️安全 命中高危清单强制升红（模型给 GREEN）

- **关联**：Story 4.2 · AC1 · AC2 · FR NFR-6 ⚠️安全
- **页面/入口**：后端 `SafetyRuleLayer`（通过 `POST /api/v1/triage` + `GET /api/v1/triage/{id}` 端到端）
- **前置**：Docker postgres+redis；`GEMINI_MODE=stub`（打桩 Gemini 故意返回 `dangerLevel=GREEN`）
- **步骤**：
  1. 提交分诊，`symptomText="犬误食巧克力大约20克"`（命中清单 `chocolate_ingestion`）
  2. 等待 `status=DONE`
  3. 轮询 `GET /api/v1/triage/{id}`，检查 `dangerLevel`
  4. 查询 DB `triage_tasks.danger_level` 和 `parsed_result`
- **预期**：API 返回 `dangerLevel=RED`（不是模型的 GREEN）；DB `danger_level='RED'`；`parsed_result` 中 `escalatedBySafetyRule=true`，`matchedSafetyRuleIds` 含 `chocolate_ingestion`；**前端若已接入，渲染红色半屏而非绿色卡**
- **层级**：L1

#### TC-4.202 ⚠️安全 命中高危清单强制升红（模型给 YELLOW）

- **关联**：Story 4.2 · AC2 · NFR-6 ⚠️安全
- **页面/入口**：后端整链路
- **前置**：Docker postgres+redis；打桩 Gemini 故意返回 `dangerLevel=YELLOW`
- **步骤**：
  1. 提交分诊，`symptomText="猫呼吸困难，呼吸很费力"`（命中 `labored_breathing`）
  2. 等待 `status=DONE`
  3. 检查 `GET /api/v1/triage/{id}` 响应和 DB
- **预期**：`dangerLevel=RED`（YELLOW 被升红）；DB `danger_level='RED'`；`escalatedBySafetyRule=true`；`matchedSafetyRuleIds` 含 `labored_breathing`
- **层级**：L1

#### TC-4.203 ⚠️安全 命中高危清单（模型给 RED，只升不降验证）

- **关联**：Story 4.2 · AC1（只升不降铁律）⚠️安全
- **页面/入口**：后端
- **前置**：打桩 Gemini 返回 `dangerLevel=RED`；`symptomText` 命中高危词
- **步骤**：
  1. 提交命中高危症状的分诊
  2. 检查最终 `dangerLevel`
- **预期**：`dangerLevel` 仍为 `RED`（原值不变，未下调）；`escalatedBySafetyRule` 字段存在（可为 false，因模型已 RED 无需升）
- **层级**：L1

#### TC-4.204 非高危症状不误升红（绿色保持绿色）

- **关联**：Story 4.2 · AC2（不误升，保红色信任）
- **页面/入口**：后端整链路
- **前置**：打桩 Gemini 返回 `dangerLevel=GREEN`；症状为明显非高危
- **步骤**：
  1. 提交分诊，`symptomText="犬轻微打喷嚏，精神食欲正常"`
  2. 等待 DONE
  3. 检查 `dangerLevel`
- **预期**：`dangerLevel=GREEN`（未被误升）；`escalatedBySafetyRule=false` 或字段缺失；DB `danger_level='GREEN'`
- **层级**：L1

#### TC-4.205 ⚠️安全 多语种高危词命中（印尼语）

- **关联**：Story 4.2 · AC1（双语匹配）⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN；symptoms 为印尼语
- **步骤**：
  1. 提交分诊，`symptomText="anjing saya sesak napas dan sulit bernapas"`（命中 `labored_breathing` → `sesak napas`）
  2. 检查结果
- **预期**：`dangerLevel=RED`；印尼语高危词被正确识别命中
- **层级**：L1

#### TC-4.206 ⚠️安全 英语高危词命中

- **关联**：Story 4.2 · AC1（英语匹配）⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN
- **步骤**：
  1. 提交分诊，`symptomText="my dog ate chocolate and grapes"`（命中 `chocolate_ingestion` + `grape_raisin_ingestion` 两条）
  2. 检查结果
- **预期**：`dangerLevel=RED`；`matchedSafetyRuleIds` 含 `chocolate_ingestion` 与 `grape_raisin_ingestion`
- **层级**：L1

### 4.2.B ⚠️安全 E5 否定语境保守处理

#### TC-4.207 ⚠️安全 E5：紧邻否定抑制单次命中（「狗没有呼吸困难」→ 不升红）

- **关联**：Story 4.2 · AC1 · 决策 E5 ⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN；症状文字含紧邻否定
- **步骤**：
  1. 提交分诊，`symptomText="狗没有呼吸困难，精神很好，食欲正常"`
  2. 等待 DONE
  3. 检查 `dangerLevel`
- **预期**：`dangerLevel=GREEN`（否定抑制该次命中，全文无其他高危命中，不升红）；`escalatedBySafetyRule=false`
- **层级**：L1

#### TC-4.208 ⚠️安全 E5：否定仅抑制本次命中，未否定的其他命中仍升红

- **关联**：Story 4.2 · AC1 · 决策 E5（最重要正向测试）⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN
- **步骤**：
  1. 提交分诊，`symptomText="没有呼吸困难，但呕吐带血"`（「呼吸困难」被否定，「呕吐带血」未否定）
  2. 等待 DONE
  3. 检查 `dangerLevel`
- **预期**：`dangerLevel=RED`（「呕吐带血」命中未被否定，必须升红）；`matchedSafetyRuleIds` 含对应呕吐带血条目；**绝不能因「没有呼吸困难」的否定而放过「呕吐带血」**
- **层级**：L1

#### TC-4.209 ⚠️安全 E5：否定不跨子句（逗号断句）

- **关联**：Story 4.2 · 决策 E5（跨句不否定）⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN
- **步骤**：
  1. 提交分诊，`symptomText="没有发烧，今天呕吐带血两次"`（「没有」在逗号前；「呕吐带血」在逗号后另一子句）
  2. 检查 `dangerLevel`
- **预期**：`dangerLevel=RED`（否定不跨逗号边界，「呕吐带血」未被否定，升红）
- **层级**：L1

#### TC-4.210 ⚠️安全 E5：「没吃到巧克力」不升红

- **关联**：Story 4.2 · 决策 E5（否定误食类）⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN
- **步骤**：
  1. 提交分诊，`symptomText="没吃到巧克力，只是舔了一下包装纸"`
  2. 检查 `dangerLevel`
- **预期**：`dangerLevel=GREEN`（「没吃到」紧邻否定「巧克力」，全文无其他高危命中，不升红）
- **层级**：L1

#### TC-4.211 ⚠️安全 E5：印尼语否定词紧邻否定（「tidak sesak napas」→ 不升红）

- **关联**：Story 4.2 · 决策 E5（印尼语否定）⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN
- **步骤**：
  1. 提交分诊，`symptomText="anjingku tidak sesak napas, nafsu makan baik"`
  2. 检查 `dangerLevel`
- **预期**：`dangerLevel=GREEN`（`tidak` 紧邻否定 `sesak napas`，全文无其他命中，不升红）
- **层级**：L1

#### TC-4.212 ⚠️安全 E5：印尼语否定不跨逗号（存在其他未否定命中）

- **关联**：Story 4.2 · 决策 E5 ⚠️安全
- **页面/入口**：后端 SafetyRuleLayer
- **前置**：打桩 Gemini 返回 GREEN
- **步骤**：
  1. 提交分诊，`symptomText="tidak sesak napas, tapi muntah darah"`（「sesak napas」被否定；「muntah darah」对应呕吐带血）
  2. 检查 `dangerLevel`
- **预期**：`dangerLevel=RED`（印尼语「muntah darah」命中呕吐带血条目，未被否定，升红）
- **层级**：L1

### 4.2.C 安全规则层边界态与防回归

#### TC-4.213 ⚠️安全 清单缺失导致 fail-fast（启动失败）

- **关联**：Story 4.2 · AC1 · B2（fail-fast）⚠️安全
- **页面/入口**：后端启动过程
- **前置**：临时将 `resources/safety/high_risk_symptoms.yml` 重命名或清空
- **步骤**：
  1. 尝试启动后端
  2. 检查启动日志
- **预期**：后端**拒绝启动**（fail-fast）；日志明确报告安全清单缺失/为空；`/actuator/health` 不可达；**不能静默放过并以无安全层的状态运行**
- **层级**：L1

#### TC-4.214 ⚠️安全 danger_level 写入路径唯一性（无旁路）

- **关联**：Story 4.2 · B4/B5（防回归不变量）⚠️安全
- **页面/入口**：源代码审查（grep）
- **前置**：本地代码库
- **步骤**：
  1. `grep -rn "danger_level\|dangerLevel" petgo-backend/src/main/java/ | grep -v test | grep -v "\.java:.*//"`
  2. 确认写入 `danger_level` 的最终值只在 `TriageTask.markDone`（或等价单一方法）中发生
  3. 确认所有写入路径都经过 `SafetyRuleLayer.enforce` 的返回值
- **预期**：`danger_level` 最终写入仅一处，且必经 `enforce`；**不存在任何直接写入 model 原始值到 DB 的快路径**
- **层级**：L0

#### TC-4.215 ⚠️安全 L2 真实链路冒烟（挂载点未旁路）

- **关联**：Story 4.2 · J3（L2 冒烟）⚠️安全
- **页面/入口**：后端整链路
- **前置**：`GEMINI_MODE=live`；真实 Gemini key；已知命中清单的真实症状（如「犬误食巧克力 30g 约半小时前」）
- **步骤**：
  1. 提交上述症状 + 宠物图
  2. 等待 DONE
  3. 检查 `dangerLevel` 与 `escalatedBySafetyRule`
- **预期**：即使真实 Gemini 判为绿色，`dangerLevel` 必须为 `RED`，`escalatedBySafetyRule=true`；确认真实异步路径下挂载点未被绕过
- **层级**：L2

---

## 4.3 AI 问诊入口与图文上传

### 4.3.A 问诊 Tab 双入口卡

#### TC-4.301 问诊 Tab 双入口卡布局（平级 + 标题 + 描述）

- **关联**：Story 4.3 · AC1 · F1
- **页面/入口**：`/triage` · `triage_page.dart`
- **前置**：已登录用户；en locale
- **步骤**：
  1. 进入问诊 Tab
  2. 检查页面内容
- **预期**：页面显示「Tanya AI (Triase)」入口卡（`triageEntryAI` key）；页面显示「Chat Dokter Hewan」入口卡（`triageEntryVet` key）；两张卡**平级排列**，视觉权重相当；AI 卡显示「≤ 15 detik」徽章；兽医卡显示在线状态标记
- **层级**：L0

#### TC-4.302 问诊 Tab 双入口卡（id locale 文案核查）

- **关联**：Story 4.3 · AC1 · i18n（双语）
- **页面/入口**：`/triage` · `triage_page.dart`
- **前置**：已登录用户；id locale
- **步骤**：
  1. 切换 App locale 为印尼语（id）
  2. 进入问诊 Tab
  3. 检查关键文案
- **预期**：页面无中文字符渲染；AI 卡标题为「Tanya AI (Triase)」（硬编码）；描述含「Unggah foto gejala」；兽医卡含「Gratis di versi ini」；**App 绝不渲染中文内容给用户**
- **层级**：L0

#### TC-4.303 未登录用户点击 AI 分诊入口触发强登录引导

- **关联**：Story 4.3 · AC1（FR-0C 门控）
- **页面/入口**：`/triage` · `triage_page.dart`
- **前置**：**未登录**用户（游客态）
- **步骤**：
  1. 进入问诊 Tab
  2. 点击「Mulai triase」（`triageEntryAI` 按钮）
- **预期**：未进入 `/triage/upload`；触发强登录引导（弹窗或跳转登录页）；登录后回跳到 `/triage/upload`（`RouteIntent` 机制）
- **层级**：L1

### 4.3.B AI 上传页（图片 + 文字 + 校验）

#### TC-4.304 上传页基础布局与文案（en/id）

- **关联**：Story 4.3 · AC2 · F2
- **页面/入口**：`/triage/upload` · `triage_upload_page.dart`
- **前置**：已登录；en locale
- **步骤**：
  1. 从 AI 入口进入上传页
  2. 检查页面元素
- **预期**：AppBar 标题「AI Smart Triage」（l10n.triageEntryAiTitle）；图片上传区含 `triageAddImage` 按钮；说明文字「Up to 3 photos, each under 10 MB (JPG/PNG/HEIC)」（l10n.triagePhotoLimit）；症状文字输入框（`triageSymptomField`）含提示语；「Start analysis」按钮（`triageSubmit`，初始**禁用**）；底部显示免责声明（`triageDisclaimer` key）
- **层级**：L0

#### TC-4.305 仅有文字（无图片）→ 提交按钮可用（AC5 图片选填）

- **关联**：Story 4.3 · AC5 · R2 回改
- **页面/入口**：`/triage/upload` · `triage_upload_page.dart`
- **前置**：已登录
- **步骤**：
  1. 进入上传页，不添加任何图片
  2. 在症状文字输入框输入「犬精神不振，食欲稍差」
  3. 观察提交按钮状态
- **预期**：`triageSubmit` 按钮 `onPressed` 非空（可点击）；**无图有文字即可提交**；底部不显示错误提示
- **层级**：L0

#### TC-4.306 无图片无文字 → 提交按钮禁用

- **关联**：Story 4.3 · AC5 · R2 回改（文字仍必填）
- **页面/入口**：`/triage/upload`
- **前置**：已登录
- **步骤**：
  1. 进入上传页，不添加图片，不输入文字
  2. 观察提交按钮
- **预期**：`triageSubmit` 按钮 `onPressed` 为 null（禁用）；显示提示「Please describe the symptoms or add a photo first」（l10n.triageNeedInput）或等效文案
- **层级**：L0

#### TC-4.307 仅有图片（无文字）→ 提交按钮禁用（文字仍必填）

- **关联**：Story 4.3 · AC5 · R2 回改
- **页面/入口**：`/triage/upload`
- **前置**：已登录；Mock 模式（可模拟图片选择）
- **步骤**：
  1. 进入上传页，添加 1 张图片
  2. 不输入任何文字
  3. 观察提交按钮状态
- **预期**：`triageSubmit` 按钮 `onPressed` 为 null（禁用）；**仅有图片不可提交**，文字仍为必填
- **层级**：L0

#### TC-4.308 上传第 4 张图片被拒绝（不超过 3 张上限）

- **关联**：Story 4.3 · AC2 · F2（图片上限）
- **页面/入口**：`/triage/upload`
- **前置**：已登录；Mock 模式
- **步骤**：
  1. 添加 3 张图片
  2. 尝试点击「添加图片」按钮
- **预期**：已添加 3 张后，`triageAddImage` 按钮不再显示（满 3 隐藏）；无法添加第 4 张；现有 3 张缩略图正常显示，各有删除（X）按钮
- **层级**：L0

#### TC-4.309 超出单张 10MB 大小限制显示 toast 错误

- **关联**：Story 4.3 · AC2 · F2（大小校验）
- **页面/入口**：`/triage/upload`
- **前置**：已登录；准备一张 >10MB 的图片文件（L2 需真机）
- **步骤**：
  1. 尝试选取/拍摄一张超过 10MB 的图片
- **预期**：显示 toast/snackbar 错误提示「mediaImageTooLarge」或等效文案（l10n.mediaImageTooLarge）；该图片**不被添加**到上传列表
- **层级**：L2

#### TC-4.310 症状文字超出 2000 字符限制

- **关联**：Story 4.3 · AC2 · F2（字数限制）
- **页面/入口**：`/triage/upload`
- **前置**：已登录
- **步骤**：
  1. 在症状描述输入框粘贴 2001 个字符
  2. 检查输入框实际内容长度
- **预期**：输入框 `maxLength=2000` 截断，实际内容 ≤2000 字符；字符计数提示正确显示
- **层级**：L0

#### TC-4.311 相机/相册权限申请（L2 真机）

- **关联**：Story 4.3 · AC2（权限申请，复用 Story 2.1 模式）
- **页面/入口**：`/triage/upload` · 底部 sheet 选图来源
- **前置**：真机（iOS/Android），首次使用，尚未授权相机/相册权限
- **步骤**：
  1. 进入上传页
  2. 点击「添加图片」按钮
  3. 选择「Camera」（`triagePickCamera`）或「Gallery」（`triagePickGallery`）
  4. 系统弹出权限申请对话框
  5. 点击「拒绝」
- **预期**：点击触发 bottom sheet 显示两个选项（Camera/Gallery）；权限被拒后，不崩溃，引导用户去系统设置（复用 2.1 FR-22D 文案）；不强制继续选图流程
- **层级**：L2

#### TC-4.312 STS 直传私密桶并提交（L2 真机）

- **关联**：Story 4.3 · AC2（STS 直传）
- **页面/入口**：`/triage/upload`
- **前置**：真机；真实 STS 凭证；`PETGO_MOCK=false`
- **步骤**：
  1. 选择 1–3 张 JPG/PNG/HEIC 图片（含 HEIC 至少 1 张）
  2. 确认图片显示缩略图
  3. 填写症状文字，点击提交
  4. 检查提交时网络流量（不经后端转发）
- **预期**：图片 STS 直传私密桶成功（EXIF GPS 已剥离）；提交时客户端只发送 `imageObjectKeys`（对象 key）给后端，不发送图片二进制内容；`HEIC` 格式被正确处理
- **层级**：L2

### 4.3.C 提交等待态与降级态

#### TC-4.313 提交后显示全屏 spinner（等待态）

- **关联**：Story 4.3 · AC3 · F3
- **页面/入口**：`/triage/upload`（submitting/polling 态）
- **前置**：已登录；Mock 模式（提交后 polling 延迟 3s）
- **步骤**：
  1. 填写症状文字并提交
  2. 观察提交后的 UI
- **预期**：立即显示全屏居中 spinner（key: `triageWaiting`）；spinner 颜色为莫兰迪蓝 `AppColors.accentConsult`（#7BA7BC 区域）；文案「Analyzing your fur baby's condition, hang tight…」（l10n.triageAnalyzing，en）；无硬编码色值；上传表单消失
- **层级**：L0

#### TC-4.314 切 Tab 返回不丢失进行中分诊（provider 保活）

- **关联**：Story 4.3 · AC3（FR-19 不中断）
- **页面/入口**：`/triage/upload`（polling 态）→ 切 Tab → 返回
- **前置**：已提交，处于轮询等待中
- **步骤**：
  1. 提交分诊，进入 spinner 状态
  2. 切换到其他 Tab（如首页）
  3. 返回问诊 Tab，进入上传页
- **预期**：返回后仍处于 spinner 等待态（provider 为 app 级，非 autoDispose）；分诊不丢失，继续轮询到结果
- **层级**：L1

#### TC-4.315 超时（>15s）显示超时态文案与重提交按钮

- **关联**：Story 4.3 · AC4 · F4
- **页面/入口**：`/triage/upload`（timedOut 态）
- **前置**：Mock 模式；`triageResultProvider` 轮询超时设为 <2s（测试用）
- **步骤**：
  1. 提交分诊，人为触发超时
  2. 观察 UI 切换
- **预期**：显示超时态（key: `triageTimeout`）；标题「Analysis is taking longer than usual」（l10n.triageTimeoutTitle）；说明文字「Please try again in a moment」（l10n.triageTimeoutBody）；「Resubmit」按钮（`triageResubmit`）可用；**不显示**软引导兽医按钮（超时态不含软引导）
- **层级**：L0

#### TC-4.316 服务异常（FAILED）显示异常态 + 软引导兽医

- **关联**：Story 4.3 · AC4 · F4（降级 + 软引导）
- **页面/入口**：`/triage/upload`（failed 态）
- **前置**：Mock 模式；打桩 Gemini 使任务最终为 FAILED
- **步骤**：
  1. 提交分诊，等待 FAILED
  2. 观察 UI
- **预期**：显示异常态（key: `triageError`）；标题「AI service is temporarily unavailable」（l10n.triageErrorTitle）；「Resubmit」按钮（`triageResubmit`）可用；**软引导**「Or contact a vet directly」（`triageContactVet` key，TextButton）出现；软引导为**非红态**普通文字按钮（不含兽医导流变现）
- **层级**：L0

#### TC-4.317 重新提交复用上次内容（不重传已上传图片）

- **关联**：Story 4.3 · AC4（重提交复用不重传）
- **页面/入口**：`/triage/upload`（重提交）
- **前置**：Mock 模式；已提交并得到 FAILED 态
- **步骤**：
  1. 在异常态点击「Resubmit」（`triageResubmit`）
  2. 观察重提交的网络请求（或 provider 状态）
- **预期**：重提交时 `imageObjectKeys` 保持原有值（`TriageDraftImage.objectKey` 已填则跳过上传）；`symptomText` 保留原值；不重新上传图片（已有对象 key 直接复用）；仅重发 `POST /triage`（新 Idempotency-Key）
- **层级**：L0/L1

#### TC-4.318 上传页前置免责声明（NFR-9）

- **关联**：Story 4.3 · 安全约束（NFR-9 免责前置）
- **页面/入口**：`/triage/upload`
- **前置**：已登录；en/id 双语
- **步骤**：
  1. 进入上传页（表单态）
  2. 检查页面底部
- **预期**：底部显示 `triageDisclaimer` 免责声明（小号字、次要色，不干扰主流程）；en 文案：「AI triage is for reference only and does not replace a professional veterinary diagnosis…」；id 文案：「Triase AI hanya sebagai referensi…」；**不可省略**
- **层级**：L0

#### TC-4.319 后端校验：无图有文字 → 202；文字为空 → 422

- **关联**：Story 4.3 · AC5 R2 回改（后端 Bean Validation）
- **页面/入口**：`POST /api/v1/triage`（后端直调）
- **前置**：有效 JWT；L1 环境
- **步骤**：
  1. body `{"symptomText":"正常打喷嚏","imageObjectKeys":[]}` → 检查状态码
  2. body `{"symptomText":"","imageObjectKeys":[]}` → 检查状态码
  3. body `{"symptomText":"   ","imageObjectKeys":[]}` → 检查状态码
- **预期**：情况 1：HTTP 202（无图有文字通过）；情况 2 和 3：HTTP 422（`symptomText` 为空/空白 → `@NotBlank` 拦截）
- **层级**：L1

#### TC-4.320 后端校验：超过 3 张图片 → 422；文字超 2000 字 → 422

- **关联**：Story 4.3 · AC5（后端边界校验）
- **页面/入口**：`POST /api/v1/triage`（后端直调）
- **前置**：有效 JWT；L1 环境
- **步骤**：
  1. body 含 4 个 `imageObjectKeys` 元素
  2. body 含 2001 字符的 `symptomText`
- **预期**：两种情况均返回 HTTP 422，body 为 ProblemDetail
- **层级**：L1

---

## 4.4 分诊结果三态展示（绿/黄含条件倒计时协议）

### 4.4.A 绿色结果页

#### TC-4.401 绿色结果页全要素（badge + 文案 + 软引导 + 存档 + 免责）

- **关联**：Story 4.4 · AC1 · F2
- **页面/入口**：`/triage/upload`（DONE+GREEN）· `triage_result_view.dart`
- **前置**：Mock 模式；打桩 Gemini 返回 GREEN（+ advice 文本）
- **步骤**：
  1. 提交分诊，等待 DONE（GREEN）
  2. 检查结果页全部元素
- **预期**：
  - 绿色 badge（`triageGreenPage` key）：图标 + 文本「Not urgent found」（l10n.triageGreenTitle）+ 绿色 (#triageGreen)
  - 三重表达非颜色单一：icon + 文本 + 色（NFR-13）
  - 居家观察建议文字（来自 advice）
  - 软引导「Want extra peace of mind? You can confirm with a vet」（l10n.triageSoftVetGuide，软性文字）
  - 「Save this result to the archive」按钮（`triageSaveToArchive` key）
  - 「Talk to a vet」按钮（`triageConsultVet` key，升级到兽医）
  - 底部免责声明（`triageDisclaimer`，小号次要色）
  - 左 3px 区域色边框（white 卡片 + 3px 绿色左边框）
- **层级**：L0

#### TC-4.402 绿色结果页（id locale 文案核查）

- **关联**：Story 4.4 · AC1 · i18n
- **页面/入口**：`triage_result_view.dart`
- **前置**：id locale；GREEN 结果
- **步骤**：
  1. 切换 locale 为 id
  2. 查看绿色结果页
- **预期**：标题「Tidak ada risiko mendesak」；软引导「Ingin lebih tenang? Kamu bisa konfirmasi ke dokter hewan」；存档「Simpan hasil ini ke arsip」；免责「Triase AI hanya sebagai referensi…」；**无中文字符**
- **层级**：L0

#### TC-4.403 绿色结果页点击「存入档案」触发 FR-16 弹窗

- **关联**：Story 4.4 · AC3 · F5（存档）
- **页面/入口**：`triage_result_view.dart`
- **前置**：GREEN 结果；已有宠物档案（Story 2.5 就位）
- **步骤**：
  1. 在绿色结果页点击「Save this result to the archive」
- **预期**：触发 FR-16 存档弹窗（2.5 提供）；传入 `triageId`；弹窗含「保存到哪只宠物档案」的选择逻辑；**不直接写入（弹窗确认后写）**
- **层级**：L1

### 4.4.B 黄色结果页

#### TC-4.404 黄色结果页三项同屏（badge + 建议 + 用药参考）

- **关联**：Story 4.4 · AC2 · F3
- **页面/入口**：`/triage/upload`（DONE+YELLOW）· `triage_result_view.dart`
- **前置**：Mock 模式；打桩 Gemini 返回 YELLOW（含 advice + medicationRef）
- **步骤**：
  1. 提交分诊，等待 DONE（YELLOW）
  2. 检查结果页
- **预期**：
  - `triageYellowPage` key
  - 黄色 badge：icon + 「Keep a close watch」（l10n.triageYellowTitle）+ 黄色（三重表达）
  - 观察建议文字（advice，经 `TriageWordingGuard` 过滤终结词）
  - 居家用药参考（`triageMedicationRefLabel` + medicationRef 文字）
  - 三项在同一屏内可见（不分页/不二跳）
- **层级**：L0

#### TC-4.405 黄色结果页条件倒计时协议块（三要素 + 视觉可区分）

- **关联**：Story 4.4 · AC2（条件倒计时协议 FR-2 / UX-DR6）
- **页面/入口**：`triage_result_view.dart` · `_ProtocolBlock`
- **前置**：YELLOW 结果；打桩包含三要素的 observation（indicators/timeWindow/escalationTriggers）
- **步骤**：
  1. 在黄色结果页检查协议块区域（`triageProtocolBlock` key）
- **预期**：
  - 协议块背景色 `triageYellowSurface` (#EEF4F7 accent-consult 浅底）
  - 圆角 `rounded.sm`
  - 标题「Observation protocol」（l10n.triageObservationTitle）加粗
  - 三要素独立分区：
    - 「What to watch」（l10n.triageIndicatorsLabel）+ 指标列表
    - 「Time window」（l10n.triageTimeWindowLabel）+ 时间窗口内容
    - 「See a vet right away if」（l10n.triageEscalationLabel）+ 触发条件列表
  - 视觉可区分（加粗标题区分）
- **层级**：L0

#### TC-4.406 黄色结果页不出现终结性表述（词表守卫）

- **关联**：Story 4.4 · AC2（禁终结性表述 FR-2 / F4）
- **页面/入口**：`triage_result_view.dart` · `TriageWordingGuard`
- **前置**：Mock 模式；打桩 Gemini 返回含终结性词汇的 advice（如「不严重，可以放心」）
- **步骤**：
  1. 提交分诊，等待 YELLOW 结果（advice 含「可以放心」）
  2. 检查结果页渲染的建议文字
- **预期**：「可以放心」等终结词**不出现**在渲染结果中；被替换为中性回退提示（l10n.triageNeutralAdvice：「Please keep observing your pet and consult a vet if anything changes.」）；**绝不原样渲染终结措辞**
- **层级**：L0

#### TC-4.407 黄色结果页不出现终结性表述（id/en 词表）

- **关联**：Story 4.4 · AC2（TriageWordingGuard 多语种）
- **页面/入口**：`triage_wording_guard.dart`
- **前置**：Mock 模式；advice 含印尼语终结词（如「tidak serius」）
- **步骤**：
  1. advice 含印尼语终结词
  2. 检查渲染结果
- **预期**：印尼语终结词被拦截，渲染为中性提示（id locale 版本）；不原样渲染
- **层级**：L0

#### TC-4.408 RED 结果在结果视图中交棒给红色半屏（不软化）

- **关联**：Story 4.4 · 安全约束（红色绝不软化）⚠️安全
- **页面/入口**：`triage_result_view.dart`
- **前置**：Mock 模式；打桩返回 RED
- **步骤**：
  1. 等待 RED 结果
  2. 检查渲染的 widget 树
- **预期**：`TriageResultView` **不渲染绿/黄结果卡**（`TriageResultCard` findsNothing）；渲染 `TriageRedResult`（进入红色处理流程，触发 overlay）；**绝对不把 RED 软化为黄卡或绿卡**
- **层级**：L0

### 4.4.C 结果页多态

#### TC-4.409 403/404 结果取失效/无权限 → 友好占位不白屏

- **关联**：Story 4.4 · F6（多态，UX-DR18）
- **页面/入口**：`triage_result_view.dart`
- **前置**：triageId 存在但被删除，或无权限访问
- **步骤**：
  1. 构造 `triageResultProvider` 处于 error（403/404）态
  2. 检查 UI 渲染
- **预期**：显示友好错误占位（不白屏、不崩溃）；**不暴露资源是否曾存在**（防枚举，符合 UX-DR18）；提示用户可返回或联系支持
- **层级**：L0

#### TC-4.410 黄色结果页（id locale）

- **关联**：Story 4.4 · AC2 · i18n
- **页面/入口**：`triage_result_view.dart`
- **前置**：id locale；YELLOW 结果（含 observation）
- **步骤**：
  1. 切换 locale id
  2. 查看黄色结果页和协议块
- **预期**：标题「Pantau dengan saksama」；协议块标题「Protokol pemantauan」；三要素标签：「Yang perlu dipantau」/「Rentang waktu」/「Segera ke dokter hewan jika」；**无中文字符**
- **层级**：L0

---

## 4.5 红色半屏强提醒

> ⚠️ 本节为**生命安全支柱最直接用户面**，全部测试具有最高优先级。

### 4.5.A ⚠️安全 红色半屏 overlay 锁定态（0–5s）

#### TC-4.501 ⚠️安全 RED 结果触发红色半屏自底滑起

- **关联**：Story 4.5 · AC1 · F1 ⚠️安全
- **页面/入口**：`/triage/upload`（DONE+RED）· `red_alert_overlay.dart`
- **前置**：Mock 模式；打桩返回 RED；宠物名「Momo」
- **步骤**：
  1. 提交分诊，等待 DONE（RED）
  2. 观察 overlay 出现
- **预期**：`showModalBottomSheet` 自底向上滑起（约 300ms 动效）；半屏红底色 `triageRed` (#C97A7A)；大白 ⚠️ 图标（`triageRedIcon` key，size 64）；display 文案「Take Momo to an animal hospital now」（l10n.triageRedTitle("Momo")）；副文「A possible emergency was detected…」（l10n.triageRedSubtext）
- **层级**：L0

#### TC-4.502 ⚠️安全 0–5s 锁定：单按钮禁用 + 倒计时递减

- **关联**：Story 4.5 · AC1（锁定机制）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：lockSeconds=5（默认）
- **步骤**：
  1. RED 结果触发 overlay 出现
  2. 立即检查「我已知晓」按钮状态
  3. 观察倒计时文本（`triageRedCountdown` key）递减（5→4→3→2→1→0）
  4. 倒计时归 0 前再次检查按钮状态
- **预期**：按钮（`triageRedAcknowledge` key）`onPressed=null`（禁用）；倒计时文本显示「5s until you can act」→「4s…」→「3s…」依次递减；5s 内按钮始终禁用
- **层级**：L0

#### TC-4.503 ⚠️安全 5s 内背景点击不关闭 overlay

- **关联**：Story 4.5 · AC1（isDismissible=false）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：lockSeconds=5；overlay 显示中（锁定期）
- **步骤**：
  1. 在 5s 内点击 overlay 背景区域
  2. 观察 overlay 是否关闭
- **预期**：overlay **不关闭**（`isDismissible:false`）；仍显示锁定期内容
- **层级**：L0

#### TC-4.504 ⚠️安全 5s 内拖拽下滑不关闭 overlay

- **关联**：Story 4.5 · AC1（enableDrag=false）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：锁定期内
- **步骤**：
  1. 在 5s 内向下拖拽 overlay
  2. 观察 overlay 是否关闭
- **预期**：overlay **不关闭**（`enableDrag:false`）
- **层级**：L0

#### TC-4.505 ⚠️安全 5s 内系统返回键不关闭 overlay

- **关联**：Story 4.5 · AC1（PopScope canPop=false）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：锁定期内；Android 设备或手势导航
- **步骤**：
  1. 在 5s 内按 Android 返回键（或向左滑动手势）
  2. 观察 overlay 是否关闭
- **预期**：overlay **不关闭**（`PopScope(canPop:false)` 拦截）
- **层级**：L0

### 4.5.B ⚠️安全 解锁后单一「我已知晓」按钮

#### TC-4.506 ⚠️安全 5s 后按钮启用，倒计时消失

- **关联**：Story 4.5 · AC2（解锁）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：lockSeconds=5；等待 5s
- **步骤**：
  1. 触发 overlay，等待约 5–6 秒
  2. 检查按钮状态和倒计时文本
- **预期**：5s 后「I understand」按钮（`triageRedAcknowledge`）`onPressed` 非空（启用）；倒计时文本（`triageRedCountdown`）消失
- **层级**：L0

#### TC-4.507 ⚠️安全 点击「我已知晓」关闭 overlay，返回结果摘要页

- **关联**：Story 4.5 · AC2（单确认 F3'）⚠️安全
- **页面/入口**：`red_alert_overlay.dart` → `triage_red_result.dart`
- **前置**：解锁后（5s 已过）
- **步骤**：
  1. 点击「I understand」按钮
  2. 观察 overlay 是否关闭
  3. 检查 overlay 关闭后的页面内容
- **预期**：overlay 关闭（`onAcknowledge` 回调调用 `Navigator.pop`）；**返回结果摘要页**（`triageRedSummary` key）；**无任何系统确认对话框、无应用内二次确认、无地图深链**（F3 去导航化）
- **层级**：L0

### 4.5.C ⚠️安全 红色态零变现/零导航

#### TC-4.508 ⚠️安全 overlay 内零兽医入口/零变现/零地图导航

- **关联**：Story 4.5 · AC1/AC2 · NFR-9（F3）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：RED 结果触发 overlay
- **步骤**：
  1. 在 overlay 显示期间，遍历整个 widget 树
  2. 查找：兽医咨询入口（`triageContactVet`/`triageEntryVet`/`triageConsultVet`）、付费/引流元素、地图导航相关 widget（原 `triageRedNavigate`/`triageRedLater`）、医院推荐/搜索词
- **预期**：上述所有元素 **findsNothing**；overlay 子树中**零**兽医咨询入口、**零**变现引流、**零**地图导航、**零**医院推荐；唯一可点击元素是「I understand」按钮（锁定期内禁用）
- **层级**：L0

#### TC-4.509 ⚠️安全 overlay 关闭后结果摘要页零兽医 CTA/零导航

- **关联**：Story 4.5 · AC2（F5）⚠️安全
- **页面/入口**：`triage_red_result.dart`（overlay 关闭后）
- **前置**：overlay 已关闭（点击「我已知晓」后）
- **步骤**：
  1. overlay 关闭后检查结果摘要页内容
  2. 查找：`triageContactVet`/`triageConsultVet`（兽医 CTA）、地图导航、医院推荐、付费引流、`triageSaveToArchive`（绿/黄存档键）
- **预期**：
  - `triageConsultVet` findsNothing（无兽医升级入口）
  - 无地图导航 widget
  - 无医院推荐
  - `triageSaveToArchive`（绿黄键）findsNothing
  - 页面保留：⚠️ 图标 + 「Urgent: high risk」（l10n.triageRedLevelLabel）+ 建议文字 + 免责声明
  - 「Save this result to the archive」（`triageRedSaveToArchive` key）**可见**（R2 存档入口，免费工具，非变现）
- **层级**：L0

#### TC-4.510 ⚠️安全 红色摘要页「存入档案」入口（R2，守零变现护栏）

- **关联**：Story 4.5 · AC4（R2 存档入口）⚠️安全
- **页面/入口**：`triage_red_result.dart`
- **前置**：RED 结果，overlay 已关闭；有宠物档案（状态 A）
- **步骤**：
  1. 检查 `triageRedSaveToArchive` 按钮存在
  2. 点击该按钮（已建档分支）
  3. 检查反馈
- **预期**：按钮存在且为「Save this result to the archive」；已建档（状态 A）→ 直接存入无弹窗确认 + 成功提示（如 snackbar 「Saved to the archive / Tersimpan ke arsip」，l10n.triageRedArchived）；该入口**明确标注为存档工具**，不夹带任何兽医/付费/引流元素
- **层级**：L0

#### TC-4.511 ⚠️安全 红色摘要页「存入档案」（未建档分支引导建档）

- **关联**：Story 4.5 · AC4（R2，未建档分支）⚠️安全
- **页面/入口**：`triage_red_result.dart`
- **前置**：RED 结果；**无宠物档案**（未建档/状态 B/C）
- **步骤**：
  1. 点击 `triageRedSaveToArchive`
- **预期**：引导先建档提示（l10n.triageArchiveNoPet：「Create a pet profile first to save to the archive」）；不直接写入；**无兽医 CTA / 无变现引流**
- **层级**：L0

#### TC-4.512 ⚠️安全 无绑定宠物 → display 文案降级为通用文案

- **关联**：Story 4.5 · AC1（多态：无宠物名）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：RED 结果；**用户无绑定宠物（petProfileProvider 返回 null）**
- **步骤**：
  1. 触发 RED，overlay 出现
  2. 检查 display 文案
- **预期**：display 文案降级为「Take your pet to an animal hospital now」（l10n.triageRedTitleNoPet）；**不出现空字符串或崩溃**
- **层级**：L0

#### TC-4.513 ⚠️安全 4.2 强制升红产生的 RED 同样触发 overlay（无差别）

- **关联**：Story 4.5 · AC1（RED 两源无差别）⚠️安全
- **页面/入口**：后端整链路 → 前端
- **前置**：Docker postgres+redis；打桩 Gemini 故意返回 GREEN；症状命中高危清单（4.2 强制升红）
- **步骤**：
  1. 提交命中高危症状，打桩 Gemini 返回 GREEN
  2. 等待 DONE（后端经 4.2 升红为 RED）
  3. 前端收到 RED，检查 overlay 是否触发
- **预期**：前端收到 `dangerLevel=RED`（4.2 升红），**同样**触发红色半屏 overlay；与模型直接返回 RED 的行为完全一致（前端无差别处理两源）
- **层级**：L1

### 4.5.D 无障碍（AC3）

#### TC-4.514 语义树：alertdialog / ASSERTIVE liveRegion 播报

- **关联**：Story 4.5 · AC3（无障碍 NFR-13）
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：RED overlay 显示中
- **步骤**：
  1. 检查 widget 树中 `Semantics` 节点
  2. 确认 `liveRegion=true`、`container=true`、`label` 包含 display 文案
- **预期**：`Semantics(liveRegion:true, container:true, label:「请立即带[宠物名]去宠物医院就诊…」)`；assertive 等价语义（屏幕阅读器出现即打断播报）
- **层级**：L0

#### TC-4.515 非颜色单一：⚠️ + 大字 + 红底三重表达

- **关联**：Story 4.5 · AC3（NFR-13，色盲可读）
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：RED overlay 显示中
- **步骤**：
  1. 检查 overlay 中是否同时有：⚠️ 图标、display 大字文本、红底色
  2. 模拟色盲场景（灰度模式），判断紧急等级是否仍可辨
- **预期**：三重信号独立传达紧急等级：icon（`triageRedIcon`）+ display 大字（`AppTypography.display`）+ 红底（#C97A7A）；去掉颜色信息（灰度）仍可通过 icon + 文字辨认紧急态
- **层级**：L0

#### TC-4.516 触摸目标 ≥ 44pt（按钮可触摸性）

- **关联**：Story 4.5 · AC3（无障碍触摸目标，UX-DR15）
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：L0
- **步骤**：
  1. 检查「I understand」按钮的 `minimumSize` 属性
- **预期**：`minimumSize: Size.fromHeight(48)`（≥44pt 标准）；全宽按钮（`width: double.infinity`）
- **层级**：L0

#### TC-4.517 真机 VoiceOver/TalkBack 打断式 ASSERTIVE 播报（L2 必做）

- **关联**：Story 4.5 · AC3（L2 真机无障碍）⚠️安全
- **页面/入口**：`red_alert_overlay.dart`
- **前置**：真机（iOS VoiceOver / Android TalkBack 已开启）；RED 结果
- **步骤**：
  1. 开启屏幕阅读器
  2. 触发 RED overlay
  3. 倾听屏幕阅读器播报内容和时机
- **预期**：overlay 出现时屏幕阅读器**立即打断**当前内容，播报「Take [petName] to an animal hospital now」+ 副文（assertive，不等待用户聚焦）；色盲模拟下 ⚠️+大字仍可辨等级；白字/红底对比度 AA；动态字体放大 3 级不破版
- **层级**：L2

---

## ⚠️ 安全攸关回归清单（Red Gate Checklist）

> 每次代码变更后，**必须**在合并前过以下勾选项。全部通过方可继续。

### 强制升红（SafetyRuleLayer 核心）

- [ ] `SafetyRuleLayer.enforce` 是 `danger_level` 写入的**唯一前置**，grep 确认无旁路（TC-4.214）
- [ ] 命中高危清单（chocolate/呼吸困难/呕吐带血/…）→ `danger_level=RED`，无论打桩 Gemini 返回 GREEN/YELLOW（TC-4.201/4.202）
- [ ] 模型给 RED + 清单命中 → 仍为 RED（不下调，TC-4.203）
- [ ] 模型给 RED + 清单未命中 → 仍为 RED（只升不降铁律）
- [ ] 非高危症状 + 模型 GREEN → 最终 GREEN（不误升，TC-4.204）
- [ ] 清单缺失/为空 → 后端 fail-fast 拒绝启动（TC-4.213）

### E5 否定语境保守处理

- [ ] 「狗没有呼吸困难」→ 不升红（TC-4.207）
- [ ] 「没有呼吸困难，但呕吐带血」→ **必须升红**（TC-4.208）⚠️
- [ ] 「没有发烧，今天呕吐带血」→ **必须升红**（否定不跨逗号，TC-4.209）⚠️
- [ ] 「没吃到巧克力」→ 不升红（TC-4.210）
- [ ] 「tidak sesak napas」→ 不升红（TC-4.211）
- [ ] 「tidak sesak napas, tapi muntah darah」→ **必须升红**（TC-4.212）⚠️

### 红色态零变现/零兽医入口/去导航（F3）

- [ ] overlay 内无任何 `triageContactVet`/`triageConsultVet`/兽医入口（TC-4.508）⚠️
- [ ] overlay 内无地图导航/医院推荐（旧 `triageRedNavigate` findsNothing）（TC-4.508）⚠️
- [ ] overlay 内无广告/付费/引流元素（TC-4.508）⚠️
- [ ] 解锁后仅单一「I understand / Saya mengerti」按钮，无二次确认/地图深链（TC-4.507）⚠️
- [ ] 关闭后结果摘要页无兽医 CTA（`triageConsultVet` findsNothing）（TC-4.509）⚠️
- [ ] 关闭后结果摘要页无地图导航（TC-4.509）⚠️
- [ ] 红色态「存入档案」(`triageRedSaveToArchive`) 存在且唯一，不夹带变现/引流（TC-4.510）
- [ ] RED 结果不被前端软化为绿/黄卡（`TriageResultCard` findsNothing when RED，TC-4.408）⚠️

### 5s 锁定不可绕过

- [ ] 锁定期内按钮禁用（TC-4.502）⚠️
- [ ] 背景点击不关闭（TC-4.503）⚠️
- [ ] 拖拽不关闭（TC-4.504）⚠️
- [ ] 系统返回键不关闭（TC-4.505）⚠️

### i18n 安全（App 不渲染中文）

- [ ] id locale 下 overlay 无中文字符：display 文案为「Segera bawa {name} ke rumah sakit hewan」
- [ ] id locale 下「I understand」→「Saya mengerti」（`triageRedAcknowledge`）
- [ ] en/id 分诊各级别标签本地化（triageLevelGreen/Yellow/Red）

---

## 本章遗留/盲区

以下内容在当前 L0/L1 验收范围外，或存在已知限制，需在 L1/L2 验收阶段特别关注：

1. **Gemini 图片摄取机制（L2 核实）**：Story 4.1 的 `GeminiClient` 使用 `fileData.fileUri` 传签名 URL；历史上 Developer API 的 `fileUri` 主要接 File API / Google 托管资源。**若真实 Gemini API 对任意签名 URL 的 `fileUri` 支持受限**，需在 L2 阶段评估是否切换为 File API 上传或 `inlineData` base64，`GeminiClient` 接口抽象已为此预留调整点（见 4.1 Completion Notes）。测试时需实测 TC-4.111 并记录实际摄取机制。

2. **倒计时切前后台不重置（L2 真机观察）**：`RedAlertOverlay` 倒计时基于挂载期 `Timer.periodic`；切前后台时 App 进后台，计时器行为依平台（iOS/Android）而异，存在"切后台后返回倒计时略有偏差"的场景。**设计意图（FR-19）**：进行中分诊不中断，切回后倒计时继续。需在 L2 真机验收中确认 iOS/Android 两平台行为一致。若计时器被 App 生命周期暂停，需讨论是否可接受（偏差通常 <1s）。

3. **SafetyRuleLayer 兽医评审尚未完成**：起步 20 条清单为 dev agent 起草 + 用户监管审定，**尚未经兽医顾问正式评审签字**。上线前必须完成兽医顾问正式评审，特别是：信号同义词的医学准确性、否定词集的完整性（是否有遗漏否定句式）、20 条急症覆盖是否充分。TC-4.201–4.212 系列测试依赖此清单，清单变更后测试用例对应的 `symptomText` 样本可能需要随之更新。

4. **黄色三要素结构化输出（L2 核实）**：Story 4.4 B1 回补了黄色 observation（indicators/timeWindow/escalationTriggers）结构化字段，由 `GeminiDeveloperApiClient` responseSchema 约束模型输出。**打桩 Gemini（L0/L1）的 `StubGeminiClient` 返回固定 GREEN 无 observation**——黄色三要素的结构化正确性只能在 L2 真实 Gemini 链路验收（TC-4.405 对应的 L2 验证）。需在 L2 时验证模型是否按 responseSchema 正确返回 observation 结构，以及前端三要素分区展示是否正确。

5. **写端点 Redis 令牌桶限流 + 429 响应（未覆盖）**：架构 NFR-12 要求 `POST /api/v1/triage` 走 Redis 令牌桶限流。当前测试用例集未包含"短时间内高频提交触发 429"的测试场景。建议补充：单用户短时间内连续提交 N 次（超限流阈值），验证返回 HTTP 429 ProblemDetail，前端应有降级提示（4.3 降级态可承接）。

---

*文档由 Paige（Technical Writer）生成 · 2026-06-10 · TailTopia Epic 4 QA*
