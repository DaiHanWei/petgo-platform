import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../storage/prefs.dart';

/// 启动时从 prefs 读到的语言覆盖（main 中 override；缺省 null=跟随设备）。
final localeOverrideProvider = Provider<String?>((ref) => null);

/// 语言设置（Story 7.2，FR-27）。优先级：用户手动选择 > 设备语言 > en 回退。
///
/// state=null → 跟随设备（由 `MaterialApp.localeResolutionCallback` 回退英语）；
/// state=Locale('id'|'en') → 手动锁定。切换即时重建全树（无需重启）+ 持久化到 prefs。
class LocaleController extends Notifier<Locale?> {
  @override
  Locale? build() {
    final code = ref.read(localeOverrideProvider);
    return _toLocale(code);
  }

  /// 当前语言码（'id'/'en'）；跟随设备时返回 null。
  String? get code => state?.languageCode;

  /// 手动切换（code='id'|'en'，或 null=跟随设备）。即时生效 + 持久化。
  void setLanguage(String? code) {
    state = _toLocale(code);
    // 持久化（fire-and-forget；prefs 失败不影响本次会话即时生效）。
    AppPrefs.create().then((prefs) {
      if (code == null) {
        prefs.setLocaleCode(''); // 空串=跟随设备
      } else {
        prefs.setLocaleCode(code);
      }
    });
  }

  static Locale? _toLocale(String? code) {
    if (code == 'id') return const Locale('id');
    if (code == 'en') return const Locale('en');
    return null; // 跟随设备
  }
}

final localeControllerProvider =
    NotifierProvider<LocaleController, Locale?>(LocaleController.new);
