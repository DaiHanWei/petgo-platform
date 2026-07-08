---
title: 举报处置增强 — P0/P1/P2 阈值分级 + 自动预处置 + 举报者隐藏
type: spec-story
slug: content-moderation-report-triage
story_seq: 6/9
status: ready-for-dev
source:
  - _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md（§5.1 / §8.9 / §3.1↔§5.1 关系）
  - _bmad-output/implementation-artifacts/spec-content-moderation-overview.md（D-CM2 / D-CM6 / D-CM7）
created: 2026-07-08
owner: Dai
communication_language: 中文
relatedEpics: [Epic 3 内容社交, Admin, Epic 6 通知]
depends_on:
  - story2 帖子审核可见性新模型（spec-content-moderation-post-review.md）—— 提供 content_posts「仅作者可见待判」挂起态与 Feed/详情对他人的过滤
flyway_placeholder: V52   # 实际号按 CI 合并顺序单调顺延，勿硬编码
verification_layers: [L0, L1, L2]
---

# 举报处置增强 — P0/P1/P2 阈值分级 + 自动预处置 + 举报者隐藏

> 本 story 是「内容审核补充规范 v1.0.1」落地的第 6 份（overview §4 story 6）。
> **权威源**：方案 §5.1（工单优先级与自动预处置）、§8.9（举报驱动下架通知）、§3.1↔§5.1 关系说明；代码契约以 `CROSS-STORY-DECISIONS.md` 为准。
> 与用户沟通用中文。

---

## 1. 背景与范围（增量边界）

举报能力**不是从零建**。后端已实现帖子级举报、按用户去重、写工单 PENDING、Admin 队列读取/处理/批量下架（file:line 见 §2）。当前明确设计为**全人工、无阈值分级、无自动下架、无举报者隐藏**（`ReportService.java:22-25,38`）。

本 story 只做**增量**，落地方案 §5.1 的举报驱动自动预处置与去重不变量：

1. **阈值分级（P0/P1/P2）**——按同一内容**去重后唯一举报用户数**给工单定优先级与目标响应时间。
2. **P0 自动预处置**——P0（法律违规经举报/巡查发现，或唯一举报用户数 ≥ 10）**自动转「仅作者可见待判」**（复用 story2 挂起态），2 小时内人工判定；P1（3–9）不自动下架、维持展示、24h 判定；P2（中风险 / 单次举报）不自动、维持展示、72h 处理。
3. **举报后对举报者隐藏**——用户举报某帖后，该帖**立即对该举报者本人隐藏**（Feed / 详情按举报者过滤）。这与「每人对同一内容仅能举报一次」（已实现唯一约束）叠加，使**举报次数天然 = 去重后唯一举报用户数**（D-CM7），阈值 P0≥10 / P1 3–9 合法可靠。
4. **判违规 → 下架通知作者**（复用 `CONTENT_REMOVED`，方案 §8.9 文案）；**判误报 → 自动恢复展示、不通知**（D-CM6）。
5. **移除「三方高风险 ≥ 0.8」P1 触发行**——该情形已由提交时 FR-12A（story2）拦截，帖子从未公开发布，不会「已发布后因高风险自动下架」。本 story 的分级表**仅举报驱动**（方案 §5.1 已明确移除该行）。

**明确不做（本 story 边界外）：**
- **评论举报 / 账号举报**——评论无举报入口（V1.2.0 FR-57）、账号无举报入口（V1.2.0 FR-58）。因此**不存在评论 / 账号的举报驱动 P0/P1/P2 队列**；评论侧对应能力是 §3.2 FR-55A 巡查下架（story3），账号侧不做。
- **不改举报人侧模糊通知**——`REPORT_REVIEWED` 保持（story7 收口文案 i18n）。本 story 不新增举报人通知。
- **不改三方审核 / 挂起态本体**——挂起态可见性机制由 story2 提供，本 story 只是把 P0 帖子**送入**该挂起态。

