# 付费问诊链路断裂 — 缺口分析与实施规格

> 产出：2026-07-16 · 分支 `v1.1-dev` · 依据 stag 实测日志 + 代码实读，非推测
> 状态：**待产品拍板 D1~D3，未开工**
>
> ⚠️ **术语澄清**：本文档**不存在「免费流 / 付费流」两条产品线**的说法。兽医问诊只有一个功能，V1.1 起**一律付费**。
> 存在的是**两条发起路径**（V1.0 旧 / V1.1 新），新的应取代旧的——这是**版本迁移未完成**，不是功能线取舍。
> `ConsultSource.DIRECT` / `AI_UPGRADE` 只是**来源标记**（用户直接发起 vs 分诊升级），**与收费无关**——付费流建的会话同样是 `DIRECT`。

---

## 1. 现象

stag 环境用户发起兽医问诊，兽医端待接单队列**恒为空**，用户等满 60 秒超时。

## 2. 证据（stag 实测，2026-07-16）

| 观测 | 值 | 来源 |
|---|---|---|
| 兽医端轮询 `GET /vet/consultations/queue` | 102 次 · `status=200` · `sub=1` `role=VET` | stag 接口日志 |
| 该端点响应 | `{"available":[]}` 恒空 | 同上 |
| 用户端实际发起 | `consult-sessions/75` · **`status:WAITING`** · `sub=54` | 同上 |
| 用户端结局 | `waitingElapsedSeconds:60` → `timedOut:true` | 同上 |
| `GET /vet/consult-sessions/waiting`（旧路径兽医端点） | **调用 0 次** | 同上 |

**排除项**：非鉴权失败（200）、非前端吞错（请求确实发出且成功）、非 `isBusy` 过滤（池本身为空）、非数据丢失。

**判据说明**：`status=WAITING` 是旧路径的确证——付费路径的会话**从不以 WAITING 落库**（`ConsultPayService:177-180` 建后立刻 `markInProgress(vetId)` + 附 IM 会话）。**勿用 `source=DIRECT` 判断**，付费会话同样是 DIRECT。

## 3. 根因

**用户端仍走 V1.0 旧发起路径，兽医端已切 V1.1 付费队列，中间无桥。**

这不是代码写错——每个 story 都在自己范围内正确完成了，是**规划层两个 `[OPEN]` 掉棒**：

1. **Story 3-2 第 101 行 `[OPEN]`**：`symptomText`/`imageObjectKeys` 是否存 `consult_requests` —— 「本 story 不持久化病例…**dev 与产品确认**，不阻塞入队/广播主线」。**从未确认** → 表无病例列。
2. **Story 3-5 第 123 行 `[OPEN-3]`**：「三屏经路由 `/consult/vet-request` 可达即可（**测试/临时入口**）；正式 Konsultasi Tab 入口整合在 **3-8**」。而 **3-8 实际拍板的范围边界**（`suspend_deadline_at` + 封禁分流 + 退款原语 + `@Scheduled` + 逃生入口 + 挂起横幅 + **文案定稿**）**不含入口整合** → 无人认领 → 付费路径的用户入口至今是孤儿路由。

Story 3-6 依计划把兽医端数据源从 `waitingList()`（旧路径）单向切到 `vetQueue()`（付费队列），断裂随即显形。

## 4. 现状事实

### 4.1 两条发起路径

| | V1.0 旧发起路径 | V1.1 付费路径 |
|---|---|---|
| 用户发起 | 直接建 `consult_sessions`(**WAITING**) | 建 `consult_requests`(QUEUEING) |
| 病例 | ✅ `ai_symptom_text`/`ai_image_refs`/`ai_danger_level`（V15） | ❌ 表无任何病例列 |
| 兽医端读 | `waitingList()`（WAITING）—— **调用 0 次** | `vetQueue()`（QUEUEING）—— **轮询中** |
| 接单后 | WAITING→IN_PROGRESS，**免费无订单** | ACCEPTED_AWAIT_PAY → 付款 → 删 request + 建 IN_PROGRESS 会话 + 订单 |
| 用户端入口 | ✅ 活的（分诊页 3 处） | ❌ 孤儿路由，UI 不可达 |

> `consult_sessions` **是两条路径共用的会话载体表，不下线**。要停用的只是「用户直接建 WAITING 会话」这条**发起**路径 + 兽医端 `waitingList()` 读取端。

### 4.2 `consult_requests` 全部列（无病例、无来源）

