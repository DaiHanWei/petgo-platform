import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/theme/app_theme.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/consult/presentation/consult_refresh.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/me/presentation/phone_edit_sheet.dart';
import 'package:tailtopia/features/me/domain/phone_soft_prompt.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/profile/data/health_record_repository.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/newbie_task_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 成长档案分享页深链 → go_router location 的纯映射（L0 可测）。
///
/// - `tailtopia://card/{token}` → `/pet/{token}`（**被分享的那只宠物**，V1.1.6 Story 2.4）
/// - `tailtopia://post/{token}` → `/shared-post/{token}`（**只有被分享的那一条内容**，V1.1.6 Story 9.3）
/// - `tailtopia://open` → `/home`（下载引导落地页 `s.tailtopia.id/get` 唤起已装 app 的通用深链）
/// - `tailtopia://open/<路径>` → 该路径（🔧 **仅 debug 包**，本地验收导航用；release 恒 `/home`）
/// - 其它 scheme/host 暂不识别（返回 null，调用方忽略）
///
/// ## 🔴 V1.1.6 Story 2.4 修的就是这里
/// 改之前这个函数把 `card` 深链**整个映射成 `/profile`**，连 token 都没解析 —— 于是：
/// 未登录的人点开看到给游客做的**示例成长本**，已登录有宠的人点开看到**自己家的宠物**。
/// 两种都不是被分享的那一只。
///
/// ⚠️ token 只从 **path** 取，不接受 query 覆盖 —— 少一个可被构造的入口。
/// 没有 token（如裸 `tailtopia://card`）则退回 `/profile`：没有 token 就没有可展示的宠物。
///
/// ⚠️ 「作者本人点自己的链接该落回自己的档案页」（AD-2 Rule 3）**不在这里判**：
/// 那是 Diary 页单一状态判定入口的职责（AD-15）。在这里判等于开第二处判定，
/// 两处迟早分叉，而分叉的表现是作者丢管理入口、或访客拿到管理入口。
String? deepLinkToLocation(Uri uri) {
  if (uri.scheme == 'tailtopia' && uri.host == 'card') {
    final token = uri.pathSegments.isEmpty ? '' : uri.pathSegments.first;
    return token.isEmpty ? '/profile' : '/pet/$token';
  }
  // 单条内容分享（V1.1.6 Story 9.3）。
  // 🔴 **与 card 是两个独立类型、两个落点**（AD-15 Rule 5）：
  // `card` 落整本档案的只读视图，`post` 只落被分享的那一条。
  // 合成一个深链目标，就等于把「我只想分享一条」变成「我把整本都给你了」。
  if (uri.scheme == 'tailtopia' && uri.host == 'post') {
    final token = uri.pathSegments.isEmpty ? '' : uri.pathSegments.first;
    // 没 token 就没有可展示的那一条 —— 落首页，不要退回任何档案页
    // （退到档案页就成了"点别人的分享链接看到自己家宠物"，正是 2.4 修掉的那个 bug）。
    return token.isEmpty ? '/home' : '/shared-post/$token';
  }
  if (uri.scheme == 'tailtopia' && uri.host == 'open') {
    // 🔧 DEBUG ONLY：`tailtopia://open/<路径>` 直达任意路由，供本地验收导航用。
    //
    // 为什么需要它：Toko 归 DEP-1 未拍板，**不占 Tab 位**（`AppTab` 四值无空位，
    // 且契约禁改 `bottom_tab_bar.dart`）。于是冷启动后走到 `/shop` 的唯一通路是
    // 「登录 → 建档 → 加驱虫记录 → 点 FR-110 品类跳转」—— 验收十个页面时太绕。
    //
    // 🔒 `kDebugMode` 硬门：release 包里这段被编译期裁掉，
    //    `tailtopia://open/...` 行为与从前逐字一致（恒 `/home`），线上零影响。
    //    形态照抄仓库既有先例（`settings_page.dart` 的 `DEV_DELETE_ACCOUNT` 截图钩子）。
    if (kDebugMode && uri.path.length > 1) {
      return uri.path + (uri.query.isEmpty ? '' : '?${uri.query}');
    }
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

class _TailTopiaAppState extends ConsumerState<TailTopiaApp> with WidgetsBindingObserver {
  final AppLinks _appLinks = AppLinks();
  StreamSubscription<Uri>? _linkSub;

  @override
  void initState() {
    super.initState();
    // 前后台切换 → 同步腾讯 IM doForeground/doBackground（Flutter 裸 SDK 不自动做；
    // 不同步则后台收不到厂商离线推送 / 前台重复弹通知，见 core/push/push_service.dart）。
    WidgetsBinding.instance.addObserver(this);
    _initDeepLinks();

    // 手机号软引导接进推送权限的排队入口（Story 7.2 / AD-14 Rule 7：先推送权限、后手机号）。
    //
    // 🛡 这里**只注册**，不弹任何东西 —— 它跟着推送权限那几个触发点走。
    //    注册放这里而不是 `main.dart`：判定要读 `authControllerProvider`（当前用户档案），
    //    而 main 的启动回调在 ProviderScope 之外，拿不到 ref。
    // ⚠️ 取值全部是**函数**，到触发那一刻才读 —— 用户可能刚在设置页填过号码。
    // ⚠️ 绝不在别处另写一套「同时命中谁先谁后」的判定，那正是 Rule 7 要消除的不确定性。
    PhoneSoftPrompt.register(
      prefs: AppPrefs.create,
      registeredAt: () async => ref.read(authControllerProvider).profile?.createdAt,
      hasPhone: () async {
        final phone = ref.read(authControllerProvider).profile?.phone;
        return phone != null && phone.isNotEmpty;
      },
      showSheet: () async {
        final ctx = rootNavigatorKey.currentContext;
        if (ctx == null) return false;
        return PhoneEditSheet.openDetailed(ctx, entry: 'soft_prompt');
      },
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    ref.read(pushServiceProvider).onAppLifecycleChanged(state);
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
    // 热启动（app 已活，后台/前台被深链唤起）。
    // ⚠️ 走 goDeepLinkFromLiveApp 而**不是**直接 go：落点若在 Tab 外（如访客视图
    // `/pet/{token}`），go 掉整个栈会让用户按返回直接退出 App —— 见该函数的说明。
    _linkSub = _appLinks.uriLinkStream.listen((uri) {
      final loc = deepLinkToLocation(uri);
      if (loc != null) goDeepLinkFromLiveApp(ref, loc);
    }, onError: (_) {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
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
          // role 随 identify 落成 person property：留存看板据此把兽医号剔出宠主大盘。
          Analytics.identifyUser(nextId, role: next.role);
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

      // 系统推送注册（收口于此单点，与 identify/缓存失效同一监听）：
      // ① 变为已登录态（冷启动恢复 / 登录 / 兽医登录 / 引导完成转正）→ 按门控注册离线推送。
      //    C 端受 F7 门控（asked=false 时内部直接跳过，不弹权限）；兽医旁路直注册。
      // ② 直接换账号（authenticated→authenticated 且 id 变化，中途未过 guest——与上方
      //    resetUserScopedCaches 同一场景，code-review 2026-08-07）：必须先解绑旧账号推送
      //    + IM 登出旧身份，再按新账号重登注册——否则设备 token 与 IM 登录态仍是上一用户，
      //    B 的设备持续收 A 的推送（跨用户隐私泄漏，与 IM 漏登出同型）。
      // fire-and-forget：注册失败静默，推送是增强能力。
      final becameAuthed =
          next.status == AuthStatus.authenticated && prev?.status != AuthStatus.authenticated;
      final switchedUser = next.status != AuthStatus.guest &&
          nextId != null &&
          prevId != null &&
          nextId != prevId;
      if (switchedUser) {
        final push = ref.read(pushServiceProvider);
        final im = ref.read(imServiceProvider);
        // 同步作废旧凭证 + 记代际（PR#34 finding #10）：新账号若在 5s 窗口内已自行 IM 登录
        //（如直接进会话页），迟到的 logout 按代际放弃，不清新账号会话。
        final logoutGeneration = im.invalidateCredential();
        push
            .unregister()
            .timeout(const Duration(seconds: 5))
            .catchError((_) {})
            .whenComplete(() =>
                im.logout(ifGeneration: logoutGeneration).catchError((_) {}))
            .whenComplete(() => push.syncRegistration(isVet: next.isVet));
      } else if (becameAuthed) {
        ref.read(pushServiceProvider).syncRegistration(isVet: next.isVet);
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
          // 全局「点非输入区收起键盘」：translucent 使按钮/输入框仍在手势竞技场胜出正常响应，
          // 仅点到非交互空白处才触发 unfocus 收起软键盘（键盘避让标准的补充，见 CLAUDE.md）。
          // 埋点治理 P0（2026-07-27）：已移除全局 AnalyticsAutocapture——从 semantics 树反推
          // 按钮文本会把用户内容（昵称/宠物名/健康主诉）当标签上报。按钮上报一律走
          // Analytics.buttonTapped(ButtonId.*) 白名单显式埋点。
          child: GestureDetector(
            behavior: HitTestBehavior.translucent,
            onTap: () => FocusManager.instance.primaryFocus?.unfocus(),
            child: child ?? const SizedBox.shrink(),
          ),
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
  // bug 20260730-421 同类隐患：健康记录/日历/日详情是宠物维度缓存（非 autoDispose），
  // 不登记则同设备换账号会看到上一用户的健康数据（隐私）。
  ref.invalidate(healthListProvider); // 健康记录页
  ref.invalidate(calendarMonthProvider); // 成长日记：日历（按月 family 整族失效）
  ref.invalidate(dayDetailProvider); // 成长日记：日详情（按日 family 整族失效）
  ref.invalidate(myPostsProvider); // 我的：我的发布
  ref.invalidate(feedProvider); // 首页 Feed（按新用户宠物状态重过滤）
  ref.invalidate(unreadCountProvider); // 通知铃铛未读角标（bug 20260625-088：换账号防显示上个用户角标）
  // bug 20260731-446：宠物身份证是用户维度缓存（列表/单卡/详情 family），不登记则同设备
  // 换账号会看到上一账号（含已删档案）的历史卡片（隐私泄漏，同 421/上面健康记录同型）。
  ref.invalidate(idCardProvider); // 身份证：单卡（旧版入口）
  ref.invalidate(idCardListProvider); // 身份证：多卡列表
  ref.invalidate(idCardDetailProvider); // 身份证：卡详情（按 cardId family 整族失效）
  ref.invalidate(newbieTasksProvider); // 新手任务进度（同型隐患：换账号防串任务状态）
  ref.invalidate(pawCoinProvider); // PawCoin 余额（同型隐患：换账号防显示上个账号余额）
  ref.read(consultRefreshProvider.notifier).bump(); // 问诊页 _active/_history 重拉
}