---

## 2. 现状基线（file:line，复用勿重建）

| 现状能力 | 证据 | 本 story 动作 |
|---|---|---|
| 帖子级举报提交 + 存在性校验 + **幂等去重** | `moderation/service/ReportService.java:39-52`（`isVisible` 校验 L41；`existsByPostIdAndReporterId` 幂等 L44；并发撞唯一约束吞掉 L49-51） | 复用；隐藏在提交事务内追加（§5.3） |
| 每用户对同帖唯一约束 | `V11:17`（`uq_content_reports_reporter_post UNIQUE (post_id, reporter_id)`） | 复用（去重不变量的基石，D-CM7） |
| 举报次数统计（后台展示） | `ReportService.java:71-73` `countForPost`；`ContentReportRepository.countByPostId` | 复用为分级输入（此计数 = 唯一用户数，见 §5.1） |
| 工单状态机 PENDING/RESOLVED/DISMISSED | `ContentReport.java:37-39,66-70`；`ReportStatus`（PENDING/RESOLVED/DISMISSED） | 复用；分级不改工单状态机，另加 post 级预处置 |
| 举报原因枚举 | `ReportReason`（ILLEGAL/MISINFO/INAPPROPRIATE/HARASSMENT/OTHER）；`V11:9` | 复用；`ILLEGAL` 是 P0「法律违规」判定输入之一 |
| 下架时批量结单 | `ReportService.java:88-96` `resolvePendingForPost` | 复用（判违规下架后收尾 PENDING 单） |
| Admin 人工下架 / 驳回 / 批量 | `AdminModerationService`（overview §2 已核实存在）；`AdminContentManageService`（巡查下架 / 恢复） | 复用；P0 判违规走既有下架路径 |
| Feed 公开口径查询 | `content/repository/ContentPostRepository.java:85-104` `findFeed`（`deletedAt IS NULL AND status=PUBLISHED` + 游标） | **增量**：追加「排除当前查看者已举报的帖」子句（§5.4） |
| 举报处理闭环 → 举报人模糊通知 | `notify/service/ModerationNotifyListener.java:23-28`（消费 `ReportResolvedEvent` 发 `REPORT_REVIEWED`；文案对下架/驳回**一致**、不透露结果） | 复用不改（本 story 不动举报人通知） |
| 作者下架通知类型 | `NotificationType.CONTENT_REMOVED`（`V43` 通知类型扩展；方案 §8.9） | 复用（判违规下架通知作者） |
| 挂起态「仅作者可见待判」 + 他人过滤 | **story2 交付**（`spec-content-moderation-post-review.md`，V48） | **依赖**（P0 送入此态，见 §8） |

> **关键事实**：`ReportService.submit` 当前**只写工单、绝不改内容可见性**（L38 注释 + L47-48）。本 story 在此方法内**追加**「对举报者隐藏」与「达阈值时触发 P0 预处置」，其余逻辑不动。

---

## 3. 目标与非目标

**目标**
- G1：举报提交后，被举报帖**立即对该举报者隐藏**（Feed 列表 + 帖子详情 + 任何对外展示位）。
- G2：同一帖的**去重唯一举报用户数**达阈值时按 §5.1 分级：P0（≥10 或法律违规经举报/巡查）自动预处置转挂起、2h SLA；P1（3–9）不自动、24h；P2（单次 / 中风险）不自动、72h。
- G3：P0 预处置**转「仅作者可见待判」**（复用 story2 挂起态），对除作者外所有人消失、无「审核中」标签、内容不删除。
- G4：人工判违规 → 永久下架 + `CONTENT_REMOVED` 通知作者（§8.9）；判误报 → 自动恢复展示、**不通知**（D-CM6）。
- G5：分级表**仅举报驱动**——不含「三方高风险 ≥0.8」行（已由 story2 提交时拦截）。