```
id · request_token · user_id · pet_profile_id · vet_id · state
queue_deadline_at · pay_deadline_at · paused_at · rebroadcast_count · created_at · updated_at
```

`ConsultRequestState` 仅 `QUEUEING` / `ACCEPTED_AWAIT_PAY`（无 PAID 态——付款成功即**删** request 转 session）。

### 4.3 关键实现现状

| 位置 | 现状 |
|---|---|
| `ConsultRequestController.create` | `@PostMapping` **无 `@RequestBody`** —— 连病例入参都没有（注释：「一人一宠，pet_profile 从 owner 派生、无需请求体」） |
| `ConsultPayService:177` | **硬编码 `ConsultSource.DIRECT`** —— 付费路径当前只能承接「用户直接发起」，无法承接分诊升级（见 D2） |
| `consult_case_form_page.dart:101` `_submit()` | 建 **WAITING session**（旧路径），第 112 行 `pushReplacement('/consult/waiting/${session.id}')` |
| 入口链 | `consult_entry_page:135` → `/consult/case` → 建 WAITING session → `/consult/waiting/{id}` —— **全程旧路径** |
| 指向 `/consult`（旧路径）的入口 | `triage_page.dart:84`、`triage_upload_page.dart:164`、`triage_result_view.dart:202` |
| 指向 `/consult/vet-request`（付费路径）的外部入口 | **无** |

### 4.4 参照系（照抄对象）

- **病例落库**（`V15__add_consult_ai_context.sql`）：
  ```sql
  ai_danger_level VARCHAR(8)   -- CHECK IN ('GREEN','YELLOW') —— 红色态零变现红线
  ai_symptom_text TEXT         -- 健康数据：日志严禁明文
  ai_image_refs   JSONB        -- 私密桶对象 key；不存签名 URL
  ```
- **自填病例复用同列**：`ConsultSession.bindDirectCase(symptomText, imageObjectKeys)` → 写 `ai_symptom_text`/`ai_image_refs`，`ai_danger_level` 留空。
- **兽医列表项隐私模式**（`VetInboxItem`）：只给 `symptomPreview`（**40 字截断**）+ `imageCount`（**只给数量，不给 URL**）；签名图另走 `GET /vet/consult-sessions/{id}/ai-context`。**付费队列必须照抄——列表端点绝不下发签名 URL。**
- **Flyway 现状**：最大 `V83` → 本次新迁移 **`V84`**（已提交迁移冻结，加列必须新起 `ALTER`，决策 E2）。

## 5. 目标流程（用户 2026-07-16 口述）

> 用户填写病例 → 发起排队 → **兽医看病例选择接单** → 接单后用户才付款 → 兽医端显示等待用户付款 → 付款成功进聊天室

对应付费路径状态机 `QUEUEING → ACCEPTED_AWAIT_PAY(1.5min 支付窗) → 付款成功删 request + 建 consult_sessions(IN_PROGRESS) + IM`——**状态机已实现且正确**，缺的是病例数据通路 + 用户入口。

---

## 6. 决策结论（2026-07-16 用户拍板）

### D1 · 兽医接单前能看完整病例 ✅ 已定

**结论：全部能看**——兽医在接单前可展开看完整症状 + 私密图（签名 URL）。

新增 `GET /api/v1/vet/consultations/{requestToken}/case`，复用 `ConsultAiContextResponse` DTO（`hasAiContext`/`dangerLevel`/`symptomText`/`imageUrls`），签名 URL **短 TTL 现签、绝不入库/落日志**（照 `ConsultAiContextService:39` `signedUrlService.signAll`）。

> ⚠️ **照抄 DTO 与签名逻辑，但绝不照抄寻址方式**。既有 `VetConsultContextController` 的
> `GET /vet/consult-sessions/{id}/ai-context` **无任何归属校验 + 自增 id 可枚举**——任何 VET 角色遍历 id
> 即可读全库病例与签名图，违反「对外暴露标识一律不可枚举 token」护栏（**既有弱点，本次范围外，另案处理**）。
> 新端点一律 **`requestToken` 寻址**（照 3-3 accept 端点范式），且请求不存在与无权限**返同一 404/409**（防枚举）。

### D2 · 分诊升级一并迁到付费路径 ✅ 已定

**结论：升级也一样**——`AI_UPGRADE` 与自填病例走同一条付费路径。连带改动：

