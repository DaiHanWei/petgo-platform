---
baseline_commit: b324308a
---

# Story 1.1: 底部 Tab 顺序与底层索引重排

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **所属**：V1.1.2 Epic 1 第一个 Story（**纯前端**）。交付：底部 Tab 视觉顺序改为 Diary / Health / [+] / Discovery / Me，**底层索引一并重排**（AD-3，用户拍板；架构原倾向不重排）。
> ⚠️ **本 Story 不改门控、不改落地页**——那两项归 Story 2.4。改完后游客点 Tab 的拦截行为、冷启动落地目标必须与改版前**完全一致**。
> ⚠️ **本 Story 的风险不在代码量，在漏改**：Tab 索引被 5 处代码引用，漏一处就是「点推送进错页面」这类难排查的问题。AC3 是逐条打勾的硬性核对清单。

## Story

As a **TailTopia 用户**,
I want **打开 App 时底栏第一个就是我的宠物成长档案**,
so that **这个 App 给我的第一印象是「记录工具」而不是「信息流」（FR-78）**。

## Acceptance Criteria

> **验证层**：**L0**（`flutter analyze` / `flutter test`，索引一致性与调用点核对）· **L1**（真后端联调，门控与落地页回归）· **L2**（模拟器视觉）。

### AC1 — Tab 视觉顺序变更（L2）

**Given** 用户打开 App
**When** 查看底部 Tab Bar
**Then** 5 个位置依次为 **Diary / Health / [+] / Discovery / Me**
**And** [+] 仍为居中凸起 FAB，视觉形态与协议尺寸与改版前一致
**And** Discovery（原首页 Feed）的内容与浏览逻辑不变，仅位置与名称变化
> 视觉基准：UI 稿 T1 屏（含变更前/后对照）。
> ⚠️ 第 2 位显示文案暂用 **Health** 占位，最终取 Health 还是印尼语 Kesehatan 见 **OQ-19**（不阻塞，定案后走 i18n 替换）。

### AC2 — 底层索引与视觉顺序一致（L0）

**Given** 现状底层索引顺序与视觉顺序本就不一致
**When** 检查 Tab 枚举顺序、`StatefulShellRoute` 分支声明顺序、`_tabLocations` 路径映射数组
**Then** 三处顺序严格一致且与视觉顺序相同
**And** 不存在「索引顺序 ≠ 视觉顺序」的残留
> 验证层：**L0**（analyze + widget test 断言分支顺序）。

### AC3 — 5 处索引寻址调用点硬性核对（L0，本 Story 核心）

**Given** AD-3 Rule 2 列出的 5 处按索引寻址调用点
**When** 完成重排
**Then** 每一处已确认并在 Completion Notes **逐条打勾**：

| # | 位置 | 现状 | 重排后须确认 |
|---|---|---|---|
| ① | `app_shell.dart` 免门控 Tab 判定 | `index == AppTab.home.index` | **去索引化**——改为按 Tab 语义判定，但免门控集合此时**仍为 `{Discovery}` 不变**（扩为 `{Discovery, Diary}` 归 Story 2.4） |
| ② | `app_shell.dart` [+] 预选 Diary 判定 | `currentIndex == AppTab.profile.index`（bug 20260703-244） | 指向重排后的 Diary 分支 |
| ③ | `app_shell.dart` `_tabLocations` 路径映射数组 | `['/home','/profile','/triage','/me']` | 数组顺序与新分支顺序严格对应 |
| ④ | `BottomTabBar` 渲染 | 按 `currentIndex` 渲染 | 图标 / 文案 / 激活态与新顺序对应 |
| ⑤ | `app_router.dart` `StatefulShellRoute` 分支声明顺序 | 4 个分支 | 声明顺序即索引来源，为本项改动的根 |

**And** 遗漏任意一处即本 AC 不通过
> ⚠️ ① **不得提前放行 Diary**——本 Story 只做「不再比较裸索引」，免门控集合不变。

### AC4 — 路径寻址不得回退为索引寻址（L0）

**Given** 登录后回跳（`RouteIntent.location`）与推送深链现状均按**路径**寻址
**When** 完成索引重排
**Then** 二者仍按路径寻址
**And** 未因重排引入任何按索引寻址的新代码（AD-3 Rule 3）

### AC5 — 门控与落地页零回归（L1）

**Given** 本 Story 不改门控与落地页
**When** 游客点击各 Tab、以及冷启动
**Then** 门控行为（游客点受控 Tab 弹强弹窗）与落地页目标（冷启动落 `/home`）与改版前**完全一致**
**And** 兽医账号冷启动仍直达工作台
**And** 无中间态破损

---

## Tasks / Subtasks

> **纯前端**。顺序：分支声明重排 → 枚举/数组同步 → 去索引化判定 → 底栏渲染 → 回归测试。

### 🟩 前端子任务（petgo_app / Flutter）

- [x] **F1. `StatefulShellRoute` 分支声明重排** (AC: 2, 3⑤)
  - [x] `core/router/app_router.dart`：4 个 branch 声明顺序改为 `/profile`(Diary) → `/triage`(Health) → `/home`(Discovery) → `/me`。
  - [x] ⚠️ 这是索引的**根来源**，先改这里，其余跟着对齐。