**非目标**
- N1：**不做评论举报、不做账号举报**（V1.2.0 FR-57/58）——本 story 只处理**帖子级**举报。
- N2：**不做举报人进度查询 / 申诉入口**（方案 §5.5：V1.1.0 无申诉）。
- N3：**不改举报人侧模糊通知文案**（story7 统一 i18n）。
- N4：**不实现队列 priority 列 UI / 后台巡查动作**（story8 owns 后台增强、V54 priority 列）；本 story 只保证 P0 项以可被后台识别的方式进入既有人工队列。
- N5：**不做账号维度违规计数**（story9）。
- N6：brigading 防刷不做算法识别——P1（3–9）**故意不自动下架**以避免群体刷量误伤（§10 R1）。

---

## 4. 数据与迁移（V52 delta）

> Flyway 冻结至 V46（overview §5）；本 story 增量占位 **V52**，一律新起 `ALTER`，实际号 CI 落地按合并顺序单调顺延。`ddl-auto=validate`：schema 归 Flyway，实体只映射不建表。

### 4.1 「举报者隐藏」——无需新列（由 `content_reports` 存在性推导）

举报者隐藏的判据**就是** `content_reports` 里「该 reporter 对该 post 存在举报记录」。因此**不新增隐藏标志列**——`举报即写记录`，`记录存在即隐藏`，二者天然同源，也正是 D-CM7 去重不变量成立的原因（隐藏后举报者再也刷不到该帖 → 不可能重复举报）。

Feed/详情过滤用既有唯一索引 `uq_content_reports_reporter_post (post_id, reporter_id)`（`V11:17`）即可高效支撑相关子查询（相关列 `post_id` 前导，正好命中）。

### 4.2 V52 迁移内容（占位）

```sql
-- V52__report_triage_auto_hidden.sql（占位号；实际号顺延）
-- Story 6：举报驱动 P0 自动预处置标记。举报者隐藏无需新列（见 §4.1）。

-- 举报驱动 P0 自动预处置：帖子从 PUBLISHED 转入 story2「仅作者可见待判」挂起态的时刻(UTC)。
-- 用途：① 兼作 2h SLA 计时起点；② 区分「发布时高风险挂起(story2)」与「已发布后举报驱动挂起(本 story)」，供后台队列展示来源。
-- NULL = 未因举报被预处置。挂起态可见性本身复用 story2 的 content_posts 审核状态列，不在此新增。
ALTER TABLE content_posts
    ADD COLUMN report_hidden_at TIMESTAMPTZ;

COMMENT ON COLUMN content_posts.report_hidden_at IS
    '举报驱动 P0 自动预处置转挂起(仅作者可见待判)的时刻(UTC);NULL=未预处置;2h SLA 起点+来源区分';

-- 可选：举报者维度反查索引(供「我举报过/被隐藏的帖」反查;若 Feed 相关子查询已够则可省)。
-- 与 uq_content_reports_reporter_post(post_id,reporter_id) 前导列相反,覆盖 reporter 前导的查询。
CREATE INDEX idx_content_reports_reporter ON content_reports (reporter_id, post_id);
```

> **勿新增**：`hidden_from_reporter` 之类布尔列（§4.1 说明会与 content_reports 冗余/失同步）；`report_priority` 快照列（优先级由计数实时派生，story8 若需持久化 priority 走 V54）。length=1 列一律不建（无本 story 相关列，规避 Hibernate CHAR(1) 坑）。

---

## 5. 后端设计

### 5.1 阈值分级（P0/P1/P2）——仅举报驱动

修订后的分级表（方案 §5.1，**已移除「三方高风险 ≥0.8」行**）：