- `consult_requests` 加 `source` 列（`DIRECT`/`AI_UPGRADE`）+ `triage_task_id`
- **`ConsultPayService:177` 硬编码 `ConsultSource.DIRECT` 必须改为读 request 的 source**，否则分诊升级付款后建的会话被错标 DIRECT、丢失 AI 上下文
- AI 上下文（等级/症状/图）由后端从 triage service 拉取组装（前端不重传，照 Story 5.4 既有约定）

### D3 · 不做存量过渡 ✅ 已定

**结论：不要**——不保留 `waitingList()` 临时入口，存量 `WAITING` 会话按既有超时逻辑自然消亡。

> ⚠️ **遗留风险（需知悉，非阻塞）**：旧版已发布 App 仍会调 `POST /consult-sessions` 建 WAITING 会话，
> 而兽医端已无人读 WAITING → **未升级用户发起问诊将静默无人接单**。是否需强制升级闸
> （`app_version_check.dart` / `kAppVersion` 既有机制）由发版时决定，本 spec 不含。

### 红线（不可协商，仅确认 dev 知悉）

`ai_danger_level` 的 CHECK 约束只允 `GREEN|YELLOW`——**RED 永不引流兽医**（红色态零变现，架构 Enforcement）。`consult_requests` 加列必须**同样加 CHECK 约束**，且 `createRequest` 服务端兜底拒绝 RED。**D1「全部能看」不放宽此红线**：RED 根本进不了队列，故不存在「兽医看到 RED 病例」的路径。

---

## 7. 实施规格（按 §6 已定结论：D1 全看 · D2 一并迁 · D3 不过渡）

### 7.1 后端

| # | 改动 | 层级 |
|---|---|---|
| B1 | **`V84__add_consult_request_case.sql`**：`consult_requests` 加 `source VARCHAR(16) NOT NULL DEFAULT 'DIRECT'` / `symptom_text TEXT` / `image_object_keys JSONB` / `ai_danger_level VARCHAR(8)` / `triage_task_id BIGINT` + `CHECK (ai_danger_level IS NULL OR ai_danger_level IN ('GREEN','YELLOW'))`。**照抄 V15 注释风格**（健康数据/私密 key 标注） | L1 |
| B2 | `ConsultRequest` domain 加 5 字段 + `bindCase(...)`（照 `ConsultSession.bindDirectCase`）。⚠️ JSONB↔`List<String>` 照 `ai_image_refs` 既有映射；`VARCHAR` 列勿踩 Hibernate `CHAR(1)` 陷阱 | L0 |
| B3 | 新建 `CreateConsultationRequest(String source, Long triageTaskId, String symptomText, List<String> imageObjectKeys)` record + Bean 校验（**照 `CreateConsultSessionRequest` 同款签名**，保持两路径入参一致） | L0 |
| B4 | `ConsultRequestController.create` 加 `@RequestBody`（**破坏性变更**：现有无 body 调用方需同步改，见 F1） | L0 |
| B5 | `ConsultRequestService.createRequest` 落病例 + `AI_UPGRADE` 时从 triage service 拉 AI 上下文（照 5.4）+ **RED 兜底拒绝** | L1 |
| B6 | **`ConsultPayService:177` 硬编码 `ConsultSource.DIRECT` 改为读 `req.getSource()`**；`AI_UPGRADE` 时同步把 `triageTaskId`/AI 上下文带进新建的 session（照 5.4 `bindAiContext`） | L1 |
| B7 | `VetQueueResponse.VetQueueItem` 加 `aiDangerLevel` / `symptomPreview`(40 字截断) / `imageCount`——**照 `VetInboxItem`；列表端点绝不下发签名 URL**（完整病例走 B10） | L0 |
| B8 | `vetQueue()` 组装病例摘要（现有 `pool.stream().map(...)` 处） | L1 |
| B10 | **新增 `GET /api/v1/vet/consultations/{requestToken}/case`**（D1）：`hasRole('VET')` + **`requestToken` 寻址**（不可枚举；不存在/无权限返同一码防枚举）→ 复用 `ConsultAiContextResponse`，图走 `signedUrlService.signAll` 短 TTL 现签。**勿照抄 `VetConsultContextController` 的自增 id + 无归属校验写法**（既有弱点） | L0 + L1 |
| B9 | **注销匿名化补齐**（Story 7.3 / 决策 D1）：`consult_requests` 新增的健康列必须纳入匿名化清洗——照 `ConsultAnonymizationService` 对 `ConsultSession` 的处理。**漏此项=合规缺口** | L1 |

### 7.2 前端

