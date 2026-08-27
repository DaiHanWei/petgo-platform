import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/presentation/widgets/repurchase_zones.dart';
import 'package:tailtopia/features/shop/presentation/widgets/repurchase_zones_v2.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

AuthState _authA() => const AuthState(
      status: AuthStatus.authenticated,
      profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
    );

AuthState _authB() => const AuthState(
      status: AuthStatus.authenticated,
      profile: UserProfile(petStatus: 'PLANNING'),
    );

Widget _wrap({required AuthState auth, PetProfile? profile, TimelinePage? page, ArchiveStats? stats}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      petProfileProvider.overrideWith((ref) async => profile),
      timelineFirstPageProvider.overrideWith(
        (ref) async => page ?? const TimelinePage(items: []),
      ),
      archiveStatsProvider.overrideWith(
        (ref) async =>
            stats ??
            const ArchiveStats(
                happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30),
      ),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GrowthArchivePage(),
    ),
  );
}

void main() {
  /// 🔴 **档案推荐区不得出现在 Diary**（产品 2026-08-27）。
  ///
  /// Story 6.5 原本给它两个展示位：Toko 首页区域② 与本页。实机上本页那一份的问题是
  /// 大多数用户处在**降级态**（没有体重/喂养记录 ⇒ 推不出个性化结果），
  /// 于是 Diary 中段被一屏按年龄段的通用商品占掉 —— 成长记录页被商业内容打断，
  /// 而那一屏并不提供"推荐"的价值。产品决定商业内容全部收到 Toko。
  ///
  /// ⚠️ 钉的是**展示位**，不是组件：`ProfileRecoZone(V2)` 在 Toko 侧照常使用，
  /// 它自己的用例（test/shop/repurchase_zones*_test.dart）一条都没动。
  /// 这一条只保证「它别再长回 Diary 上」—— 而那种回归通常是合并时顺手带回来的，
  /// 不会有人特意去看这一屏。
  testWidgets('有宠态 Diary 不渲染商品推荐区（2026-08-27 撤下）', (tester) async {
    const profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T');
    await tester.pumpWidget(_wrap(auth: _authA(), profile: profile));
    await tester.pumpAndSettle();

    expect(find.byType(ProfileRecoZone), findsNothing,
        reason: '🔴 商品推荐区又长回 Diary 了');
    expect(find.byType(ProfileRecoZoneV2), findsNothing,
        reason: '🔴 v2 版式下的商品推荐区又长回 Diary 了');
  });


  testWidgets('状态 B/C → 非有宠态 + 修改状态入口（AC3）', (tester) async {
    await tester.pumpWidget(_wrap(auth: _authB()));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('changeStatusButton')), findsOneWidget);
    expect(find.byKey(const ValueKey('petInfoCard')), findsNothing);
    // 2.7 AC3：B/C 无名片可分享 → 无分享 FAB
    expect(find.byKey(const ValueKey('shareFab')), findsNothing);
  });

  testWidgets('状态 A 无档案 → 空状态「立即创建」（AC2）', (tester) async {
    await tester.pumpWidget(_wrap(auth: _authA(), profile: null));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('growthCreateButton')), findsOneWidget);
    expect(find.byKey(const ValueKey('petInfoCard')), findsNothing);
  });

  testWidgets('状态 A 有档案 → 信息卡 + 空时间线（AC1）', (tester) async {
    const profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T', breed: 'Shiba');
    await tester.pumpWidget(_wrap(auth: _authA(), profile: profile));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
    expect(find.text('Momo'), findsOneWidget);
    // 2.7 AC1：A + 有档案 → 渲染分享 FAB
    expect(find.byKey(const ValueKey('shareFab')), findsOneWidget);
  });

  testWidgets('有档案 + 含健康事件条目 → 渲染健康事件样式（AC1 前向兼容 J4）', (tester) async {
    const profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T');
    final page = TimelinePage(items: [
      TimelineItem(
        kind: TimelineKind.healthEvent,
        date: DateTime.parse('2026-06-03T10:00:00Z'),
        aiLevel: 'YELLOW',
        symptomSummary: '咳嗽',
      ),
    ]);
    await tester.pumpWidget(_wrap(auth: _authA(), profile: profile, page: page));
    await tester.pumpAndSettle();
    // Story 3.3：健康/问诊条渲染改走共用组件（④a 粉底条 key = timelineConsultRow）。
    expect(find.byKey(const ValueKey('timelineConsultRow')), findsOneWidget);
    // paspor.html 重做：等级徽章用 emoji + l10n 标签（默认英文 → 🟡 Yellow），不再裸露 enum 值。
    expect(find.text('🟡 Yellow'), findsOneWidget);
  });
}