| 优先级 | 触发条件（举报驱动） | 自动预处置 | 目标响应 |
|---|---|---|---|
| **P0（紧急）** | 法律违规内容（经**举报**含 `ILLEGAL` 原因 / 运营**巡查**发现；非提交时自动评分命中）**或** 同一内容去重唯一举报用户数 **≥ 10** | **系统自动下架 → 转「仅作者可见待判」**，用户端立即不展示 | 2h 内判定 |
| **P1（高）** | 同一内容去重唯一举报用户数 **3–9** | **不自动下架**，维持现状展示 | 24h 内判定 |
| **P2（普通）** | 三方中风险标注（0.6–0.8）**或** 单次举报 | **不自动下架**，维持现状展示 | 72h 内处理 |

**计数即唯一用户数**：`ReportService.countForPost(postId)` = `content_reports` 该帖行数。因唯一约束 `(post_id, reporter_id)` + 举报即隐藏（举报者刷不到 → 无重复举报动机与可能），该计数**天然 = 去重后唯一举报用户数**，可直接与 3 / 10 阈值比较（D-CM7），无需额外 DISTINCT 或防刷逻辑。

**优先级派生（不落库）**：优先级是**读时派生的分类**，不持久化到 `content_reports`（§4.2 说明）。后台队列展示时用「计数 + 是否含 `ILLEGAL` 原因 + 是否巡查」实时映射到 P0/P1/P2；SLA（2h/24h/72h）随之派生。

### 5.2 P0 自动预处置状态机（复用 story2 挂起态）

只有 **P0** 触发自动动作。判定与流转：

```
举报写入成功后（同事务提交后 / 或提交时同步计算）:
  cnt = countForPost(postId)            // 含本次
  isLegalReport = 本次或历史举报含 ReportReason.ILLEGAL
  if (帖子当前 status == PUBLISHED
        && report_hidden_at IS NULL      // 未预处置(幂等,避免重复入队)
        && (cnt >= 10 || isLegalReport)):
      → 触发 P0 自动预处置:
         1. content_posts.status: PUBLISHED → <story2 挂起态>（仅作者可见待判）
         2. content_posts.report_hidden_at = now()（UTC，2h SLA 起点）
         3. 入既有人工审核队列（manual_review_queue, V41），标来源=举报/P0
         4. 不推送通知（预处置是中间态,非最终判定; D-CM6 只对最终负向结果通知）
  P1/P2:
      → 不改内容可见性，仅工单在队列按计数派生的优先级排序（story8 展示）
```

> **P0「仅作者可见待判」= story2 挂起态**：P0 帖子**已 PUBLISHED**，预处置是把它从 PUBLISHED **翻回**挂起态（story2 引入的审核状态值，如 `PENDING_REVIEW`）——他人过滤、无「审核中」标签、作者仍可见，全部复用 story2 已实现的可见性规则（D-CM2）。内容**不删除**（`deleted_at` 保持 NULL），仅暂停对外展示。
>
> **巡查 P0**：运营在后台对某帖标「法律违规」时同样走本预处置（复用 `AdminContentManageService` 巡查下架 → 挂起 / 或直接永久下架，按运营选择）。巡查 P0 不依赖举报计数。

**人工判定后果（2h 内，走既有 Admin 下架路径）：**
- **判定违规** → 永久下架（`status → REMOVED` / 既有下架语义），`resolvePendingForPost` 结掉该帖 PENDING 举报单，发 `CONTENT_REMOVED` 通知作者（§5.5）。
- **判定误报** → 恢复展示（`status → PUBLISHED`，清 `report_hidden_at`），**不通知**（D-CM6）；已举报的用户仍保持对其隐藏（其举报记录不撤，隐藏不变——恢复是「对他人恢复」，不改举报者本人视角）。

### 5.3 举报者隐藏（在 `ReportService.submit` 内追加）

在 `submit(postId, reporterId, reason)`（`ReportService.java:39-52`）**同一事务**内，写工单**之后**：
- **无需额外写操作**：隐藏判据 = 「该 reporter 对该 post 的 `content_reports` 记录已存在」（本次刚写入）。即「写工单」本身就完成了「对举报者隐藏」的数据落地（§4.1）。
- 幂等场景（`existsByPostIdAndReporterId` 命中 → L44 早返回 / 并发撞唯一约束 → L49 吞掉）下隐藏依然成立（记录已在），无副作用。

