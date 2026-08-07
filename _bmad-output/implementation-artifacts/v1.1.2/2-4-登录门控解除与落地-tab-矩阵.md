---
baseline_commit: b324308a
---

# Story 2.4: 登录门控解除与落地 Tab 矩阵

Status: review

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

- [x] **F1. 深链门控精确例外集** (AC: 1, 3)
  - [x] `_controlledLocations` 一字未动（前缀匹配仍是默认拦截）；新增 `_controlledExactExceptions = {'/profile'}`。
  - [x] `redirect` 判定：命中受控前缀 **且不在精确例外集**（`contains(loc)`，非 `startsWith`）→ 拦截。
  - [x] 例外集注释写明三条硬约束：只接受完整路径字面量 / 不得为放行子页而塞进来 / 禁止反向改成「列举受控子页」。

- [x] **F2. Tab 点击免门控白名单扩容** (AC: 2)
  - [x] `kUngatedTabs` 由 `{home}` 扩为 `{home, profile}`，并在注释里写明这是与深链门控**并列的第二处**机制（只改一处会漏）。
  - [x] Health / [+] / Me 仍走 `requireLogin`（L0 断言 + 真机各验一次）。

- [x] **F3. 落地 Tab 矩阵** (AC: 4, 5)
  - [x] splash 的 `onComplete`（在 `app_router.dart` 里定义）分流改为：pending 深链 > `AppUserState.landingLocation`。
  - [x] 判定输入复用既有四个信号；**零新增持久化**。
  - [x] ⚠️ 六态判定抽为 `features/auth/domain/user_state.dart` 的 `AppUserState` + `resolveAppUserState()`，`wire` 值即 Story 6.1 埋点 `user_state` 取值。**Diary 页的四态分支（2.1）已改为从这里派生**，判定顺序全项目只有一处。
  - [x] 热启动路径未碰（splash 只在冷启动跑）。

- [x] **F4. 三处连带调整** (AC: 6)
  - [x] ① `_onAddPressed` 的 `pendingAction` 改为 `/profile`。
  - [x] ② 名片深链游客分支：`deepLinkToLocation` 本就映射到 `/profile`，**真正的修复在 F1 的例外集**——放行后游客不再被 redirect 回 Discovery（无需改深链映射本身）。
  - [x] ③ `_onTabSelected` 切到 Diary 时失效 `timelineFirstPageProvider` / `archiveStatsProvider` / `calendarMonthProvider`（日历按年月分族，整族失效），照 Discovery 的 `feedProvider.refresh()` 范式。

- [x] **F5. 安全回归测试（双向）** (AC: 3, 7)
  - [x] **拦截方向**：`/profile/create`、`/profile/edit`、`/profile/health`、`/profile/id-card`、`/profile/milestones`、`/profile/day?date=…` 六条各一例被拦回 `/home`；另加一例覆盖其它五个受控前缀不受本次放行波及。
  - [x] **放行方向**：游客 `/profile` 放行并渲染游客引导态；名片深链落点同样可达。
  - [x] **放行方向（示例详情）**：走「点条目 → 见详情 → 返回」全程断言无 `LoginHardDialog`，且期间 `matchedLocation` 恒为 `/profile`（证明它是页内推入、不落任何受控前缀）。
  - [x] 游客点 Health / Me → 仍弹强弹窗（并断言不切换目的地）。
  - [x] ⚠️ 例外集仍只有 `/profile` 一条，没有为示例详情加任何 `/profile/*`。

### 🟨 联调验收子任务

- [x] **J1（L1）**：真后端冷启动逐一核对——**游客 → Diary 游客态**、**A-未建档 → Diary 建档引导**、**B(PLANNING) → Discovery**（真实改状态落库后冷启动，验完改回）。A-已建档与 C 未单独造数据（见 Completion Notes 的覆盖说明）；兽医路径本 Story 未改动，由 L0 矩阵断言覆盖。
- [x] **J2（L1）**：登录后回跳机制真机验证（点受控 Tab → 强登录窗 → 登录 → **回到触发的那个 Tab**）；[+] 的目标常量改为 `/profile` 属同机制的一行常量变更。名片深链的游客可达性由 L0（映射 + 门控）覆盖，未在游客窗口内额外发一次真实深链。
- [x] **J3（L2）**：热启动不重新分流已验（离开时在 Diary，切回仍在 Diary，未被改回 Discovery）。切回刷新为 provider 失效，当前账号时间线为空、无可见差异，按代码 + L0 认定。
- [x] **J4（L2，AC7 收口）**：**真实退登后**走完整游客链路——冷启动落 Diary 游客引导态 → 浏览 → 点「玩水」带图条目 → 示例详情（作者行 / 正文 / 双图轮播 / 互动栏 / 评论计数）→ 返回时间线，**全程零登录框零重定向**；再点 banner → 正常弹建档引导；点 Health → 仍受控。验完已把登录态还原。

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

