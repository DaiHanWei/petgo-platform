import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/theme/app_theme.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 成长档案分享页深链 → go_router location 的纯映射（L0 可测）。
/// `tailtopia://card/{token}` → `/profile`（成长档案 Tab，分享页 CTA 第①级）。
/// 其它 scheme/host 暂不识别（返回 null，调用方忽略）。
String? deepLinkToLocation(Uri uri) {
  if (uri.scheme == 'tailtopia' && uri.host == 'card') {
    return '/profile';
  }
  return null;
}

/// 应用根 Widget。
/// - go_router 驱动路由（provider 化，含受控路由门控 redirect）
/// - 外部深链（app_links）：热启动直接导航；冷启动存 [pendingDeepLinkProvider] 交 SplashPage 消费
/// - V1 仅浅色模式、portrait-only（NFR-14）
/// - i18n：跟随设备语言，支持 en / id，其他语言回退 en（无写死字符串）
/// - 无障碍（Story 7.4 / NFR-13）：动态字体上限 clamp ≤ [maxTextScale]，防超大字号破布局（标题封顶）。
class TailTopiaApp extends ConsumerStatefulWidget {
  const TailTopiaApp({super.key});

  /// 动态字体放大上限（NFR-13「≤3 级」）：body 及以下随系统缩放，封顶防溢出/截断关键信息。
  static const double maxTextScale = 1.3;

  @override
  ConsumerState<TailTopiaApp> createState() => _TailTopiaAppState();
}

class _TailTopiaAppState extends ConsumerState<TailTopiaApp> {
  final AppLinks _appLinks = AppLinks();
  StreamSubscription<Uri>? _linkSub;

  @override
  void initState() {
    super.initState();
    _initDeepLinks();
  }

  Future<void> _initDeepLinks() async {
    // 冷启动初始链接：app 尚在 splash，存 pending 交 SplashPage.onComplete 消费（避免与 go('/home') 抢路由）。
    try {
      final initial = await _appLinks.getInitialLink();
      if (initial != null) {
        final loc = deepLinkToLocation(initial);
        if (loc != null) ref.read(pendingDeepLinkProvider.notifier).set(loc);
      }
    } catch (_) {
      // 拿不到初始链接不阻塞启动。
    }
    // 热启动（app 已活，后台/前台被深链唤起）：直接导航。
    _linkSub = _appLinks.uriLinkStream.listen((uri) {
      final loc = deepLinkToLocation(uri);
      if (loc != null) ref.read(routerProvider).go(loc);
    }, onError: (_) {});
  }

  @override
  void dispose() {
    _linkSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
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
      // 无障碍（Story 7.4 AC3）：clamp 系统字体缩放上限，防超大字号破布局；不可移除。
      builder: (context, child) {
        final mq = MediaQuery.of(context);
        return MediaQuery(
          data: mq.copyWith(textScaler: mq.textScaler.clamp(maxScaleFactor: TailTopiaApp.maxTextScale)),
          child: child ?? const SizedBox.shrink(),
        );
      },
    );
  }
}
