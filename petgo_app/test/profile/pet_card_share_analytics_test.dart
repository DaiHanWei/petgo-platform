import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 10.1 · 埋点 E-23 `pet_card_share_tapped`（FR-92 那条漏斗的**起点**）。
///
/// 「宠物名片分享 → 下载转化率」是历史标记的最高优先级数据缺口，此前零埋点。
/// E-24 ÷ E-23 才算得出「分享出去到底有没有人点」 —— 缺了 E-23 就只有分子。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

void main() {
  late List<(String, Map<String, Object>?)> seen;

  setUp(() {
    seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Map<String, Object>? propsOf(String event) =>
      seen.where((e) => e.$1 == event).map((e) => e.$2).firstOrNull;

  /// [milestoneCompleted] 是本文件的主角 —— 见下面 `has_milestone` 那两条用例。
  Widget app({required int milestoneCompleted}) {
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (_, _) => const GrowthArchivePage()),
        for (final p in const [
          '/content/:id',
          '/profile/health',
          '/profile/milestones',
          '/profile/id-card'
        ])
          GoRoute(path: p, builder: (_, _) => const Scaffold(body: Text('stub'))),
      ],
    );
    return ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(
              AuthState(
                status: AuthStatus.authenticated,
                role: 'USER',
                profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
              ),
            )),
        petProfileProvider.overrideWith((ref) async => PetProfile(
              id: 7,
              name: 'Mochi',
              // 🔴 必须非空：`cardToken` 为空时分享按钮压根不渲染（老档案可能没有）。
              cardToken: 'TOKEN',
              petType: 'CAT',
              birthday: DateTime(2025, 1, 1),
            )),
        timelineFirstPageProvider.overrideWith((ref) async => const TimelinePage(items: [])),
        archiveStatsProvider.overrideWith((ref) async => ArchiveStats(
              happyMomentCount: 1,
              consultCount: 0,
              milestoneCompleted: milestoneCompleted,
              milestoneTotal: 30,
            )),
        shareFabAnimatedShownProvider.overrideWith((ref) async => true),
        // 系统分享面板在 widget test 里没有平台实现 —— 桩掉，本文件只关心埋点。
        shareServiceProvider.overrideWithValue((text, {Rect? sharePositionOrigin}) async {}),
        calendarMonthProvider.overrideWith((ref, ym) async =>
            CalendarMonth(year: ym.year, month: ym.month, days: const [])),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    );
  }

  Future<void> tapShare(WidgetTester tester, {required int milestoneCompleted}) async {
    await tester.binding.setSurfaceSize(const Size(500, 2400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(app(milestoneCompleted: milestoneCompleted));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('shareFab')));
    await tester.pumpAndSettle();
  }

  testWidgets('点分享 → pet_card_share_tapped(entry)', (tester) async {
    await tapShare(tester, milestoneCompleted: 3);
    expect(propsOf('pet_card_share_tapped')?['entry'], 'archive_header',
        reason: '`entry` 的取值以实现为准（清单 §3），当前只有页头标题行这一个入口');
  });

  /// 🔴 **这两条是本文件存在的理由。**
  /// 建档动作本身就会自动完成一条里程碑（C-S1「Profil dibuat」），所以任何档案
  /// 的已完成数都 ≥ 1 —— 按「> 0」判 `has_milestone` 会让这个属性**恒为 true**、
  /// 在看板上完全看不出来它已经废了。
  /// 1-2 在 H5 的 `page_state` 上正是踩过同一个坑（见 CardPageAnalytics 的注释）。
  testWidgets('只有建档那一条（=1）→ has_milestone=false', (tester) async {
    await tapShare(tester, milestoneCompleted: 1);
    expect(propsOf('pet_card_share_tapped')?['has_milestone'], false);
  });

  testWidgets('除建档之外还有（=2）→ has_milestone=true', (tester) async {
    await tapShare(tester, milestoneCompleted: 2);
    expect(propsOf('pet_card_share_tapped')?['has_milestone'], true);
  });
}