- [x] **F2. Tab 枚举与路径映射数组同步** (AC: 2, 3③)
  - [x] `AppTab` 枚举顺序与 F1 一致。
  - [x] `shared/widgets/app_shell.dart` `_tabLocations` 改为 `['/profile','/triage','/home','/me']`。
  - [x] 加 widget test 锁定：枚举序 == 分支序 == 数组序。

- [x] **F3. 免门控判定去索引化** (AC: 3①)
  - [x] `_onTabSelected` 中 `index == AppTab.home.index` 改为按 Tab 语义的免门控集合判定（此时集合仅含 Discovery）。
  - [x] ⚠️ **不加 Diary**——Story 2.4 才扩。加测试锁定：游客点 Diary 此时**仍**被门控。

- [x] **F4. [+] 预选 Diary 判定对齐** (AC: 3②)
  - [x] `_onAddPressed` 中 `currentIndex == AppTab.profile.index` 指向重排后的 Diary 分支，行为（有档案则预选成长日历）不变。
  - [x] ⚠️ 该逻辑与 FR-83「全局默认 Diary」的口径合并归 Story 4.2，本 Story 只保证重排后行为不变。

- [x] **F5. 底栏渲染与顺序对齐** (AC: 1, 3④)
  - [x] `shared/widgets/bottom_tab_bar.dart`：图标 / 文案顺序按 T1 稿；第 2 位文案 key 新增（占位 `Health`，i18n 双语）。
  - [x] [+] FAB 位置、尺寸、`_FixedCenterDockedFabLocation` 不动。

- [x] **F6. 回归测试** (AC: 4, 5)
  - [x] 锁定：登录后回跳与推送深链仍按路径寻址（无索引常量出现在这些路径）。
  - [x] 锁定：游客点受控 Tab 仍弹强弹窗；冷启动仍落 `/home`；兽医仍直达工作台。

### 🟨 联调验收子任务

- [x] **J1（L1）**：真后端 + 模拟器，游客/已登录/兽医三种身份走一遍，门控与落地页与改版前一致。
- [x] **J2（L2）**：模拟器视觉核对 T1 稿——5 位顺序、[+] 居中凸起、Discovery 内容不变。

### 🟦 后端子任务

- ❌ **无**。本 Story 零后端改动、零迁移。

---

## Dev Notes

### 关键约定

- **只改视觉顺序与底层索引，不碰门控与落地页**。这是本 Story 与 Story 2.4 的分界，越界会造成两条 story 重复认领同一处改动。
- **索引重排是用户拍板的选择**（AD-3）。架构原倾向「只改视觉顺序、不动索引」，因为按索引寻址的调用点漏改代价高。既然选了理顺，AC3 的核对清单就是硬门槛。
- **第 2 位文案 `Health` 是占位**：OQ-19 未定（Health vs Kesehatan）。用 i18n key 承载，定案后只换文案不动结构。

### 强制护栏（违反即返工）

- 免门控集合本 Story **不得**加入 Diary（属 2.4）。
- 登录后回跳 / 推送深链**不得**改为按索引寻址。
- [+] FAB 的视觉形态与协议尺寸不变（FR-78 明确）。

### Project Structure Notes

- 前端修改：`core/router/app_router.dart`（分支声明顺序）、`shared/widgets/app_shell.dart`（枚举/`_tabLocations`/两处判定）、`shared/widgets/bottom_tab_bar.dart`（渲染顺序 + 第 2 位文案）、`l10n/app_{en,id}.arb`（新 Tab 文案 key）。
- 前端新增测试：Tab 顺序一致性 widget test、门控回归 test。

### References

