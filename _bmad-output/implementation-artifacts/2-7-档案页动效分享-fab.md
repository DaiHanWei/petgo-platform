# Story 2.7: 档案页动效分享 FAB

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **宠物主**,
I want **档案页有一个醒目的分享按钮**,
so that **我能随时把宠物名片分享出去，驱动拉新飞轮**。

> 本 Story 在成长档案 Tab 落地**动效分享 FAB**：首访 scale pulse + ring ripple、此后静态、滚动不消失、点击调系统分享菜单传名片链接、B/C 无档案不显示。**几乎全前端**（无后端新增）。复用 2.6 的 `/p/{cardToken}` 链接。
>
> **依赖前序**：1.2（设计 token/动效基线）、2.2（cardToken）、2.4（成长档案 Tab + FAB 占位）、2.6（名片 H5 链接，分享目标）。

## Acceptance Criteria

> **验证层标注**：**L0 静态**（widget/动效/分支测试）· **L1 集成/运行时** · **L2 端到端/外部凭证**（真机系统分享菜单）。本 Story 主落 **L0**；系统分享调起需 **L2**（真机）。

### AC1 — 大号动效 FAB（首访动效→静态，滚动不消失）

**Given** 状态 A 已创建档案用户在成长档案 Tab
**When** 页面渲染
**Then** 右下角显示大号动效 FAB，首次进入触发 scale pulse + ring ripple 动效、此后保持静态（FR-15、UX-DR13），FAB 持续显示不因滚动消失
> 验证层：**L0**（首访标记控制动效一次性、之后静态；FAB pinned 不随 ListView 滚动消失 — widget 测试；首访标记持久于 prefs，复访不再动效）。

### AC2 — 点击调系统分享传名片链接

**Given** 用户点击分享 FAB
**When** 触发分享
**Then** 调用系统分享菜单，默认传入该宠物名片链接（WhatsApp/Instagram/复制链接）
> 验证层：**L0**（点击调 `Share.share(url)`，url=`https://<host>/p/{cardToken}` — 用 mock 验传参）+ **L2**（真机弹系统分享菜单、含 WhatsApp/Instagram/复制链接、分享出正确链接）。

### AC3 — B/C 无档案不显示 FAB

**Given** 状态 B 或 C（无宠物档案）用户
**When** 进入成长档案 Tab
**Then** FAB 不显示（无档案即无名片可分享）
> 验证层：**L0**（按用户状态/是否有档案分支：B/C 或无档案 → 不渲染 FAB — widget 测试）。

---

## Tasks / Subtasks

> 三段组织。**全前端**（后端无新增；分享链接来自 2.6/cardToken 来自 2.2）。

### 🟦 后端子任务（petgo-backend）

- [ ] **B1. 无新增** (AC: 1, 2, 3)
  - [ ] 本 Story 后端无改动；cardToken 由 2.2 提供、名片 URL 由 2.6 提供。确认前端可从 `GET /pet-profiles/me` 拿到 cardToken 拼分享链接（contract 对齐，无代码）。

### 🟩 前端子任务（petgo_app / `lib/features/profile` + `shared/widgets`）

- [ ] **F1. 动效分享 FAB widget** (AC: 1)
  - [ ] `shared/widgets/share_fab.dart`（或 `features/profile/presentation/widgets/`）：大号 FAB，首访触发 **scale pulse + ring ripple** 动效（AnimationController），之后静态；**pinned 不随滚动消失**（放 Stack/floatingActionButton 而非 list 内）。
  - [ ] 首访一次性：`prefs` 存 `shareFabAnimatedShown` 标记，已展示则跳过动效直接静态（动效只首访一次，UX-DR13）。
- [ ] **F2. 系统分享调起** (AC: 2)
  - [ ] `flutter pub add share_plus`（或等效）；点击 FAB → `Share.share('https://<host>/p/$cardToken')`；host 从环境配置（dev/prod）取。
- [ ] **F3. 显隐分支（B/C/无档案不显示）** (AC: 3)
  - [ ] 成长档案 Tab（2.4）集成：仅 (状态 A + 已创建档案 + 有 cardToken) 渲染 FAB；B/C 或无档案不渲染。替换 2.4 的「FAB 占位」。
- [ ] **F4. i18n / 无障碍** (AC: 1, 2)
  - [ ] FAB 语义标签（无障碍）「分享宠物名片」走 .arb（id/en）；无写死字符串。

### 🟨 联调验收子任务

