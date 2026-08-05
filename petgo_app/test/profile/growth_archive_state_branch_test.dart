import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/app_shell.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// Story 2.1 · L0：Diary 页状态分支单一入口（AD-15）。
///
/// 三件事：
/// 1. [resolveDiaryUserState] 四态互斥且穷尽（纯函数，穷举输入组合）；
/// 2. 四个分支各渲染到正确目的地（widget test）；
/// 3. 门控未解的回归护栏 —— 本 Story 不得为自测提前放行游客（AC3）。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

const AuthState _guest = AuthState.guest();

AuthState _authWith({required String? petStatus}) => AuthState(
      status: AuthStatus.authenticated,
      role: 'USER',
      profile: UserProfile(petStatus: petStatus),
    );

/// 档案拉取次数计数器：游客分支必须为 0（游客无令牌，拉档案会打 401 → 弹全局强登录窗）。
Widget _wrap({
  required AuthState auth,
  PetProfile? profile,
  void Function()? onProfileFetch,
}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      petProfileProvider.overrideWith((ref) async {
        onProfileFetch?.call();
        return profile;
      }),
      timelineFirstPageProvider.overrideWith((ref) async => const TimelinePage(items: [])),
      archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
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
  group('AC1 判定入口：四态互斥且穷尽', () {
    test('未登录 → 游客态（petStatus / 档案信号一概不影响）', () {
      for (final petStatus in <String?>[null, 'HAS_PET', 'PLANNING', 'ENTHUSIAST']) {
        for (final hasProfile in <bool>[true, false]) {
          expect(
            resolveDiaryUserState(
                isLoggedIn: false, petStatus: petStatus, hasPetProfile: hasProfile),
            DiaryUserState.guest,
            reason: 'petStatus=$petStatus hasPetProfile=$hasProfile 时游客判定应短路',
          );
        }
      }
    });

    test('已登录 + PLANNING / ENTHUSIAST → 非有宠态（不引导建档，UX-DR6）', () {
      for (final petStatus in <String>['PLANNING', 'ENTHUSIAST']) {
        for (final hasProfile in <bool>[true, false]) {
          expect(
            resolveDiaryUserState(
                isLoggedIn: true, petStatus: petStatus, hasPetProfile: hasProfile),
            DiaryUserState.nonOwner,
          );
        }
      }
    });

    test('已登录 + HAS_PET → 按有无档案二分', () {
      expect(
        resolveDiaryUserState(isLoggedIn: true, petStatus: 'HAS_PET', hasPetProfile: true),
        DiaryUserState.ownerWithProfile,
      );
      expect(
        resolveDiaryUserState(isLoggedIn: true, petStatus: 'HAS_PET', hasPetProfile: false),
        DiaryUserState.ownerWithoutProfile,
      );
    });

    test('已登录 + petStatus 未知（profile 未回填）→ 按 HAS_PET 走，不落空分支', () {
      expect(
        resolveDiaryUserState(isLoggedIn: true, petStatus: null, hasPetProfile: true),
        DiaryUserState.ownerWithProfile,
      );
      expect(
        resolveDiaryUserState(isLoggedIn: true, petStatus: null, hasPetProfile: false),
        DiaryUserState.ownerWithoutProfile,
      );
    });

    test('穷尽性：枚举恰好四态（新增态会让分发 switch 编译期报错）', () {
      expect(DiaryUserState.values, <DiaryUserState>[
        DiaryUserState.guest,
        DiaryUserState.nonOwner,
        DiaryUserState.ownerWithoutProfile,
        DiaryUserState.ownerWithProfile,
      ]);
    });
  });

  group('AC1/AC2 四分支布线', () {
    testWidgets('游客 → 渲染 FR-80 游客引导态，且不拉取宠物档案', (tester) async {
      var fetches = 0;
      await tester.pumpWidget(_wrap(auth: _guest, onProfileFetch: () => fetches++));
      await tester.pumpAndSettle();

      // Story 2.2 起该分支渲染真实游客引导态（此前是空占位）。
      expect(find.byType(DiaryGuestPage), findsOneWidget);
      // 游客路径不得订阅档案：否则 401 → 全局强登录引导（2.4 放行后会当场暴露）。
      expect(fetches, 0);
      // 未误落其它分支（游客页自带示例宠物信息卡，故不以 petInfoCard 作区分依据）
      expect(find.byKey(const ValueKey('growthCreateButton')), findsNothing);
      expect(find.byKey(const ValueKey('changeStatusButton')), findsNothing);
    });

    testWidgets('状态 A 未建档 → 建档引导占位（既有空状态，零回归）', (tester) async {
      await tester.pumpWidget(_wrap(auth: _authWith(petStatus: 'HAS_PET'), profile: null));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('growthCreateButton')), findsOneWidget);
      // 删档后不被困在状态 A（bug 20260702-237）
      expect(find.byKey(const ValueKey('growthChangeStatusButton')), findsOneWidget);
      expect(find.byType(DiaryGuestPage), findsNothing);
      expect(find.byKey(const ValueKey('petInfoCard')), findsNothing);
    });

    testWidgets('状态 A 已建档 → 现有真实档案页（零改动）', (tester) async {
      const profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T', breed: 'Shiba');
      await tester.pumpWidget(_wrap(auth: _authWith(petStatus: 'HAS_PET'), profile: profile));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('shareFab')), findsOneWidget);
      expect(find.byType(DiaryGuestPage), findsNothing);
      expect(find.byKey(const ValueKey('growthCreateButton')), findsNothing);
    });

    testWidgets('状态 B/C → 既有「有宠专属」页，不引导建档（UX-DR6 回归基准）', (tester) async {
      await tester.pumpWidget(_wrap(auth: _authWith(petStatus: 'PLANNING')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('changeStatusButton')), findsOneWidget);
      // 不得出现建档引导 / 档案页 / 游客占位
      expect(find.byKey(const ValueKey('growthCreateButton')), findsNothing);
      expect(find.byKey(const ValueKey('petInfoCard')), findsNothing);
      expect(find.byType(DiaryGuestPage), findsNothing);
    });
  });

  group('AC3 门控（Story 2.4 起已放行 Diary 主页）', () {
    // 本组原为「门控未解」的零回归断言（2.1 交付时游客进不来）。Story 2.4 正式放行 Diary 主页后
    // 三条断言全部到期，改由 test/shared/diary_gating_and_landing_test.dart 双向守门
    // （拦截方向：/profile/* 子页仍受控；放行方向：/profile 与示例详情对游客可达）。
    // 这里只保留一条最小连接断言，确认本页的游客分支确实对游客可达。
    test('Diary 主页已在免门控 Tab 白名单里，Health / Me 仍受控', () {
      expect(kUngatedTabs.contains(AppTab.profile), isTrue);
      expect(kUngatedTabs.contains(AppTab.triage), isFalse);
      expect(kUngatedTabs.contains(AppTab.me), isFalse);
    });
  });
}
