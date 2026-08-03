---
baseline_commit: b324308a
---

# Story 2.4: 登录门控解除与落地 Tab 矩阵

Status: ready-for-dev

> **所属**：V1.1.2 Epic 2 **收尾 Story**（**纯前端 · 安全攸关**）。交付：Diary 主页对游客开放 + 冷启动落地 Tab 按用户状态分流 + 三处连带调整。
> ⚠️ **顺序刻意排在最后**。门控一解开，游客点进 Diary 就要看到东西——2.2 的游客态必须先就绪。反过来先解门控会造成破损中间态。
> ⚠️ **安全攸关**：门控采用「默认受控 + 精确例外」，安全默认不可反转（NFR-3 / AD-7）。反向做法会使日后新增的成长档案子页一旦忘记登记就默认对游客敞开。
> ⚠️ **PRD 只覆盖了一处门控**（深链前缀匹配）。实际有**两处**，Tab 点击那处 PRD 未提及——只改一处，游客点 Diary 标签仍会被弹登录框。
> ⚠️ **本 Story 是 Epic 2 的收口验收点**：2.1~2.3 交付的页面在门控打开前对真实游客都不可达，端到端只有到这里才成立。AC7 覆盖完整游客链路（含 Story 2.2 AC8 的示例详情）。

## Story

As a **还没注册的访客**,
I want **打开 App 直接就能看到成长档案，点标签也不被弹登录框**,
so that **我能先看看这个 App 是干什么的，再决定要不要注册（FR-78 / FR-0A）**。

## Acceptance Criteria

> **验证层**：**L0**（安全回归 test）· **L1**（真后端，多身份分流）· **L2**（模拟器冷启动）。

### AC1 — 深链门控「默认受控 + 精确例外」（L1 · AD-7 Rule 1）

**Given** 深链门控按路径前缀匹配（`_controlledLocations` 的 `loc == p || loc.startsWith(p + '/')`）
**When** 解除 Diary 主页门控
**Then** 采用**默认受控 + 精确例外**：前缀匹配**保持默认拦截**，另设**精确例外集**仅放行 `/profile` 本身
**And** 建档 / 编辑档案 / 当天详情 / 里程碑列表等**全部子页面自动继续受控，无需逐个登记**
**And** 例外集**只接受完整路径字面量**，不接受前缀或通配
> ⚠️ 反向做法（把受控子页逐个列进清单）**明确禁止**——违反「安全规则只升不降不可绕过」。

### AC2 — Tab 点击门控（L1 · AD-7 Rule 2，PRD 未覆盖的第二处）

**Given** Tab 点击门控在 Story 1.1 已去索引化，免门控集合此时仍为 `{Discovery}`
**When** 实现完成
**Then** 免门控白名单扩为 **`{Discovery, Diary}`**
**And** 游客点击 Diary 标签**直接进入**，不弹登录框
**And** **Health / [+] / Me** 三者对游客维持受控不变

### AC3 — 安全默认回归锁定（L0 · NFR-3）

**Given** 安全默认不可反转，且门控须**双向**正确——该拦的拦住、该放的放行
**When** 运行回归测试
**Then** **拦截方向**：游客访问 `/profile/create`、`/profile/edit`、当天详情、里程碑列表**均被拦截**
**And** 存在测试显式锁定这些子页的受控状态
**And** 测试注释写明：新增任意 `/profile/*` 子页时默认受控，放行需显式加入精确例外集
**And** **放行方向（2026-08-03 新增）**：游客访问 `/profile` 主页**放行**；**Story 2.2 AC8 的示例内容详情对游客可达、不被任何门控拦截**
**And** 若示例详情实现为**独立路由**，须断言其路径**不落在任何受控前缀下**（尤其不得在 `/profile/` 之下）
**And** 若示例详情实现为**页内推入**（推荐做法，不新增路由），须断言游客在游客态下可完成「点条目 → 见详情 → 返回」全程无门控介入

### AC4 — 落地 Tab 矩阵（L1/L2 · AD-8）

**Given** 落地 Tab 按当前用户状态**实时计算**
**When** 冷启动完成
**Then** 按下表分流：

| 用户 | 冷启动落地 Tab |
|---|---|
| 游客（未登录） | **Diary**（游客引导态） |
| 状态 A · 已建档 | **Diary** |
| 状态 A · 未建档 | **Diary**（建档引导态） |
| 状态 B / C | **Discovery** |
| 兽医账号 | 兽医工作台（不变） |

