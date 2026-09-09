# 红色态对症应急建议 — 设计文档

**日期**：2026-06-30
**范围**：AI 分诊红色态（DangerLevel.RED）的应急建议从「写死通用 3 步」改为「AI 按当前症状生成的对症院前应急建议」。
**安全等级**：⚠️ 安全攸关（红色态属 CLAUDE.md 安全节点；改动不得削弱"立即就医"或绕过安全规则层）。

---

## 1. 背景与现状

AI 分诊三态 GREEN/YELLOW/RED。红色态前端走专门组件 `triage_red_result.dart` → 全屏 `RedAlertOverlay`：强警告标题 + 症状 + **写死的 3 步应急** + 5 秒倒计时 + 单一「我已知晓」退出（Story 4.5，F3 去导航化）。

**问题**：那 3 步是固定本地化文案 `triageRedStep1/2/3`，与具体症状无关 → 千篇一律；且第 2 句"裹暖布(wrap in warm cloth)"碰上**中暑**急症反而有害。后端 `advice` 字段虽由 Gemini 生成，但红色态前端**根本不展示它**。

## 2. 目标

红色态应急步骤改为 AI 针对当前症状生成、设想"主人短时间无法到院"场景下的到院前对症应急处理，内容更丰富、对症；同时**强制保留**立即就医的紧迫性与所有安全护栏。

## 3. 非目标（不动）

- F3 去导航化护栏：零兽医 CTA / 零变现引流 / 零地图导航 / 零医院推荐 — **全部保留**。
- `SafetyRuleLayer` 只升不降逻辑 — **不改**。
- 红色态唯一出口"我已知晓"、5 秒倒计时、全屏沉浸 — **不改**。
- 不开处方、不替代兽医 — **保留**。

## 4. 设计

### 4.1 数据契约（后端新增两个结构化字段，仅红色态填）

`GeminiDeveloperApiClient.RESPONSE_SCHEMA` 新增：
- `emergencySteps`: `ARRAY<STRING>` — "现在该做"对症步骤，3-5 条
- `emergencyAvoid`: `ARRAY<STRING>` — "切勿"禁忌，1-3 条

> **取舍**：用新字段而非复用 `advice`。`advice` 语义是黄/绿态观察建议，红色态前端不展示；新字段前端直接循环渲染，互不污染。两字段非 `required`（非红色态/越界不填）。

### 4.2 Prompt（SYSTEM_INSTRUCTION 加红色态专项指令）

判 RED 时**必须**生成 `emergencySteps` + `emergencyAvoid`，明确设想"主人短时间无法到院"，给到院前对症应急；每条简短、单一动作、可操作；**同时强制声明**：必须立即就医，以下仅为送医途中/到院前应急，不可替代就医、不开处方。越界或非红一律**不填**这两字段（与现有"越界恒 GREEN+引导、绝不因越界判红"边界一致）。

### 4.3 后端透出链路

- `GeminiTriageResult` record 加 `emergencySteps`、`emergencyAvoid`（`List<String>`，可空）。
- `GeminiDeveloperApiClient.parse()` 解析这两字段；`StubGeminiClient` 红色样例补充这两字段。
- `TriageTask.parsedResult`（JSONB）随结果落库 — 无 schema 变更（JSONB 自由结构）。
- `TriageResultResponse` 透出 `emergencySteps`、`emergencyAvoid`（DONE 态）。

### 4.4 前端（RedAlertOverlay 渲染）

- 前端 `TriageResult` model 加 `emergencySteps`、`emergencyAvoid`（`List<String>`，从 `/triage/{id}` 响应解析，缺省空表）。
- `triage_red_result.dart` 把这两字段传入 `RedAlertOverlay`。
- `RedAlertOverlay` 步骤区：
  - **有 AI 内容**：渲染"现在该做"(emergencySteps，沿用 `_StepLine` 编号样式) + "切勿"(emergencyAvoid，红色禁止样式新区块)。
  - **拿不到 AI 内容**（安全层强制升红 / AI 失败 / 模型没产出 → 空表）：**回退**到通用兜底步骤，并把现有 `triageRedStep2`"裹暖布"改为中性安全表述（去除可能对症有害的具体动作）。
  - 步骤区顶部固定一句"以下仅为就医前应急，不可替代立即就医"（新 i18n）。
- 顶部强警告、症状区、5 秒倒计时、"我已知晓"不动。

### 4.5 i18n（两份 ARB 同步）

- 新增：`triageRedAvoidHeader`（"切勿/Hindari"小标题）、`triageRedPreCareNote`（"以下仅为就医前应急…"）。
- 修订：`triageRedStep2`（去"裹暖布"，改中性安全表述，如"运输时尽量减少移动与刺激"）。
- 改 ARB 后必须 `flutter gen-l10n`。

## 5. 安全兜底矩阵

| 场景 | 红色 overlay 步骤区展示 |
|---|---|
| AI 判红 + 产出 emergencySteps/avoid | AI 对症"该做"+"切勿" |
| SafetyRuleLayer 强制升红（AI 判黄/绿，无对症急救步骤） | 回退通用兜底步骤 |
| AI 调用失败 / 超时 / 没产出步骤 | 回退通用兜底步骤 |

**永远有保底内容**；顶部"立即就医"强提醒在任何分支恒显。

## 6. 受影响文件

**后端**
- `shared/ai/GeminiDeveloperApiClient.java`（SYSTEM_INSTRUCTION + RESPONSE_SCHEMA + parse）
- `shared/ai/GeminiTriageResult.java`（record 加字段）
- `shared/ai/StubGeminiClient.java`（红色样例补字段）
- `triage/dto/TriageResultResponse.java`（透出）

**前端**
- `features/triage/domain/triage_result_state.dart`（或 model 定义处，加字段 + 解析）
- `features/triage/presentation/triage_red_result.dart`（传参）
- `shared/widgets/red_alert_overlay.dart`（渲染 + 回退）
- `l10n/app_en.arb` / `l10n/app_id.arb`（新增/修订文案）

## 7. 测试与验收层级

- **L0 后端**：`mvn -B package`；`GeminiDeveloperApiClient` parse 单测（含 emergencySteps/avoid）；`TriageResultResponse.from` 透出测试；Stub 红色样例。
- **L0 前端**：`flutter analyze` + `flutter gen-l10n`；`RedAlertOverlay` widget test 两路（有 AI 步骤渲染 / 空→回退通用 + 修订后兜底文案）。
- **L2（本地/真机）**：真实 Gemini 对中暑/中毒/外伤等不同红色症状返回**对症且不同**的应急建议；overlay 视觉；非红/越界不出这两字段。

## 8. 风险

- **送医被稀释**：靠 prompt 强制声明 + overlay 顶部强提醒 + 步骤区"不可替代就医"前缀三重缓解。
- **AI 给出错误/危险应急**：prompt 约束"单一动作、对症、不开处方"；红色态本就强制就医，应急仅为辅助；保留通用兜底。L2 需对高风险症状（中毒/中暑/外伤/抽搐）人工抽检。