因此**隐藏是零新增写**——真正的改动全在**读路径过滤**（§5.4）。`submit` 里唯一新增的是 §5.2 的 P0 阈值判定与预处置触发。

### 5.4 Feed / 详情按举报者过滤（读路径）

**Feed 列表**（`ContentPostRepository.findFeed`, `:85-104`）追加一条相关子查询，排除「当前查看者已举报的帖」：

```jpql
... 现有 WHERE ...
AND NOT EXISTS (
    SELECT 1 FROM ContentReport r
    WHERE r.postId = p.id AND r.reporterId = :viewerId
)
```
- 新增 `@Param("viewerId") Long viewerId`（当前登录用户 id）。相关子查询命中 `uq_content_reports_reporter_post(post_id, reporter_id)`，per-post O(1)。
- **游客 / 未登录**：`viewerId` 传一个不可能命中的值（如 `-1` 或 `null` 配 `(:viewerId IS NULL OR NOT EXISTS ...)`；沿用 findFeed 现有「布尔标志避免 42P18」惯例，避免裸 `:viewerId IS NULL` 令 PG 无法推断类型）。

**帖子详情**（举报者直接点进该帖 / 深链）：详情查询同样叠加「若 `viewerId` 已举报该帖 → 视同不可见」，返回 RFC9457 `404 内容不存在`（与 `ReportService` L41-42 `isVisible` 语义一致——对举报者而言该帖「已不存在」）。

**作者本人**：作者对自己的帖不受影响（作者一般不会举报自己；即便如此，「我的发布」走 `findMyPosts`，不叠加举报过滤）。

**不影响他人**：其他未举报用户照常可见（除非该帖因 P0 已转挂起 / 已下架，那走 story2/既有可见性规则，与本过滤正交）。

### 5.5 通知

| 事件 | 通知对象 | 类型 | 文案 | 依据 |
|---|---|---|---|---|
| P0 自动预处置（转挂起待判） | —— | **不推送** | —— | 中间态非最终；D-CM6 |
| 人工**判违规**永久下架 | 内容作者 | `CONTENT_REMOVED`（复用） | 方案 §8.9「你的内容已被隐藏 / Kontenmu telah disembunyikan」（文案 i18n 由 story7 落 arb） | §8.9 / D-CM6 |
| 人工**判误报**恢复展示 | —— | **不推送** | —— | 正向结果不通知；D-CM6 |
| 举报处理闭环（举报人侧） | 举报人 | `REPORT_REVIEWED`（既有，不改） | 模糊「举报已处理」（`ModerationNotifyListener.java:25-27`） | 现状复用 |

> `CONTENT_REMOVED` 的**具体印尼语文案 / arb 键**由 story7 统一落地；本 story 只负责**在判违规下架时触发该类型**（复用既有下架 → 通知链路，不新增通知类型、不新增 Flyway 通知 CHECK）。

---

## 6. 前端设计（Flutter）

前端改动小——核心是**举报后本人视角内容消失** + Feed 过滤由后端权威完成，前端只需正确响应。

### 6.1 举报后本人视角内容消失
- 现状举报入口（帖子详情 / Feed 卡片 overflow 菜单「举报」）提交成功后：
  - **乐观移除**：从当前 Feed 列表本地移除该卡片（或标记 hidden），给「已举报，将不再向你展示此内容」轻提示（toast），无需等下次刷新。
  - **详情页举报**：提交成功后 pop 回上一页（该帖对本人已「不存在」），不再停留在详情。
  - **再次进入**：下拉刷新 / 重新拉 Feed 时后端已过滤（§5.4），该帖不再返回；若用户仍持有深链直接进详情 → 后端 404 → 前端走既有「内容不存在 / 已失效」占位页（规格页面多态：不存在态）。
