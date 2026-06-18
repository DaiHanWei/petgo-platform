---
title: '兽医端独立薄荷主题（P0 色彩系统层）'
type: 'refactor'
created: '2026-06-17'
status: 'done'
baseline_commit: '7869542104563768a80bc2cc1197f5e48173f6b7'
context:
  - '{project-root}/CLAUDE.md'
  - '{project-root}/_bmad-output/fidelity-audit.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 全局主题以 `accentGrowth`(=`#845EC9` 紫) 种子化，兽医端子树（登录/工作台 4 tab/案例详情/对话）的 Material 默认组件（NavigationBar 指示色、涟漪、按钮）与 4 处显式品牌色引用全部渲染**紫色**；而原型 H5 规定兽医端为**薄荷绿 `#5BCBBB`** 体系。app 当前**无任何 vet 专属颜色 token、无 vet 主题作用域**。

**Approach:** 只做色彩系统层（P0）：① 在 `colors.dart` 新增 vet 薄荷 token 块（additive，不改任何现有值）；② 在 `app_theme.dart` 加 `AppTheme.vet`（以 `#5BCBBB` 种子化的浅色 ColorScheme）；③ 给 4 个 vet 路由 builder 包一层 `Theme(AppTheme.vet)`，使整个 vet 子树默认渲染薄荷；④ 把 vet 屏 4 处显式紫引用改为 vet 薄荷 token。深色顶栏 `#2B2540`/对话工具栏 `#1A2B28` 仅**定义 token 备用**，其结构性铺设留 P1 逐屏。

## Boundaries & Constraints

**Always:** 以原型 H5 (`_bmad-output/pages/vet-*.html`) 为色值唯一标准；用户侧紫色体系**保持不动**；vet token 走 `AppColors`，不在 vet 业务文件硬编码 hex（遵 CLAUDE.md 护栏）；新增 token 一律 additive，现有常量值/名零改动。

**Ask First:** 若实现中发现 vet 子树外的文件（用户/游客侧）也会被 vet 主题波及；若需把 vet 路由重构为 ShellRoute 才能注入主题（优先用 builder 包裹，不重构路由）。

