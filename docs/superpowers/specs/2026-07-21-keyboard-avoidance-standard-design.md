# Spec：键盘避让标准（输入框不被软键盘遮挡）

> 分支 petgo_app（Flutter 前端，纯前端改动）。自包含，可本地/云端 L0；L2 抽样留本地。
> 与用户沟通中文。源起 bug 20260713-285（成长日记打字看不到内容）的通用化。

## 1. 背景与问题

App 内多处输入框在软键盘弹出后**被键盘覆盖**，用户看不到自己输入的内容。当前 20 个含输入框的文件键盘规避手法**参差不齐**：部分底部弹层/页面已用 `viewInsets`/`isScrollControlled`，但多数表单页（nickname、pet_profile_edit、ticket_compose、csat、refund_account、consult_case_form 等）与底部贴附输入栏（comment_composer、im_chat）无保护。

需要一条**统一边界标准** + **共享封装件**，把现存页面全部整改合规，并写入 `petgo_app/CLAUDE.md` 作为新页面的强制约定。

## 2. 标准（边界定义）

**任何获得焦点的输入框必须完整显示在软键盘上方，不被遮挡。** 按输入所在「形态」分四类：

| 形态 | 要求 | 机制 |
|---|---|---|
| **可滑动表单页** | 聚焦时自动滚动，把输入框露到键盘上方 | 输入置于可滚动体（`KeyboardSafeArea` 或本就可滚）+ Scaffold `resizeToAvoidBottomInset: true` |
| **不可滑动页（输入在底部）** | 内容整体上移，输入贴键盘上方 | `KeyboardSafeArea`（填满视口 + 键盘弹出时可上滚）+ `resizeToAvoidBottomInset: true` |
| **不可滑动页（输入在顶部/上方）** | 零动作：输入本就在键盘上方，**不覆盖、不上移** | `resizeToAvoidBottomInset: true` 从底部压缩高度、顶部不动 → 顶部框天然在键盘上方；套 `KeyboardSafeArea` 也不触发多余滚动（`ensureVisible` 仅当焦点框被挡时才滚） |
| **底部弹层 bottom sheet** | 弹层内容随键盘上移 | `showModalBottomSheet(isScrollControlled: true)` + 内容套 `KeyboardInset` |
| **底部贴附输入栏（评论/聊天）** | 输入栏顶到键盘正上方 | 宿主 Scaffold `resizeToAvoidBottomInset: true`（body 内 Column 底栏自动上移）；若为浮层/非 body 挂载则套 `KeyboardInset` |
| **dialog** | 整体保持在键盘上方 | Flutter 默认（居中 + viewInsets 顶起）——仅核实，通常无需改 |

> **边界说明**：标准只保证「获得焦点的输入框」在键盘上方。若顶部输入框**下方**还有非焦点内容（如提交按钮）被键盘挡住，套了 `KeyboardSafeArea` 的页面可上滑看到，符合标准；「输入时下方按钮也必须可见」是更强诉求，当前标准不强制，需按页单独提出。

**新增任何含输入框的页面/弹层，必须套用对应机制**（写入 CLAUDE.md）。

## 3. 共享件

新文件 `lib/shared/widgets/keyboard_safe_area.dart`，含两个小而专的件：

### 3.1 `KeyboardSafeArea`（表单页体包裹）
职责：让页面体既能**填满视口**（内容不足时底部输入沉底），又在**键盘弹出时可上滚**露出焦点框。依赖 Scaffold `resizeToAvoidBottomInset:true`（body 高度已扣除键盘），故内部不再叠加 `viewInsets` padding（避免双算）。

```dart
class KeyboardSafeArea extends StatelessWidget {
  const KeyboardSafeArea({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          // IntrinsicHeight 让 child 内 Column 可用 Spacer/spaceBetween 把底部输入沉底，
          // 同时整体可滚（键盘弹出 body 变矮时露出焦点框）。
          child: IntrinsicHeight(child: child),
        ),
      ),
    );
  }
}
```

用法：`Scaffold(body: SafeArea(child: KeyboardSafeArea(child: Column(...))))`。原本用 `Column + Expanded/Spacer` 沉底按钮的页面，直接用 `KeyboardSafeArea` 包 `Column`（`Column` 内 `Spacer()` 仍有效）。本就 `ListView`/`SingleChildScrollView` 的可滚页**无需**再包（Flutter 聚焦自动 ensureVisible 已生效），仅核实 `resizeToAvoidBottomInset` 未被设 false。