- **文案**：沿用既有举报成功提示，可加一句「你将不再看到此内容」（i18n 键与文案由 story7 统一；本 story 若需临时键，先 en/id 占位并在 Completion Notes 记录待 story7 复核）。

### 6.2 Feed 过滤（无端上逻辑）
- Feed 分页/游标逻辑不变；过滤是后端 WHERE，前端**不做**本地举报名单维护（避免与后端不一致）。前端仅需保证登录态下请求带上当前用户身份（既有 JWT，后端取 `viewerId`），游客态照常（后端按未登录处理）。

### 6.3 不做
- 不做「我举报过的内容」列表页（非目标 N2）。
- 不做举报进度 / 申诉 UI（N2）。
- 不显示任何「审核中」「已被举报 N 次」等状态给普通用户（D-CM2：无「审核中」标签；举报计数仅后台可见）。

---

## 7. 验收标准（AC）

> 每条标验证层级：**L0**（静态：`flutter analyze`/`test`、`mvn -B compile|package`，无需 DB）｜**L1**（集成：Docker postgres+redis 真跑 + Flyway validate + 接口）｜**L2**（端到端：真机/模拟器视觉、多账号）。云端只跑 L0；L1/L2 默认回本地/CI（见 §9）。

### 后端 AC

- **AC-B1（L1）迁移干净**：V52 `ALTER content_posts ADD report_hidden_at TIMESTAMPTZ` + `idx_content_reports_reporter` 应用后，`mvn spring-boot:run` 启动、`ddl-auto=validate` 通过、`/actuator/health=UP`。字段为 `TIMESTAMPTZ` 可空、无 CHAR(1) 类列。*环境：Docker postgres。*
- **AC-B2（L0）举报者隐藏零新增写**：`ReportService.submit` 改动后，写工单逻辑与幂等分支（`existsBy...` 早返回、`DataIntegrityViolationException` 吞掉）保持不变；不新增隐藏标志写。`mvn -B package` 绿、既有 ReportService 单测不回归。*环境：无。*
- **AC-B3（L1）Feed 对举报者过滤**：账号 A 举报帖 P → A 再拉 Feed **不含** P；账号 B（未举报）拉 Feed **仍含** P。相关子查询走 `uq_content_reports_reporter_post`。*环境：postgres + 2 用户 JWT。*
- **AC-B4（L1）详情对举报者 404**：A 举报 P 后，A `GET /api/v1/content-posts/{P}` 返回 RFC9457 `404`（`title/status/detail`，不泄堆栈）；B 仍 `200`。*环境：postgres。*
- **AC-B5（L1）P1/P2 不自动下架**：同帖被 3–9 个不同用户举报 → 帖 `status` 保持 `PUBLISHED`、`report_hidden_at` 保持 NULL、对未举报第三方仍可见（仅工单累积）。单次举报（P2）同样不改可见性。*环境：postgres + 多用户。*
- **AC-B6（L1）P0 达 10 自动预处置**：同帖被第 10 个不同用户举报（含本次达 10）→ 帖 `status` 转 story2 挂起态、`report_hidden_at` 被写入、入既有人工队列（来源标举报/P0）、**未发任何通知**；对所有非作者用户不可见、作者本人仍可见、无「审核中」标签。幂等：第 11 个举报不重复入队（`report_hidden_at` 已非空）。*环境：postgres + 多用户 + story2 挂起态已就绪。*
- **AC-B7（L1）P0 因 `ILLEGAL` 原因预处置**：单个 `ILLEGAL` 原因举报即触发 P0 预处置（不必等 10 次），流转同 AC-B6。*环境：postgres。*
- **AC-B8（L1）判违规 → 下架 + 通知作者**：P0 挂起帖被 Admin 判违规 → 永久下架、该帖 PENDING 举报单经 `resolvePendingForPost` 全置 RESOLVED、作者收到 `CONTENT_REMOVED` 通知；举报人侧 `REPORT_REVIEWED` 照常（既有链路）。*环境：postgres + admin。*
- **AC-B9（L1）判误报 → 恢复不通知**：P0 挂起帖被 Admin 判误报 → `status` 回 `PUBLISHED`、`report_hidden_at` 清空、对他人恢复可见；**作者无任何通知**；原举报者仍对其隐藏（举报记录未撤）。*环境：postgres + admin。*
- **AC-B10（L0）分级表仅举报驱动**：代码/常量中 P0/P1/P2 触发条件**不含**「三方高风险 ≥0.8 自动下架」路径（已由 story2 提交时拦截）；`mvn -B package` 绿。*环境：无。*

