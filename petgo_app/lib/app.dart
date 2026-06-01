import 'package:flutter/material.dart';
import 'package:petgo/core/router/app_router.dart';
import 'package:petgo/core/theme/app_theme.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// 应用根 Widget。
/// - go_router 驱动路由（Story 1.2：5 位 Tab 外壳）
/// - V1 仅浅色模式
/// - i18n：跟随设备语言，支持 en / id，其他语言回退 en（无写死字符串）
class PetGoApp extends StatelessWidget {
  const PetGoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      onGenerateTitle: (ctx) => AppLocalizations.of(ctx).appTitle,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      themeMode: ThemeMode.light,
      routerConfig: appRouter,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      // 设备语言 id → 印尼语；其余（含 en 及未支持语言）回退英语。
      localeResolutionCallback: (locale, supported) {
        if (locale != null && locale.languageCode == 'id') {
          return const Locale('id');
        }
        return const Locale('en');
      },
    );
  }
}