| # | 改动 | 层级 |
|---|---|---|
| F1 | `consult_case_form_page.dart:101` `_submit()` 改调 `POST /api/v1/consultations`（带病例）→ 跳 `/consult/vet-request/waiting/{requestToken}`（原为建 session → `/consult/waiting/{id}`） | L0 |
| F2 | 分诊三入口改道：`triage_page.dart:84`、`triage_upload_page.dart:164`、`triage_result_view.dart:202` → 付费路径（**页面顺序待定，见下方⚠️**） | L0 |
| F3 | `VetQueue` model + `VetQueueItem` 加病例字段（`vet_queue.dart`） | L0 |
| F4 | 兽医队列卡 `_QueueCard` 从「身份+时长」改**富卡**（等级色条 + 症状摘要 + 图数量徽章）——Story 3-6 曾明令「不复用 `_InboxCard` 富卡」，**本次推翻该约束**（因表已补病例列），dev 需知悉此为有意变更 | L0 + L2 |
| F5 | **队列卡加「看病例」展开**（D1）：调 B10 新端点拉完整症状 + 签名图，接单前可看。复用既有 `ConsultAiContext` model（`vet_repository.aiContext` 同款 fromJson） | L0 + L2 |
| F6 | i18n：新 key 走 `app_id.arb`（印尼语主）+ `app_en.arb`，改后必 `flutter gen-l10n`；`microcopy_rules_test` 必绿 | L0 |

> ⚠️ **页面顺序是 UX 决策，建议出图前不动 F2**：现有付费三屏（`vet_request_confirm` → `waiting` → `pay`）**没有病例表单环节**，而病例表单 `/consult/case` 是独立页。需明确病例表单插在 `vet_request_confirm` 之前还是合并进去。

### 7.3 不做（范围外）

`consult_sessions` 会话载体本身、`VetActivePage`/`VetHistoryPage`、后端接单/支付/回退逻辑（3-3/3-4 已实现且正确）、订单中心、GemPay 打款。

---

## 8. 验证

| 层 | 内容 | 环境 |
|---|---|---|
| **L0** | `flutter analyze` 零警告 · `flutter test` 全绿（含 queue 富卡 widget 测试 + `microcopy_rules_test`）· `mvn -B test-compile` | 云端可跑 |
| **L1** | Flyway V84 迁移 + `ddl-auto=validate` 通过 · `createRequest` 落病例 + RED 拒绝集成测试 · `vetQueue` 返回病例摘要 · **`AI_UPGRADE` 付款后 session 的 source/triageTaskId 正确**（B6 回归） | 本地 scratch 库（**先 flush Redis DB0**，否则 id 从 1 重启撞陈旧幂等键→假回归） |
| **L2** | 真机/模拟器全链路 ×2 条来源：<br>① **自填病例**：填病例→排队→兽医端看到富卡→**展开看完整症状+图**（D1）→接单→用户付款→兽医端显等待付款→付款成功进聊天室<br>② **分诊升级**（D2）：分诊结果页「找兽医」→同上链路→**付款后会话 source=AI_UPGRADE 且 AI 上下文卡正常渲染**（B6 回归） | 本地 Android 模拟器 + stag 后端 |

### 云端执行须知

- ✅ 云端只跑到 **L0 绿灯**，在 Completion Notes 标注「L1/L2 待本地验收」。
- ⚠️ **改迁移后须 `test-compile` 重拷资源**（surefire 会用旧的）。
- ⚠️ Flyway 号 **V84** 已按当前 `v1.1-dev` 最大号（V83）顺延；**若并行工作线也在加迁移，开工前重新核号**（并行撞号是已知坑）。
- 分支：`v1.1-dev`（V1.1 改动一律不在 `stag` 直接改）。

---

## 9. 影响面提示

- **生产是否同样断裂未核实**——取决于 Epic 3 是否已合入 `main`。若已合入，生产用户的问诊同样无人能接，属线上事故级，需优先确认。
- **旧版 App 遗留**（D3 连带）：已发布客户端仍会建 WAITING 会话而无人接单，是否加强制升级闸留发版决定。
- **既有安全弱点（另案）**：`VetConsultContextController` 的 `GET /vet/consult-sessions/{id}/ai-context` 无归属校验 + 自增 id 可枚举 → 任何 VET 可遍历读全库病例与签名图。本 spec 的新端点已规避（token 寻址），但**既有端点仍需另案修复**。
- 本次是**规划缺口补漏**，非单纯 bug 修复。建议把 D1~D3 的结论回写 Story 3-2 `[OPEN]` / 3-5 `[OPEN-3]`，避免再次掉棒。
