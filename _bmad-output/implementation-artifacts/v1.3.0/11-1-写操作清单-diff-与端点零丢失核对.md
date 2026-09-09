---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 11
story: 11.1
ad: [AD-12]
decisions: [D-8, D-23, D-27]
depends_on: [2-1, 全部 Epic 1~10]
---

# Story 11.1: 写操作清单 diff 与端点零丢失核对

Status: ready-for-dev

> 自包含 story，纯 L0（脚本 + 文档），可云端执行。与用户沟通用中文。
> 这是 AB-19A「不动任何功能、逐操作核对不丢一项」的机器证据；前置是 Story 2.1 已产出基线清单与脚本 `scripts/ci/list-admin-write-ops.sh`。

## Story

As a QA，
I want 用 Story 2.1 同一个脚本对重构后代码再生成一份清单并与基线 diff，
so that 128 个写操作一个不丢有机器证据，而不是靰人肉对表。

## Acceptance Criteria

**AC1 · 重跑脚本**
**Given** `dev_1.3.0`（或本分支）重构后代码，Epic 1～10 全部合入
**When** 运行 `bash scripts/ci/list-admin-write-ops.sh > implementation-artifacts/v1.3.0/后台写操作清单-<yyyyMMdd>-after.md`
**Then** 输出格式与基线（Story 2.1 产出的 `后台写操作清单-<yyyyMMdd>.md`）逐列一致：路径 / HTTP 方法 / Controller#method / `@PreAuthorize` 表达式 / 所属页面（`AdminPageCatalog`）`[L0]`
**And** 同批输出 GET 页面路由列表（去参数路由 / 导出 / drawer / 片段）`[L0]`

**AC2 · diff 判定规则（写端点）**
**When** `diff` 基线与 after
**Then** **允许新增**的写端点仅限本版 PRD 定义：`POST /admin/accounts/{id}/rename`、`…/rebind-email`（AB-16A）；`/admin/roles/**` 写端点（AB-21A）；`POST /admin/places/**`（AB-17A）；`POST /admin/comments/virtual`、`/admin/warm-replies/**` 写端点（AB-20A）；`POST /admin/config/tiers`（AB-22A）；`POST /admin/dashboard/materialize`（@StagOnly，AD-3）；`POST /admin/tickets/warn` 若因加 `reason` 参数导致方法签名变化，路径不变不算新增 `[L0]`
**And** **允许删除**的写端点仅限 Story 7.6 注明的旧批量入口 `POST /admin/seed-batch`、`POST /admin/seed-batch/import`（且必须在 7.6 Completion Notes 有「仅服务旧入口」的核实记录）`[L0]`
**And** 其余基线 128 条写端点：路径、方法、`@PreAuthorize` 表达式**逐字一致**；任何一条差异 = 本 story 失败，回对应页面 story 修 `[L0]`

**AC3 · diff 判定规则（GET 路由）**
**Then** **允许删除**的 GET 路由仅限退役清单（见 Story 11.3 精确列表：8 个独立详情页 + `reports` + `content-schedules` + `vets/online` + `ratings` + `vets/{id}/ratings` + `vets/{id}/edit` + `vets/{id}/qualification` + `tickets/detail`）`[L0]`
**And** **允许新增**的 GET 仅限：`/**/{id}/drawer` 抽屉片段、`/admin/nav/badges`、`/admin/charts`、`/admin/places*`、`/admin/roles*`、`/admin/warm-replies*`、`/admin/comments/distribution*`、`/admin/config/tiers/disabled`、`/admin/algo-params/changes/drawer` `[L0]`

**AC4 · 产出核对报告**
**Then** 写 `implementation-artifacts/v1.3.0/后台写操作清单-核对-<yyyyMMdd>.md`：三段（写端点 diff / GET diff / 结论），每条新增 / 删除标注依据 story 号；结论行「128/128 基线写端点一致 ✅」或列出偏差 `[L0]`
**And** 脚本加 `--baseline <file>` 参数可直接输出上述报告骨架（可选，若 2.1 未做则本 story 补）`[L0]`