- [ ] **J1. 首访动效→静态（L0）** (AC: 1) — 首次进 Tab 出 scale pulse+ring ripple，复访静态；滚动 FAB 不消失。
- [ ] **J2. 系统分享（L2）** (AC: 2) — 真机点击弹系统分享，传入 `/p/{cardToken}`，含 WhatsApp/Instagram/复制链接。
- [ ] **J3. B/C 隐藏（L0）** (AC: 3) — B/C/无档案用户 Tab 内无 FAB。

---

## Dev Notes

### 架构约定

- **前端动效/状态**：动效用 AnimationController；首访标记走 `shared_preferences`（非敏感偏好，架构 §Auth「prefs 存引导计数」同类）。FAB 显隐依赖全局宠物状态 provider + 档案 provider。
- **分享链接**：`/p/{cardToken}`（2.6），cardToken 来自 2.2，**不暴露顺序 id**。host 走环境配置（dev 指本地/prod 指正式域）。
- **无后端改动**：纯前端 + 系统能力。

### 强制护栏

- **禁 MQ/缓存层/新中间件**（前端 story，无后端中间件）。
- **对外标识不可枚举**：分享传 cardToken，绝不传内部 id。
- 首访动效一次性（UX-DR13）：勿每次进页都动效。
- 无障碍语义 + i18n，无写死串。

### 范围边界（不做）

- ❌ 不做名片 H5 页面（2.6 后端）。
- ❌ 不做档案 Tab 主屏/时间线（2.4）、编辑（2.8）。
- ❌ 后端无新增表/端点。
- ✅ 只做：动效分享 FAB（首访动效→静态、pinned）+ 系统分享调起传名片链接 + B/C 无档案隐藏 + i18n/无障碍。

### Project Structure Notes

- 前端 `lib/shared/widgets/share_fab.dart`（或 `features/profile/presentation/widgets`）；集成于 2.4 的 `growth_archive_page`，替换 FAB 占位。`share_plus` + `share_preferences`。
- 与架构前端 `shared/widgets/{...}` 列表中分享/FAB 类组件一致（架构 `shared/widgets` 含 publish_fab 等凸起/浮层组件）。

### References

- [Source: architecture.md#Frontend Architecture] — Riverpod 状态、prefs 存非敏感偏好、动效在 presentation。
- [Source: architecture.md#Integration Points 数据流 3] — 分享 `/p/{cardToken}` 驱动社交预览→下载（飞轮）。
- [Source: architecture.md#Enforcement Guidelines] — 对外标识不可枚举 token。
- [Source: epics.md#Story 2.7] — 三条原始 AC（FR-15/UX-DR13）。
- [Source: epics.md#Story 2.6] — 名片链接 `/p/{cardToken}`（分享目标）。
- [Source: epics.md#Story 2.4] — 档案 Tab FAB 占位（本 Story 接入）。

## Dev Agent Record

### Agent Model Used

云端 dev agent（headless，L0 绿）。

### Debug Log References

- 前端：`flutter analyze` → No issues；`flutter test` → 87 passed。
- 后端：本 Story 无改动（cardToken 由 2.2、名片 URL 由 2.6 提供）。

### Completion Notes List

**L0 状态（云端已验收）：**
- 前端：`ShareFab`（首访 scale pulse + ring ripple 动效，复访静态；pinned 于 Scaffold.floatingActionButton 不随滚动消失）+ `shareServiceProvider`（Share.share，注入便于测试）+ `shareFabAnimatedShownProvider`（prefs 首访标记，动效一次性）。集成 growth_archive_page 替换 2.4 占位 FAB，仅 (状态 A + 有档案 + 有 cardToken) 渲染（B/C/无档案不显示）。分享传 `petCardShareUrl(cardToken)`=/p/{token}（不可枚举）。FAB 无障碍语义 + i18n。
- 单测：ShareFab 点击触发 onPressed、首访 animate→onAnimationShown 一次性、复访不触发；growth_archive A+档案显示 FAB / B 隐藏 FAB。

**share_plus 版本**：`share_plus:^10.1.4`（`Share.share(text)` 未废弃，直接用）。

**⚠️ 待本地验收（L2）：**
- J2 真机点击弹系统分享菜单（含 WhatsApp/Instagram/复制链接），分享出正确 `/p/{cardToken}` 链接。
- 待肉眼确认：FAB 首访动效（scale pulse + ring ripple）视觉、滚动不消失（真机/模拟器）。

### File List

**前端**：`pubspec.yaml`(+share_plus)、`features/profile/presentation/widgets/share_fab.dart`、`features/profile/domain/share_service.dart`、`features/profile/presentation/growth_archive_page.dart`(集成 FAB)、`l10n/*.arb`(+shareFabLabel)；测试 `test/profile/share_fab_test.dart`、`growth_archive_test.dart`(+FAB 断言)。

**后端**：无改动。
