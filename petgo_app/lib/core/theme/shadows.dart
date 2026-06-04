import 'package:flutter/material.dart';

/// 设计 token —— 柔和阴影（PetGo Prototype `--sh-sm/md/lg/mint`）。
///
/// 对应 CSS 多层 box-shadow，组件用 `boxShadow: AppShadows.md`。
/// 注：`blurRadius` = CSS 第三值，`spreadRadius` 默认 0，`offset` = 前两值。
class AppShadows {
  AppShadows._();

  static const Color _ink = Color(0xFF2B2A27);
  static const Color _mint = Color(0xFF4FB389);

  /// 0 1px 2px /.05, 0 2px 8px /.04
  static const List<BoxShadow> sm = [
    BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 1), blurRadius: 2),
    BoxShadow(color: Color(0x0A2B2A27), offset: Offset(0, 2), blurRadius: 8),
  ];

  /// 0 2px 6px /.05, 0 10px 26px /.07
  static const List<BoxShadow> md = [
    BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 6),
    BoxShadow(color: Color(0x122B2A27), offset: Offset(0, 10), blurRadius: 26),
  ];

  /// 0 8px 20px /.08, 0 22px 48px /.10
  static const List<BoxShadow> lg = [
    BoxShadow(color: Color(0x142B2A27), offset: Offset(0, 8), blurRadius: 20),
    BoxShadow(color: Color(0x1A2B2A27), offset: Offset(0, 22), blurRadius: 48),
  ];

  /// 薄荷光晕：0 8px 22px rgba(79,179,137,.30)
  static const List<BoxShadow> mint = [
    BoxShadow(color: Color(0x4D4FB389), offset: Offset(0, 8), blurRadius: 22),
  ];

  // 便于在常量上下文外引用基色
  static const Color ink = _ink;
  static const Color mintGlow = _mint;
}
