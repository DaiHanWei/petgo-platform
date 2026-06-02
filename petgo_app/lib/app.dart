import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:petgo/core/l10n/locale_controller.dart';
import 'package:petgo/core/router/app_router.dart';
import 'package:petgo/core/theme/app_theme.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// 应用根 Widget。
/// - go_router 驱动路由（provider 化，含受控路由门控 redirect）
/// - V1 仅浅色模式
/// - i18n：跟随设备语言，支持 en / id，其他语言回退 en（无写死字符串）
class PetGoApp extends ConsumerWidget {
  const PetGoApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Story 7.2：用户手动选择优先（localeController）；null → 跟随设备（resolutionCallback 回退英语）。
    final manualLocale = ref.watch(localeControllerProvider);
    return MaterialApp.router(
      onGenerateTitle: (ctx) => AppLocalizations.of(ctx).appTitle,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      themeMode: ThemeMode.light,
      routerConfig: ref.watch(routerProvider),
      locale: manualLocale,
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