### 3.2 `KeyboardInset`（底部内边距包裹）
职责：给**底部弹层**与**浮层贴附输入栏**在其内容底部加 `= viewInsets.bottom` 的动画内边距，随键盘顶部上移。

```dart
class KeyboardInset extends StatelessWidget {
  const KeyboardInset({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => AnimatedPadding(
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
        child: child,
      );
}
```

用法：`showModalBottomSheet(isScrollControlled: true, builder: (_) => KeyboardInset(child: ...))`；贴附输入栏浮层同样套 `KeyboardInset`。

### 3.3 测试
`test/shared/keyboard_safe_area_test.dart`：
- `KeyboardInset`：注入 `tester.view.viewInsets`（模拟键盘高度）→ 断言子内容底部 padding == 键盘高度；键盘收起 → padding 归 0。
- `KeyboardSafeArea`：在受限高度里放「顶部内容 + Spacer + 底部输入」→ 断言底部输入沉底；缩小可用高度（模拟键盘）→ 断言变为可滚动（`Scrollable` 可滚动到底部输入）。

## 4. 审计与修复计划

**顺序：** 建共享件 → 全量走查 → 逐页最小修 → 写 CLAUDE.md → 验收。

### 4.1 全量走查（20 文件，逐个分类判合规出表）
按 §2 四形态归类每个文件，产出 `文件 | 形态 | 现状 | 修法 | 结论(合规/已修)` 表。初步分类：

- **表单页**：vet_final_diagnosis、vet_login、nickname、publish_compose、triage_upload、pet_profile_edit、pet_profile_create、pet_form_fields（widget）、id_card、health_list、csat、delete_account、consult_case_form、ticket_compose、refund_account、mint_onboarding、me_page
- **底部弹层**：consult_diagnosis_sheet 等含输入的 sheet（部分已用 viewInsets，核实/统一为 `KeyboardInset`）
- **贴附输入栏**：comment_composer、im_chat_placeholder（会话输入）
- **dialog**：consult_rating_dialog

> 注：`me_page`/`health_list`/`id_card` 等出现在输入清单可能因内嵌 sheet/搜索框，走查时按实际输入位置定形态。

### 4.2 逐页最小修
- 表单页：非滚动或底部沉底输入 → 包 `KeyboardSafeArea`；本就可滚 → 仅核实 `resizeToAvoidBottomInset` 未设 false。
- 底部弹层：补 `isScrollControlled: true` + `KeyboardInset`。
- 贴附输入栏：确保宿主 Scaffold `resizeToAvoidBottomInset:true`；浮层挂载则套 `KeyboardInset`。
- dialog：核实焦点框在键盘上方，通常不改。
- **已合规的不动**，表里标「已合规」。最小 diff，不顺手重构无关代码。

### 4.3 写标准进 `petgo_app/CLAUDE.md`
在 §「主题 & 无障碍」旁新增小节「输入 / 键盘避让」，收录 §2 标准表 + 两个共享件用法 + 「新页必须套用」的强制约定。**根 CLAUDE.md 不动**（这是 Flutter app 级 UI 标准）。

## 5. 验收

- **L0（全量）**：`flutter analyze`（0 issue）+ `flutter test`（含新 `keyboard_safe_area_test`）。
- **L2（抽样 4）**：Android 模拟器聚焦截图，每形态一个代表页：表单页（如 ticket_compose）/ 底部弹层（如 consult_diagnosis_sheet）/ 贴附输入栏（comment_composer 或会话页）/ dialog（consult_rating_dialog）。确认焦点框在键盘上方。

## 6. 落地纪律

- 纯前端；无后端、无迁移、无 arb（除非某页顺带缺字符串，届时两份 arb 同步）。
- 一次只碰一处，最小改；已合规不动。
- 关联记忆：[[setstate-arrow-returns-future-debug-trap]]（debug 陷阱）、[[v11-ux-fidelity-rework-0718]]。
- 关联 bug：20260713-285（成长日记键盘遮挡，本标准的具体来源）。