### 前端 AC

- **AC-F1（L0）静态绿**：`flutter analyze` 无 error、`flutter test` 绿。新增/改动的举报成功回调无 setState-arrow-returns-Future 陷阱。*环境：无。*
- **AC-F2（L2）举报即消失（Feed）**：模拟器上 A 在 Feed 卡片举报帖 P → 卡片即时移除 + 「不再向你展示」提示；下拉刷新后 P 不复现。*环境：Android 模拟器 + 真后端(api.tailtopia.id)+ 真登录。*
- **AC-F3（L2）举报即消失（详情）**：A 在帖 P 详情页举报 → 提交成功后 pop 回列表，P 不在列表；持深链再进 P → 命中「内容不存在/已失效」占位页（不白屏、不崩）。*环境：模拟器 + 真后端。*
- **AC-F4（L2）他人不受影响**：同帖 P 在账号 B（未举报）设备上正常可见、可交互。*环境：模拟器 + 双账号。*

### 跨端联调 AC

- **AC-X1（L2）阈值端到端**：用多个真实账号对同一帖举报，累计到 10 时该帖对**所有人**（含从未举报的第三方）消失、作者仍可见无标签；Admin 后台 2h 队列出现该 P0 项；判违规后作者收到「你的内容已被隐藏」通知。*环境：多账号 + 后台 + 真后端。*

---

## 8. 依赖与契约

- **强依赖 story2（帖子审核可见性新模型）**：本 story 的 P0 预处置**复用** story2 引入的 content_posts「仅作者可见待判」挂起态（审核状态值 + Feed/详情对他人过滤 + 无「审核中」标签，D-CM2）。**story2 未落地前，本 story 的 P0 自动预处置无法联调**（AC-B6/B7/X1 需 story2 就绪）；举报者隐藏（AC-B3/B4）与 P1/P2（AC-B5）不依赖 story2，可先行。
- **复用 `content_reports`（V11）+ `ReportService`**：不新建举报表、不改工单状态机、不改举报人模糊通知。唯一约束 `uq_content_reports_reporter_post` 是去重不变量（D-CM7）与隐藏判据（§4.1）的共同基石，**勿删勿改**。
- **复用既有 Admin 下架 / 恢复路径**：判违规走 `AdminModerationService` 下架 + `resolvePendingForPost` 收尾；判误报走 `AdminContentManageService` 恢复语义。本 story 不新写下架/恢复实现。
- **复用 `CONTENT_REMOVED` 通知类型**：不新增通知类型、不新增通知 CHECK 迁移（story7 负责文案 i18n）。
- **与 story8（后台增强）契约**：队列 `priority` 持久化列由 story8 V54 落；本 story 只保证 P0 项以「`report_hidden_at` 非空 + 在人工队列」的形式**可被后台识别并按 2h SLA 派生优先级**。若 story8 先于本 story，priority 列可直接读；若本 story 先行，后台先用派生优先级展示。
- **命名/接口契约**：DB `snake_case`（`report_hidden_at`）↔ 实体 `reportHiddenAt`（camelCase）；API `/api/v1`，举报端点沿用既有 `POST /api/v1/content-posts/{postId}/reports`（不新增端点）；错误 RFC9457 ProblemDetail；日志 SLF4J JSON **禁记** PII / 举报原文 / 被举报内容 / token。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0）**：`mvn -B package`（编译 + 单测，含 ReportService 改动、findFeed JPQL 语法）、`flutter analyze` + `flutter test`。AC-B2 / AC-B10 / AC-F1 云端可绿。
- ⚠️ **L1 留本地 / CI**：V52 迁移 + `ddl-auto=validate` + 相关子查询真跑（AC-B1/B3/B4/B5/B6/B7/B8/B9）需 Docker postgres；云沙箱不保证 Docker daemon。schema 契约以 CI/L1 绿为准（Hibernate 类型坑只有 L1 暴露）。JPQL `NOT EXISTS` 相关子查询与游客 `viewerId` 判空写法务必在 L1 验 PG 不报 42P18。
- ❌ **L2 必回本地**：AC-F2/F3/F4 / AC-X1 需 Android 模拟器 + 多真实账号 + 真后端（api.tailtopia.id）+ 真 Google 登录（连桩/mock 会把过滤请求静默吞成兜底，见 memory 测试连正式后端）。
- Completion Notes 标注「L1/L2 待本地/CI 验收」，未联 story2 的 P0 相关 AC 标「待 story2 合入后联调」。