**And** **不持久化**「上次落在哪」，每次冷启动按当时状态重新判定
**And** 用户状态改变（B/C ↔ A）后落地 Tab **实时跟随**
**And** 判定点在 Splash 完成回调，复用既有分流顺序（pending 深链 > 兽医直达 > 其余）

### AC5 — 热启动行为不变（L2）

**Given** 热启动不受本 Story 约束
**When** App 仍在后台、用户切回
**Then** 停留在离开时的页面，**不重新分流**

### AC6 — 三处连带调整（L1 · FR-78）

**Given** 落地页变更带来三处连带调整
**When** 实现完成
**Then** ① **[+] 发布按钮触发登录后的回跳**由「回首页」改为**回 Diary**
**And** ② **游客点开他人分享的宠物名片深链**（FR-14）落在 **Diary 游客引导态**，不再重定向 Discovery
**And** ③ **从其它 Tab 切回 Diary 时自动刷新**时间线与日历数据（现状仅 Discovery 有等价机制）
> ③ 的理由：Story 3.2 引入的里程碑/健康记录/身份证镜像条目会在用户不在该页时变化，不刷新会导致切回后看不到新内容。

### AC7 — 游客完整链路端到端可走通（L1/L2，2026-08-03 新增）

**Given** 本 Story 是门控真正打开的那一刻——在此之前 Story 2.2 的游客态与示例详情对真实游客都不可达
**When** 用**未登录**设备走完整链路
**Then** 冷启动 → 落地 Diary 游客引导态 → 浏览 9 条示例 → 点 3 条带图内容之一 → **看到示例详情** → 返回时间线
**And** **全程不出现任何登录框、不出现任何拦截重定向**
**And** 点其余 6 条 / 详情页互动按钮 → 正常触发建档引导（这是**期望行为**，不算门控拦截）
**And** 示例详情**零网络请求**（延续 Story 2.2 AC4/AC8）
> ⚠️ 这是本 Epic 的**收口验收**：2.1~2.3 交付的页面在本 Story 之前都只能靠 debug 路由与 widget test 验证，端到端只有到这里才成立。

---

## Tasks / Subtasks

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. 深链门控精确例外集** (AC: 1, 3)
  - [ ] `core/router/app_router.dart`：`_controlledLocations` **不动**（保持前缀匹配默认拦截）；新增 `_controlledExactExceptions = {'/profile'}`。
  - [ ] `redirect` 判定改为：命中受控前缀 **且不在精确例外集** → 拦截。
  - [ ] 例外集加注释：只接受完整路径字面量，禁止前缀/通配。

- [ ] **F2. Tab 点击免门控白名单扩容** (AC: 2)
  - [ ] `shared/widgets/app_shell.dart`：Story 1.1 建立的语义化免门控集合由 `{Discovery}` 扩为 `{Discovery, Diary}`。
  - [ ] 核对 Health / [+] / Me 仍走 `requireLogin`。

- [ ] **F3. 落地 Tab 矩阵** (AC: 4, 5)
  - [ ] `features/onboarding/presentation/splash_page.dart` 的 `onComplete` 回调（现状：pending 深链 > 兽医 > `/home`）：扩为按状态矩阵计算目标路径。
  - [ ] 判定输入复用 `isLoggedIn` / `petStatus` / `hasPetProfile` / `isVet`；**不新增持久化**。
  - [ ] ⚠️ **把用户状态判定抽为可复用函数**（返回 guest / A_with_profile / A_no_profile / B / C / vet 六态）。Story 6.1 的埋点 `user_state` 属性会引用它——两处各写一份会导致埋点口径与实际分流不一致。
  - [ ] 热启动路径不碰。

- [ ] **F4. 三处连带调整** (AC: 6)
  - [ ] ① `_onAddPressed` 的 `pendingAction` 由 `RouteIntent(location: '/home')` 改为 `/profile`。
  - [ ] ② 名片深链游客分支：由 redirect 到 `/home` 改为落 `/profile`（游客态）。
  - [ ] ③ `_onTabSelected` 切到 Diary 时触发时间线/日历 provider 刷新（照 Discovery 现有 `feedProvider.refresh()` 范式）。