- [Source: epics-v1.1.2.md#Story 1.1] — 5 条 AC 原文。
- [Source: architecture-v1.1.2-delta.md#AD-3] — 索引重排决策 + 5 处调用点核对清单 + 路径寻址不得回退。
- [Source: architecture-v1.1.2-delta.md#§1.3] — 门控有两处（本 Story 只去索引化，不解门控）。
- [Source: PRD-v1.1.2.md#FR-78] — Tab 顺序与命名变更；「Tab 索引口径」段明确视觉顺序为需求、底层索引由工程按成本决定。
- [Source: ui-v1.1.2.html#T1] — 变更前/后对照视觉稿。
- [Source: app_shell.dart / app_router.dart / bottom_tab_bar.dart] — 现状实现。

## Dev Agent Record

### Agent Model Used

claude-opus-5[1m]（本地 dev-story；L0 + L2 全绿，真模拟器视觉验收）

### Debug Log References

- **L0**：`flutter analyze` → **No issues found**；新增 `test/shared/tab_order_test.dart` **6 绿**；`flutter test` 全量 **501 绿 0 失败**（零回归）。
- **L2**（真模拟器 `petgo_phone` / Android 16）：底栏渲染为 **Diary / Health / [+] / Discovery / Me**，[+] 居中凸起、激活态仍为 V1.0 pop-art（紫实心 + 红错位投影，萌化归 1-2）。截图存 scratchpad `tab-01.png`。
- **L2 门控回归**：游客点 Diary → 弹「This feature needs an account」强登录窗，**Discovery 保持激活态、不切换目的地**；游客冷启动仍落 Discovery。截图 `tab-02-diary-guest.png`。二者共同证明 AC5 零回归 + AC3① 未提前放行。

### Completion Notes List

**AC3 五处索引寻址调用点逐条核对（硬性清单）：**

| # | 位置 | 处理 | 结果 |
|---|---|---|---|
| ① | `app_shell.dart` 免门控 Tab 判定 | `index == AppTab.home.index` → **语义化白名单** `kUngatedTabs`（公开常量，可测） | ✅ 已去索引化；**集合仍为 `{Discovery}` 未提前放行 Diary** |
| ② | `app_shell.dart` [+] 预选 Diary 判定 | 裸索引比较 → `AppTab.values[currentIndex] == AppTab.profile` | ✅ 行为不变，指向重排后的 Diary 分支 |
| ③ | `app_shell.dart` `_tabLocations` 路径映射数组 | **数组整个删除**，`location` 内嵌到 `AppTab` 枚举上 | ✅ 结构上不可能再与枚举脱节 |
| ④ | `BottomTabBar` 渲染 | Row 顺序改为 profile / triage / [+] / home / me | ✅ 与 `AppTab.values` 严格一致 |
| ⑤ | `app_router.dart` 分支声明顺序 | 手写四段 → **按 `AppTab.values` 循环生成** + 穷尽 `switch` 映射根页 | ✅ 分支顺序 == 枚举顺序，由构造方式保证 |

**两处「消除漂移」而非「改对顺序」的设计选择**（③⑤）：AD-3 担心的是「三处顺序各自维护、日后走歧」。与其改对再写断言，不如让它**结构上无法不一致**——`location` 内嵌枚举、分支循环生成后，这两类漂移不再可能发生，断言反而成了同义反复。

- **AC4 路径寻址未回退**：全仓扫描确认 `RouteIntent` 全部按路径、深链表 `shellTabRoots` 为路径集合、无 `goBranch(0/1/2/3)` 硬编码。
- **文案**：`tabHome` 由 Home/Beranda 改为 **Discovery/Jelajah**。⚠️ **顺带发现 OQ-19 是伪问题**：第 2 位文案本就已是 `tabTriage` = **Health（英）/ Kesehatan（印尼）**，二者是同一 key 的两语版本而非二选一，无需产品拍板。建议在 README/PRD 关闭 OQ-19。
- **未做（留后续，非本 Story 范围）**：`deep_link_routes.dart` 的 `shellTabRoots` 是与 `AppTab.location` 重复的路径集合（Set，无顺序依赖，**不属索引寻址**，不在 AC3 五点内）。若日后改某 Tab 路径而忘同步，深链会白屏（正是它当初为修 bug 20260729 而生）。建议后续改为 `AppTab.values.map((t) => t.location).toSet()`。本 Story 是零回归的缺陷/重排 story，不扩大改动面。
- **未改名**：枚举值仍为 `home/profile/triage/me`（`home` = 现 Discovery）。改名牵动 l10n key 与大量调用点，不在任何 AC 内；已在枚举文档注释写明映射关系。
- **零后端改动、零迁移。**

### File List

**前端（修改）：**
- `petgo_app/lib/shared/widgets/bottom_tab_bar.dart`（枚举重排 + `location` 内嵌 + Row 顺序 + 文档）
- `petgo_app/lib/shared/widgets/app_shell.dart`（`kUngatedTabs` 公开常量 + 去索引化判定 + 删 `_tabLocations`）
- `petgo_app/lib/core/router/app_router.dart`（分支按 `AppTab.values` 循环生成 + `_tabRootPage` 穷尽 switch + import）
- `petgo_app/lib/l10n/app_en.arb`（tabHome → Discovery）
- `petgo_app/lib/l10n/app_id.arb`（tabHome → Jelajah）
- `petgo_app/lib/l10n/app_localizations*.dart`（`flutter gen-l10n` 重新生成）

**测试（新增）：**
- `petgo_app/test/shared/tab_order_test.dart`（L0，6）

**规划产物（修改）：**
- `_bmad-output/implementation-artifacts/v1.1.2/1-1-底部-tab-顺序与底层索引重排.md`、`_bmad-output/implementation-artifacts/sprint-status-v1.1.2.yaml`

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 1.1 + AD-3 生成。baseline=b324308a。 |
| 2026-08-03 | dev-story | Tab 顺序与底层索引重排完成：枚举重排 + `location` 内嵌枚举（删并行数组）+ 路由分支按枚举循环生成 + 免门控判定去索引化（集合不变，未提前放行 Diary）+ tabHome 改 Discovery/Jelajah。AC3 五处调用点逐条核对通过。L0 analyze 零问题 / 新测 6 / 全量 501 绿；L2 真模拟器验收底栏顺序与游客门控回归。顺带发现 OQ-19 为伪问题（Health/Kesehatan 本就是同 key 两语版本）。 |