---

## 10. 风险与待确认

- **R1 — brigading（群体刷量）**：举报计数虽按唯一用户去重（唯一约束 + 举报即隐藏），仍可能被有组织群体刷到 ≥10 触发 P0 自动挂起 → 误伤正常内容。**缓解**：P0 是**转挂起待判 2h、内容不删除**（非永久删除），最终由人工判违规才永久下架、判误报自动恢复；P1（3–9）**故意不自动下架**（方案 §5.1 明示），把刷量风险最高的中间区间留给人工。**残余风险**：恶意群体在 2h 窗口内令目标内容对外消失（DoS-of-visibility）——V1 接受此权衡，观察后如高发再引入举报者信誉/速率因子（记 §10 待 V1.2）。
- **R2 — 「法律违规」P0 判定口径**：`ReportReason.ILLEGAL` 是用户自报原因，可能被滥用为「一票挂起」。**决策点**：单个 `ILLEGAL` 举报即触发 P0 预处置（§5.2 AC-B7）是否过激？备选——`ILLEGAL` 仅提升队列优先级但不单独触发自动挂起，自动挂起仍以 ≥10 为准。**待与 PM/运营确认**（方案 §5.1 P0 行文含「法律违规内容（举报/巡查发现）」，倾向单举报即 P0，但建议巡查为主、用户自报 `ILLEGAL` 从严）。**默认按方案：`ILLEGAL` 举报触发 P0**，若确认从严则改为「仅计数 ≥10 触发自动挂起，`ILLEGAL` 只置顶队列」。
- **R3 — P0 判定时机（同步 vs @Async）**：达阈值判定与挂起流转放在 `submit` 同事务同步做，还是发事件异步做？**建议同步**（阈值判断廉价、需与举报写入强一致避免竞态漏触发），符合护栏「异步只用 @Async + DB 状态机、禁 MQ」——本判定无需异步。**待确认**是否有性能顾虑（举报 QPS 极低，判定 O(1)，倾向同步）。
- **R4 — story2 挂起态字段名/枚举值未定**：本 story 引用 story2 的「仅作者可见待判」状态但未固化其列名/枚举值。**约定**：以 story2 spec 落地的 `content_posts` 审核状态列为准，本 story 实现时对齐该值（Completion Notes 记录实际引用的枚举名）。
- **R5 — 恢复后举报者仍隐藏是否符合预期**：判误报恢复后，原举报者仍看不到该帖（举报记录不撤）。**判断**：符合「举报即对本人隐藏」语义（用户主动表达不想看）——恢复是「对他人恢复」，非「撤销举报者选择」。**确认**此为期望行为（倾向保留，与方案 §5.1「举报后立即对举报者隐藏」一致，不因误报而强制其重新可见）。