- [ ] **F5. 安全回归测试（双向）** (AC: 3, 7)
  - [ ] **拦截方向**：游客访问 `/profile/create`、`/profile/edit`、`/profile/day/*`、里程碑列表 → 全部被拦截。
  - [ ] **放行方向**：游客访问 `/profile` → 放行。
  - [ ] **放行方向（新增）**：游客可达 Story 2.2 的示例内容详情——若为独立路由，断言其不在受控前缀下；若为页内推入，断言「点条目 → 见详情 → 返回」全程无门控介入。
  - [ ] 游客点 Health/[+]/Me → 仍弹强弹窗。
  - [ ] ⚠️ **不要**为了让示例详情可达而把 `/profile/` 下的路径加进例外集——那会连带放行真实子页。示例详情本就不该落在该前缀下（Story 2.2 AC8 已约束）。

### 🟨 联调验收子任务

- [ ] **J1（L1）**：真后端，游客 / A-已建档 / A-未建档 / B / C / 兽医 六种身份冷启动，落地目标逐一核对 AC4 表。
- [ ] **J2（L1）**：游客点名片分享链接 → 落 Diary 游客态；[+] 登录后 → 回 Diary。
- [ ] **J3（L2）**：模拟器验证热启动不重新分流；切回 Diary 触发刷新。
- [ ] **J4（L2，AC7 收口）**：未登录设备走完整游客链路——冷启动落 Diary → 点带图条目进详情 → 返回，全程零登录框；再点非图条目与详情页互动按钮，确认正常引到建档。

### 🟦 后端子任务

- ❌ **无**。

---

## Dev Notes

### 关键约定

- **两处门控，改法不同**：深链那处是「前缀匹配 + 精确例外」，Tab 那处是「语义化白名单扩容」。只改一处会漏。
- **安全默认必须是「拦」**。反向做法（列举受控子页）看似等价，实则把安全默认反转了——新增子页忘登记就敞开。CLAUDE.md「安全规则层只升不降不可绕过」直接约束这一点。
- **落地页不持久化**。用户宠物状态会变，记住「上次落在哪」反而会错。
- **连带调整③（切回刷新）现在做，是为 Story 3.2 铺路**：镜像条目会在用户不在该页时变化。

### 强制护栏（违反即返工）

- 例外集**只接受完整路径字面量**；出现 `startsWith` 或通配即违规。
- Diary 子页（建档/编辑/当天详情/里程碑列表）**必须仍受控**，须有测试锁定。
- **不得**为放行示例详情而扩大 `/profile/` 例外集——示例详情本就不在该前缀下（Story 2.2 AC8）。扩例外集会连带放行真实子页，属安全默认反转。
- Health / [+] / Me 对游客维持受控。
- 不得引入落地页的持久化存储。

### Project Structure Notes

- 前端修改：`core/router/app_router.dart`（精确例外集 + redirect 判定 + 名片深链游客分支）、`shared/widgets/app_shell.dart`（白名单扩容 + [+] 回跳目标 + 切回刷新）、`features/onboarding/presentation/splash_page.dart`（落地矩阵）。
- 前端新增测试：门控安全回归 test、落地矩阵 test。

### References

- [Source: epics-v1.1.2.md#Story 2.4] — 6 条 AC 原文。
- [Source: architecture-v1.1.2-delta.md#AD-7] — 默认受控 + 精确例外；两处门控对照表。
- [Source: architecture-v1.1.2-delta.md#AD-8] — 落地 Tab 实时计算不持久化；判定点在 Splash 完成回调。
- [Source: architecture-v1.1.2-delta.md#§1.3] — 门控两处的代码事实（PRD 只覆盖一处）。
- [Source: PRD-v1.1.2.md#FR-78] — 落地页矩阵表、门控解除声明、三处连带调整、假设 A-7。
- [Source: epics-v1.1.2.md#Story 2.2 AC8] — 示例详情的可达性要求与两处路由陷阱（本 Story 的放行方向断言依据）。
- [Source: app_router.dart / app_shell.dart / splash_page.dart] — 现状实现。

## Dev Agent Record

### Agent Model Used

_(待填)_

### Debug Log References

_(待填)_

### Completion Notes List

_(待填)_

### File List

_(待填)_

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 2.4 + AD-7/AD-8 生成。baseline=b324308a。 |
| 2026-08-03 | 修订 | 配合 Story 2.2 AC8（游客可无登录看示例详情）：AC3 扩为**双向**断言（该拦的拦住 + 示例详情必须放行）；新增 AC7 游客完整链路端到端收口验收；护栏加「不得为放行示例详情而扩大 `/profile/` 例外集」。 |
| 2026-08-03 | 修订 | 落地矩阵的用户状态判定须抽为可复用函数（六态），供 Story 6.1 埋点 `user_state` 属性引用，避免两处口径分叉。 |