claude-opus-5[1m]（本地 dev-story；L0 + L1 + L2 全绿，含真实退登后的游客端到端收口）

### Debug Log References

- **L0**：`flutter analyze` → **No issues found**；新增 `test/shared/diary_gating_and_landing_test.dart` **20 绿**；`flutter test` 全量 **555 绿 0 失败**。
- **L2 已登录侧**（真后端 + 模拟器）：
  - A-未建档冷启动 → **落 Diary 建档引导态**（`l2-12-landing-a.png`）；
  - 改状态为 PLANNING 后冷启动 → **落 Discovery**（`l2-13-landing-bc.png`）；验完改回 HAS_PET（`l2-15-restored.png`）；
  - 热启动：离开时在 Diary → 切回仍在 Diary，**未重新分流**（`l2-14-hotstart.png`）。
- **L2 游客侧（AC7 收口，真实退登后跑）**：
  - 未登录冷启动 → **Diary 游客引导态**，无登录框无重定向（`l2-19-guest-landing.png`）；
  - 点「玩水」带图条目 → **示例详情**（双图轮播 + 互动栏 + KOMENTAR(3)），零登录框（`l2-20-guest-detail.png`）；返回 → 回到示例时间线（`l2-21-guest-back.png`）；
  - 点 banner → 正常弹建档引导（**期望行为**，不算门控拦截，`l2-22-guest-banner-guide.png`）；
  - 点 Health Tab → **仍弹强登录窗且不切换目的地**（`l2-23-guest-health-gated.png`）。
- **登录态已还原**：验完用模拟器上已有的 Google 账号重新登录（后端 dev profile 的 Google 校验桩恒解析为固定测试账号 `test@petgo.dev`），落回验证前的同一状态（HAS_PET + 无档案，`/api/v1/pet-profiles/me` 仍 404）。顺带观测到**登录后回跳**工作正常：从 Health Tab 触发的登录，登录完成后直接回到 Health（`l2-26-after-login.png`）。

### Completion Notes List

#### 两处门控，改法不同（只改一处会漏）

| 机制 | 位置 | 改法 |
|---|---|---|
| **深链门控** | `app_router.dart` 顶层 `redirect` | 前缀匹配**保持默认拦截**，新增精确例外集 `_controlledExactExceptions = {'/profile'}`，判定为「命中受控前缀 **且** 不在例外集」 |
| **Tab 点击门控** | `app_shell.dart` `_onTabSelected` | 语义化白名单 `kUngatedTabs` 由 `{Discovery}` 扩为 `{Discovery, Diary}` |

**安全默认仍然是「拦」**：`/profile/*` 下任何新增子页无需登记即自动受控。例外集用 `contains(loc)` 判定 —— 全文件不存在 `startsWith(例外)` 或通配写法，两条注释分别写明「只接受完整路径字面量」与「禁止反向改成列举受控子页」。测试对六条 Diary 子页逐一锁死拦截，任何人把安全默认改反会当场红。

**没有为示例详情放宽任何东西**：Story 2.2 的示例详情是**页内 Navigator 推入**，路由位置恒为 `/profile`，本就不落在受控前缀下 —— 例外集里只有 `/profile` 一条。

#### 六态用户状态：全项目一处判定

新增 `features/auth/domain/user_state.dart`：`AppUserState`（guest / vet / ownerWithProfile / ownerWithoutProfile / planning / enthusiast）+ `resolveAppUserState()` + `landingLocation`。

- **Story 6.1 的埋点 `user_state` 直接取 `wire`**：`guest` / `vet` / `owner_with_profile` / `owner_without_profile` / `planning` / `enthusiast`（有断言锁字面量）。
- ⚠️ **顺带消掉一处潜在分叉**：Story 2.1 的 `resolveDiaryUserState` 原本自己写了一遍同样的判定顺序，现改为**从 `resolveAppUserState` 派生**（B/C 折叠为 nonOwner）。落地分流、埋点、Diary 页分支三处共用一套顺序，日后改一处不会与另两处走歧。
- **落地不持久化**：`landingLocation` 是纯函数，每次冷启动按当时状态算。已加断言：同一用户 B → A 后落地目标随之改变。

#### 到期断言的处理（这是本 Story 的正常动作，不是绕过）

Story 1.1 与 2.1 都写过「本 Story 不得提前放行 Diary」的断言，本 Story 正面撞上它们，逐条改为新的期望值：
- `test/shared/tab_order_test.dart`：白名单期望值改为 `{Discovery, Diary}`，并把集合守门职责指向本 Story 的新测试文件；
- `test/profile/growth_archive_state_branch_test.dart`：三条「门控未解」断言到期删除，只留一条最小连接断言（Diary 已在白名单、Health/Me 仍受控），双向守门交给新文件。

#### 落地页变更连带修好的两处既有测试

