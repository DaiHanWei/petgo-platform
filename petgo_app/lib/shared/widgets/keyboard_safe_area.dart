import 'package:flutter/material.dart';

/// 键盘避让共享件。标准见 `petgo_app/CLAUDE.md` §「输入 / 键盘避让」。
///
/// 任何获得焦点的输入框必须完整显示在软键盘上方，不被遮挡。

/// [KeyboardSafeArea]：表单页体包裹。
///
/// 让页面体既能**填满视口**（内容不足时底部输入沉底），又在**键盘弹出时可上滚**
/// 露出焦点框。依赖宿主 Scaffold `resizeToAvoidBottomInset: true`（body 高度已扣除
/// 键盘），故内部不再叠加 viewInsets padding（避免双算）。
///
/// 用法：`Scaffold(body: SafeArea(child: KeyboardSafeArea(child: Column(...))))`。
/// 原本用 `Column + Expanded/Spacer` 沉底按钮的页面，直接用本件包 `Column`
/// （`Spacer()` 仍有效）。本就 `ListView`/`SingleChildScrollView` 的可滚页无需再包。
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

/// [KeyboardInset]：底部内边距包裹。
///
/// 给**底部弹层**与**浮层贴附输入栏**在其内容底部加 `= viewInsets.bottom` 的
/// 动画内边距，随键盘顶部上移。
///
/// 用法：`showModalBottomSheet(isScrollControlled: true, builder: (_) =>
/// KeyboardInset(child: ...))`；贴附输入栏浮层同样套 `KeyboardInset`。
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
