import 'package:flutter/widgets.dart';

/// 设计 token —— 圆角（UX-DR1）。xs → full + phone（卡片上圆角 14px）。
class AppRounded {
  AppRounded._();

  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double full = 999;

  /// 手机卡片上圆角。
  static const double phone = 14;

  static const BorderRadius smRadius = BorderRadius.all(Radius.circular(sm));
  static const BorderRadius mdRadius = BorderRadius.all(Radius.circular(md));
  static const BorderRadius lgRadius = BorderRadius.all(Radius.circular(lg));
  static const BorderRadius phoneRadius = BorderRadius.all(Radius.circular(phone));
}
