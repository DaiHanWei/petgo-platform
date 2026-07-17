import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/analytics_autocapture.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/theme/app_theme.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/consult/presentation/consult_refresh.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 成长档案分享页深链 → go_router location 的纯映射（L0 可测）。
/// `tailtopia://card/{token}` → `/profile`（成长档案 Tab，分享页 CTA 第①级）。
/// `tailtopia://open` → `/home`（下载引导落地页 `s.tailtopia.id/get` 唤起已装 app 的通用深链）。
/// 其它 scheme/host 暂不识别（返回 null，调用方忽略）。
String? deepLinkToLocation(Uri uri) {
  if (uri.scheme == 'tailtopia' && uri.host == 'card') {
    return '/profile';
  }
  if (uri.scheme == 'tailtopia' && uri.host == 'open') {
    return '/home';
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
    // 分析身份绑定：登录(含待引导新用户,有 id)→ identify(哈希 distinctId)；登出 → reset。
    // 收口于此单点，保持 AuthController 纯净（不在 9 处 call-site 散埋副作用）。
    ref.listen<AuthState>(authControllerProvider, (prev, next) {
      final prevId = prev?.profile?.id;
      final nextId = next.profile?.id;
      if (next.status != AuthStatus.guest && nextId != null) {
        // 非 guest 且拿到 id（含 newUserPendingOnboarding 引导期）。仅在 id 变化时上报，避免改资料重复 identify；
        // 直接切到另一用户（中途未过 guest）时先 reset 再 identify，遵守 PostHog「换人先 reset」规约。
        if (nextId != prevId) {
          if (prevId != null) Analytics.reset();
          Analytics.identifyUser(nextId);
        }
      } else if (next.status == AuthStatus.guest && prevId != null) {
        // 仅在此前确有身份时 reset，避免纯游客态抖动平白重置匿名 id（破坏匿名漏斗连续性）。
        Analytics.reset();
      }

      // 账号切换（首次登录 / 直切另一用户 / 冷启动恢复）→ 失效上一用户维度缓存，
      // 新用户进各 Tab 时按新 token 重新拉取（修：同设备换账号后档案/时间线/我的发布/问诊列表仍显上一用户）。
      // 只在「变为某个非游客用户」时失效；退出登录（转 guest）**不**在此失效——游客态无 token，
      // 失效会立即触发 /me 等重拉 → 401 → 强制登录弹窗。清除靠受控 Tab 对游客不可见 + 下次登录重拉达成。
      // 兽医登录 profile=null（nextId=null）自动跳过，不误刷用户维度缓存。
      if (next.status != AuthStatus.guest && nextId != null && nextId != prevId) {
        resetUserScopedCaches(ref);
      }
    });
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
          // 全局点击 autocapture：根部旁路监听所有 tap → button_tapped（不影响正常点击）。
          child: AnalyticsAutocapture(child: child ?? const SizedBox.shrink()),
        );
      },
    );
  }
}

/// 失效所有「当前用户维度」的缓存（provider + 问诊本地列表刷新信号），使切换账号后各 Tab 重拉。
/// 收口于 app.dart 组合根：跨 feature 的缓存协调放在根部，AuthController 保持纯净。
/// 仅在「变为某个非游客用户」时调用（见上方 authControllerProvider 监听）——退出登录不调，
/// 游客态无 token，失效会立即触发 /me 重拉 → 401 → 强制登录弹窗。
void resetUserScopedCaches(WidgetRef ref) {
  ref.invalidate(petProfileProvider); // 成长档案 / 我的：宠物档案
  ref.invalidate(timelineFirstPageProvider); // 成长档案：时间线首页
  ref.invalidate(archiveStatsProvider); // 成长档案 / 我的：统计栏
  ref.invalidate(milestoneListProvider); // 成长档案：里程碑
  ref.invalidate(myPostsProvider); // 我的：我的发布
  ref.invalidate(feedProvider); // 首页 Feed（按新用户宠物状态重过滤）
  ref.invalidate(unreadCountProvider); // 通知铃铛未读角标（bug 20260625-088：换账号防显示上个用户角标）
  ref.read(consultRefreshProvider.notifier).bump(); // 问诊页 _active/_history 重拉
}