**Never:** 不做深色顶栏/统计卡/队列卡/工具栏等**结构性逐屏布局**（属 P1）；不改用户侧任何 token、主题、屏；不改吉祥物 `momoBody`(#7FD1AE)；不引入新依赖/中间件。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 进入兽医工作台 | role=VET, 路由 `/vet/workbench` | NavigationBar 指示/选中态、涟漪、主按钮呈薄荷 `#5BCBBB`，非紫 | N/A |
| 兽医登录页 | 路由 `/vet/login` | 登录主按钮 `backgroundColor` 薄荷，非紫 | N/A |
| 兽医「我的」在线态 | `_online=true` | 在线指示色薄荷 `#5BCBBB`，非紫 | N/A |
| 用户侧任意屏 | 路由非 `/vet/*` | 主色仍紫 `#845EC9`，零变化（回归保护） | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/core/theme/colors.dart` -- 颜色 token 单一事实源；新增 vet 薄荷 token 块
- `petgo_app/lib/core/theme/app_theme.dart` -- 主题装配；现仅 `light`(紫种子)，新增 `vet`(薄荷种子)
- `petgo_app/lib/core/router/app_router.dart` -- 4 个 vet 路由 builder（`/vet/login`、`/vet/workbench`、`/vet/conversation/:id`、`/vet/request/:id`）注入点
- `petgo_app/lib/features/vet/presentation/vet_login_page.dart:95` -- `accentConsult`→vet 薄荷
- `petgo_app/lib/features/vet/presentation/vet_active_page.dart:97-98` -- `accentConsult`→vet 薄荷
- `petgo_app/lib/features/vet/presentation/vet_me_page.dart:157` -- `accentGrowth`→vet 薄荷
- `petgo_app/test/` -- 若有 vet 屏断言紫色或主题色，需同步更新

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/lib/core/theme/colors.dart` -- 新增 vet token 块（additive）：`vetPrimary=#5BCBBB`、`vetPrimaryDeep=#203D39`、`vetSurface=#EFF7F4`、`vetSurface2=#F8FBFA`、`vetTopBar=#2B2540`(备 P1)、`vetToolbar=#1A2B28`(备 P1)、`vetOnAccent=#FFFFFF`。带注释标注权威来源与「P1 备用」项 -- 给 vet 主题与后续逐屏提供薄荷色源
- [x] `petgo_app/lib/core/theme/app_theme.dart` -- 抽 `_build(seed)`；`light` 保持紫种子，新增 `static ThemeData get vet`（薄荷 `vetPrimary` 种子），复用同套 textTheme/scaffoldBackground -- 让 vet 子树有独立薄荷 ColorScheme
- [x] `petgo_app/lib/core/router/app_router.dart` -- 加顶层 helper `_vetScoped(child)=Theme(data: AppTheme.vet,...)`，4 个 vet 路由 builder 全部套用；补 `material.dart` + `app_theme.dart` import -- vet 子树默认薄荷，物理隔离用户侧紫
- [x] `petgo_app/lib/features/vet/presentation/{vet_login_page,vet_active_page,vet_me_page}.dart` -- 3 文件 4 处 `AppColors.accentConsult`/`accentGrowth` → `AppColors.vetPrimary` -- 消除 vet 屏显式紫引用
- [x] `petgo_app/test/` -- 跑全量测试：313 passed，无 vet 屏断言旧紫色 → 无需改测试 -- L0 绿

**Acceptance Criteria:**
- Given role=VET 进入 `/vet/workbench`，when 渲染底部导航与主按钮，then 主色为薄荷 `#5BCBBB` 系而非紫 `#845EC9`
- Given 用户/游客侧任一路由，when 渲染，then 主色仍为紫 `#845EC9`，无任何回归
- Given `colors.dart` 改动，when 检视 diff，then 所有现有常量名与值零改动，仅新增 vet token
- Given `flutter analyze` 与 `flutter test`，when 执行，then 全绿（0 error）

## Design Notes

vet 路由注入用 builder 包裹而非 ShellRoute 重构——影响面最小、零路由语义变更。示例：

```dart
GoRoute(path: '/vet/login',
  builder: (c, s) => Theme(data: AppTheme.vet, child: const VetLoginPage())),
```

`AppTheme.vet` 与 `light` 同构，仅 `seedColor` 换 `vetPrimary`；scaffoldBackground 仍用 `AppColors.base`(白)，符合原型 vet 浅底。深色顶栏 `#2B2540` 本步**不应用**，仅落 token，避免与 P1 逐屏结构改动冲突。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues found（0 error）
- `cd petgo_app && flutter test` -- expected: All tests passed

## Suggested Review Order

**主题定义（设计意图入口）**

- vet 主题入口：`light`/`vet` 共用 `_build(seed)`，仅种子色不同
  [`app_theme.dart:17`](../../petgo_app/lib/core/theme/app_theme.dart#L17)

- vet 薄荷 token 块（additive，含 P1 备用项）
  [`colors.dart:67`](../../petgo_app/lib/core/theme/colors.dart#L67)

**主题注入（作用域隔离）**

- `_vetScoped` helper：给 vet 子树包薄荷主题，与用户侧紫物理隔离
  [`app_router.dart:50`](../../petgo_app/lib/core/router/app_router.dart#L50)

- 4 个 vet 路由 builder 套用（此为首个）
  [`app_router.dart:86`](../../petgo_app/lib/core/router/app_router.dart#L86)

- 弹窗逃逸 patch：`showDialog` 挂根 Navigator → 显式包薄荷主题（审查发现）
  [`vet_conversation_page.dart:77`](../../petgo_app/lib/features/vet/presentation/vet_conversation_page.dart#L77)

**显式紫引用收口**

- 登录主按钮 → vetPrimary
  [`vet_login_page.dart:95`](../../petgo_app/lib/features/vet/presentation/vet_login_page.dart#L95)

- 进行中卡头像 → vetPrimary
  [`vet_active_page.dart:97`](../../petgo_app/lib/features/vet/presentation/vet_active_page.dart#L97)

- 在线态指示色 → vetPrimary
  [`vet_me_page.dart:157`](../../petgo_app/lib/features/vet/presentation/vet_me_page.dart#L157)