**AC5 · CI 钉住**
**Then** `.github/workflows` 加一步：在 `dev_1.3.0` 与 `feat/1.3.0-*` 分支 PR 上运行脚本并与 `后台写操作清单-<基线日期>.md` diff，非白名单差异即红 `[L0]`

---

## Tasks / Subtasks

- [ ] **T1 · 重跑与对齐格式**（AC1）
  - [ ] 确认脚本扫描范围 = `petgo-backend/src/main/java/com/tailtopia/admin/**` + `namemoderation/web`（`NameModerationAdminController` 在 admin 包外但挂 `/admin/**`）+ 任何 `@RequestMapping("/admin")` 前缀类；基线若漏了包外 Controller，先补脚本再重跑基线（写明）
  - [ ] 输出按路径排序，稳定可 diff

- [ ] **T2 · 白名单文件**（AC2 / AC3）
  - [ ] 新建 `scripts/ci/admin-write-ops-allowlist.txt`：两段 `+`（允许新增）`-`（允许删除），每行带 story 号注释
  - [ ] 脚本 `--baseline` 模式：读白名单，输出「未解释的差异」

- [ ] **T3 · 核对报告**（AC4）
  - [ ] 按模板写报告；把 Epic 7.6 的旧端点删除依据链接进来

- [ ] **T4 · CI**（AC5）
  - [ ] 参考既有 `flyway-guard`（`scripts/ci/check-flyway-versions.sh`）的接法加 job；失败信息打印未解释差异

- [ ] **T5 · 云端执行须知**：全 L0，云端可跑完

---

## Dev Notes

### 基线口径

- 2026-09-09 核实：`admin/**` 内 `@Post|Put|DeleteMapping` **128** 条，`@GetMapping` **79** 条（含参数路由 / 导出 / 片段）。这两个数字是 PRD §5 ③ 与 UX 对齐 #1 的依据。
- 「页面数」以 GET 路由列表为分母（约 45 个页面级路由），**不是** UI 稿 49 帧（就绪度报告 UX 对齐 #1，D-8 措辞已改）。

### 🔴 三类容易误判为「丢了」的变化

1. **PRG → htmx 分支**：同一个 POST 方法内加 `HxRequest` 判断返 fragment，路径不变，**不是**变更。
2. **`warn` 加 `reason` 参数**：路径不变，方法签名变，**不是**变更；但 `@PreAuthorize` 必须不变。
3. **Controller 搬家**：如 `AdminRatingController.overview` 删除但排序逻辑并入 `vets`（9.1b），GET 路由 `/admin/ratings` 在退役白名单内，写端点无涉。

### 🔴 唯一主动删写端点的地方

Story 7.6 种子发布页删旧批量入口时，`POST /admin/seed-batch` / `/seed-batch/import` 若仅服务该入口则一并删。本 story 的允许删除白名单**只有这两条**；其他任何写端点消失都算事故。

### 与 Story 2.1 的关系

2.1 产脚本 + 基线；本 story 复用脚本产 after + diff + 报告 + CI。脚本若需改（补包外 Controller），先在 11.1 改并**重跑基线**、注明「基线 v2」，不要用 v1 基线对 v2 脚本输出。

### References

- [Source: epics-v1.3.0.md#Story 11.1 / Story 2.1 / Story 7.6] · [Source: architecture-v1.3.0-delta.md#Enforcement / AD-12] · [Source: 决策日志.md D-8 D-23 D-27] · [Source: implementation-readiness-report-2026-09-09-v1.3.0.md#UX 对齐 #1]
- [Source: scripts/ci/check-flyway-versions.sh（CI 接法范式）]

## Dev Agent Record

### Agent Model Used

（dev-story 填写）

### Debug Log References

### Completion Notes List

### File List