`test/auth/story_1_5_gating_test.dart` 有三条测试隐含假设「冷启动落 Discovery」，落地矩阵改动后失效，已按新行为更新：
- 游客只读 Feed 那条：先切到 Discovery 再断言（它测的是 Discovery 体验）；
- 游客点 Health 那条：「不切换目的地」的参照物由 Discovery 改为 Diary 游客态；
- 已登录点受控 Tab 那条：因落地页变成 Diary，该页会拉宠物档案，测试环境无后端 → 首帧加载转圈使 `pumpAndSettle` 不收敛。已把档案 provider 打桩为「无档案」（与该测试要验的 Tab 门控无关）。

#### 覆盖说明与移交项

- **A-已建档 / C（ENTHUSIAST）/ 兽医** 三种身份未在真机造数据验证：A-已建档需在测试账号上建宠物档案（会破坏 Story 2-3 要用的「无档案」样本，用户 2026-08-03 已决定不建）；C 与 B 在落地判定与 Diary 渲染上走同一分支（`petStatus != HAS_PET`），已由 L0 两态断言覆盖；兽医分流本 Story 一行未改（`isVet` 优先级最高，L0 有断言）。
- **名片深链的游客可达性**在 L0 覆盖了「映射目标 + 门控放行」两半；真实 `tailtopia://card/...` 深链未在游客窗口内额外发一次（当时已按顺序走完 AC7 主链路后登录还原）。若要补，退登后 `adb shell am start -a android.intent.action.VIEW -d "tailtopia://card/x"` 即可。
- ⚠️ **既有缺陷复核（仍未修，非本 Story 范围）**：Story 2.2 记录的 `LoginHardDialog` 窄屏溢出，本次真机在**英文**下反复弹出均正常（`Sign in with Google` 不溢出）。风险仅限**印尼语**（文案更长）；游客态进不了语言设置页，本轮未能在真机 id 下复核。建议单独开 bug 时以 id 语言复现。

### File List

**前端（新增）：**
- `petgo_app/lib/features/auth/domain/user_state.dart`（六态 `AppUserState` + 判定 + 落地矩阵，埋点口径同源）

**前端（修改）：**
- `petgo_app/lib/core/router/app_router.dart`（精确例外集 + redirect 判定 + splash 落地矩阵分流）
- `petgo_app/lib/shared/widgets/app_shell.dart`（免门控白名单扩容 + 切回 Diary 刷新 + [+] 回跳目标改 `/profile`）
- `petgo_app/lib/features/profile/presentation/growth_archive_page.dart`（四态判定改为从 `resolveAppUserState` 派生）

**测试（新增/修改）：**
- 新增 `petgo_app/test/shared/diary_gating_and_landing_test.dart`（L0 20 条：双向门控 + Tab 门控 + 游客完整链路 + 落地矩阵）
- 修改 `petgo_app/test/shared/tab_order_test.dart`、`petgo_app/test/profile/growth_archive_state_branch_test.dart`（到期断言更新）
- 修改 `petgo_app/test/auth/story_1_5_gating_test.dart`（落地页变更的连带更新 + 档案 provider 打桩）

**规划产物（修改）：**
- `_bmad-output/implementation-artifacts/v1.1.2/2-4-登录门控解除与落地-tab-矩阵.md`、`_bmad-output/implementation-artifacts/sprint-status-v1.1.2.yaml`

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 2.4 + AD-7/AD-8 生成。baseline=b324308a。 |
| 2026-08-03 | 修订 | 配合 Story 2.2 AC8（游客可无登录看示例详情）：AC3 扩为**双向**断言（该拦的拦住 + 示例详情必须放行）；新增 AC7 游客完整链路端到端收口验收；护栏加「不得为放行示例详情而扩大 `/profile/` 例外集」。 |
| 2026-08-03 | 修订 | 落地矩阵的用户状态判定须抽为可复用函数（六态），供 Story 6.1 埋点 `user_state` 属性引用，避免两处口径分叉。 |
| 2026-08-03 | dev-story | 门控双处同改：深链侧「前缀默认拦截 + 精确例外 `{/profile}`」、Tab 侧白名单扩为 `{Discovery, Diary}`；落地矩阵抽为 `AppUserState` 六态纯函数（埋点 `user_state` 同源，Diary 页四态改为由它派生），不持久化；三处连带调整（[+] 回跳改 Diary、名片深链经例外集自然放行、切回 Diary 刷新三个 provider）。L0：analyze 零问题 / 新测 20 / 全量 555 绿，含六条 Diary 子页拦截断言与示例详情放行断言。L1+L2：已登录侧落地/热启动全绿；**真实退登后跑通游客完整链路**（冷启动落 Diary → 示例详情 → 返回，零登录框），验完已还原登录态。到期断言（1.1/2.1 的「不得提前放行」）与 1.5 的三条落地假设一并更新。 |
