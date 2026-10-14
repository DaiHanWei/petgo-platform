import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 身份证主题（V1.3.4 ktp）页面内共用的控件样式，取自 UI 稿 `ui-v1.3.4-ktp.html` 的 CSS。
///
/// 只在 id_card 相关页面里引用——**不进全局主题**（全局 inputDecorationTheme / filledButtonTheme 改了会波及全 App）。

/// 输入框圆角（稿 `.in{ border-radius:12px }`）。
const double kIdCardInputRadius = 12;

/// 输入框描边（稿 `.in{ border:1.5px solid var(--line) }`，聚焦 / 展开 mint，`.in.bad` 红）。
OutlineInputBorder _inputBorder(Color color) => OutlineInputBorder(
      borderRadius: BorderRadius.circular(kIdCardInputRadius),
      borderSide: BorderSide(color: color, width: 1.5),
    );

/// 表单输入框装饰（稿 `.in`：白底 · 圆角 12 · 1.5 描边 line · 内边距 11/14 · 14 号字 · 占位 muted）。
InputDecoration idCardInputDecoration({required String hintText, bool error = false}) => InputDecoration(
      hintText: hintText,
      hintStyle: const TextStyle(color: AppColors.muted, fontSize: 14),
      counterText: '',
      filled: true,
      fillColor: AppColors.surface,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      border: _inputBorder(error ? AppColors.danger : AppColors.line),
      enabledBorder: _inputBorder(error ? AppColors.danger : AppColors.line),
      focusedBorder: _inputBorder(error ? AppColors.danger : AppColors.mint),
    );

/// 主按钮（稿 `.btn.fill`：高 48 · 胶囊 24 · 15/w700；`.dis` 置灰 line 底 muted 字）。
/// [busy] = 提交中：保持紫底（稿 C2 白色转圈），调用方同时传 onPressed=null 防连点。
ButtonStyle idCardPrimaryButtonStyle({bool busy = false}) => FilledButton.styleFrom(
      backgroundColor: AppColors.mint,
      foregroundColor: Colors.white,
      disabledBackgroundColor: busy ? AppColors.mint : AppColors.line,
      disabledForegroundColor: busy ? Colors.white : AppColors.muted,
      minimumSize: const Size(64, 48),
      padding: const EdgeInsets.symmetric(horizontal: 16),
      shape: const StadiumBorder(),
      // 按钮 textStyle 整体覆盖、不与全局主题合并 → 必须显式带 Poppins，否则 Android 落到 Roboto
      textStyle: const TextStyle(fontFamily: 'Poppins', fontSize: 15, fontWeight: FontWeight.w700),
    );

/// 描边按钮（稿 `.btn.out`：高 44 · 胶囊 · 1.5 lineViolet 描边 · mint 14/w600）。
ButtonStyle idCardOutlineButtonStyle() => OutlinedButton.styleFrom(
      foregroundColor: AppColors.mint,
      // 提交中按钮暂不可点但保持紫色（稿 C2），不走默认灰
      disabledForegroundColor: AppColors.mint,
      side: const BorderSide(color: AppColors.lineViolet, width: 1.5),
      minimumSize: const Size(64, 44),
      padding: const EdgeInsets.symmetric(horizontal: 16),
      shape: const StadiumBorder(),
      textStyle: const TextStyle(fontFamily: 'Poppins', fontSize: 14, fontWeight: FontWeight.w600),
    );

/// 底部提示（稿 `.snack`：浮动 · 左右 16 · 圆角 8 · ink 底白字 13 号）。
/// [bottom]：页面有吸底主按钮时调大，免得浮动条盖住按钮（建卡页 C3）。
SnackBar idCardSnackBar(String msg, {double bottom = 16}) => SnackBar(
      content: Text(msg, style: const TextStyle(color: Colors.white, fontSize: 13)),
      behavior: SnackBarBehavior.floating,
      backgroundColor: AppColors.ink,
      margin: EdgeInsets.fromLTRB(16, 0, 16, bottom),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
    );
