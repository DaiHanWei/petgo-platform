import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_avatar_item.dart';
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
      timelineFirstPageProvider.overrideWith((ref, _) async => const TimelinePage(items: [])),
      archiveStatsProvider.overrideWith((ref, _) async => const ArchiveStats(
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

    test('穷尽性：枚举恰好五态（新增态会让分发 switch 编译期报错）', () {
      // V1.1.6 Story 2.3 加了 visitor（AD-4：访客态作为 AD-15 单一判定入口的新增分支）。
      expect(DiaryUserState.values, <DiaryUserState>[
        DiaryUserState.guest,
        DiaryUserState.nonOwner,
        DiaryUserState.ownerWithoutProfile,
        DiaryUserState.ownerWithProfile,
        DiaryUserState.visitor,
      ]);
    });

    test('🛡 访客判据独占：有 token 时，登录态与自己的档案一概不影响判定', () {
      // AD-2 Rule 2：已登录非作者与未登录访客看到**同一套**只读视图。
      // 这条把「同一套」钉死 —— 四种身份组合，只要带 token，一律 visitor。
      for (final loggedIn in [true, false]) {
        for (final hasProfile in [true, false]) {
          expect(
            resolveDiaryUserState(
              isLoggedIn: loggedIn,
              petStatus: hasProfile ? 'HAS_PET' : 'PLANNING',
              hasPetProfile: hasProfile,
              visitorToken: 'TOK',
            ),
            DiaryUserState.visitor,
            reason: '带 token 时判定必须恒为 visitor'
                '（isLoggedIn=$loggedIn hasPetProfile=$hasProfile）',
          );
        }
      }
    });

    test('无 token 时判定完全不受影响（访客分支不得污染既有四态）', () {
      // 空串也算没有 —— 路由参数缺失时拿到的是空串而不是 null，
      // 漏判会让作者莫名其妙落进只读态。
      for (final token in [null, '']) {
        expect(
          resolveDiaryUserState(
            isLoggedIn: true,
            petStatus: 'HAS_PET',
            hasPetProfile: true,
            visitorToken: token,
          ),
          DiaryUserState.ownerWithProfile,
        );
      }
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
    test('Diary 主页已在免门控 Tab 白名单里，Me 仍受控', () {
      expect(kUngatedTabs.contains(AppTab.profile), isTrue);
      // DEP-1 闭合后原 Health 位换成 Toko 并一并放行；受控侧的最小守门收敛到 Me。
      expect(kUngatedTabs.contains(AppTab.me), isFalse);
    });
  });

  // ===== V1.4.0 multi-pet Story 1.5 · AC3（UI 稿 B1 ~ B6）=====
  group('V1.4.0 multi-pet：Diary Tab 宠物切换', () {
    const pets = [
      PetListItem(id: 1, name: 'Orenji', isDefault: true),
      PetListItem(id: 2, name: 'Kopi'),
    ];
    const orenji = PetProfile(id: 1, name: 'Orenji', cardToken: 'T1');
    const kopi = PetProfile(id: 2, name: 'Kopi', cardToken: 'T2');

    late _MultiPetRepo repo;
    late Map<int, int> timelineFetches;
    late Set<int> failingTimeline;

    setUp(() {
      SharedPreferences.setMockInitialValues({});
      repo = _MultiPetRepo({1: orenji, 2: kopi});
      timelineFetches = {};
      failingTimeline = {};
    });

    Future<ProviderContainer> pump(WidgetTester tester,
        {List<PetListItem> list = pets, Map<int, int>? giftValues, List<String>? pushed}) async {
      tester.view.physicalSize = const Size(1080, 3200);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.reset);
      final c = ProviderContainer(overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(_authWith(petStatus: 'HAS_PET'))),
        // 不桩 petProfileProvider：走真实派生层（effectivePetIdProvider → getMyProfile(petId:)）。
        profileRepositoryProvider.overrideWithValue(repo),
        petListProvider.overrideWith((ref) async => list),
        timelineFirstPageProvider.overrideWith((ref, petId) async {
          timelineFetches[petId] = (timelineFetches[petId] ?? 0) + 1;
          if (failingTimeline.contains(petId)) throw StateError('offline');
          return const TimelinePage(items: []);
        }),
        archiveStatsProvider.overrideWith((ref, petId) async => ArchiveStats(
            happyMomentCount: 1,
            consultCount: 0,
            milestoneCompleted: 0,
            milestoneTotal: 30,
            giftTotalValue: giftValues?[petId] ?? 0)),
        shareFabAnimatedShownProvider.overrideWith((ref) async => true),
      ]);
      addTearDown(c.dispose);
      final router = pushed == null
          ? null
          : GoRouter(routes: [
              GoRoute(path: '/', builder: (_, _) => const GrowthArchivePage()),
              GoRoute(
                path: '/pets/:petId/gifts',
                builder: (_, s) {
                  pushed.add(s.uri.toString());
                  return const Scaffold(body: Text('gift page'));
                },
              ),
            ]);
      if (router != null) addTearDown(router.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: router == null
            ? const MaterialApp(
                locale: Locale('en'),
                localizationsDelegates: AppLocalizations.localizationsDelegates,
                supportedLocales: AppLocalizations.supportedLocales,
                home: GrowthArchivePage(),
              )
            : MaterialApp.router(
                routerConfig: router,
                locale: const Locale('en'),
                localizationsDelegates: AppLocalizations.localizationsDelegates,
                supportedLocales: AppLocalizations.supportedLocales,
              ),
      ));
      await tester.pumpAndSettle();
      return c;
    }

    Finder title(String name) => find.text("$name's Diary");

    testWidgets('B1：切换器在页面最顶部、标题行之上；默认宠物高亮', (tester) async {
      await pump(tester);

      final switcher = find.byKey(const ValueKey('petSwitcher'));
      expect(switcher, findsOneWidget);
      expect(title('Orenji'), findsOneWidget);
      expect(tester.getRect(switcher).bottom, lessThanOrEqualTo(tester.getRect(title('Orenji')).top));
      expect(tester.widget<PetAvatarItem>(find.byKey(const ValueKey('petSwitcherItem-1'))).selected, isTrue);
    });

    testWidgets('B2：点另一只 → 立即高亮 + 标题行立即换名 + 数据区骨架；切换器仍可点', (tester) async {
      final c = await pump(tester);
      repo.hold(2); // Kopi 的档案请求挂起，停在「切换中」

      await tester.tap(find.byKey(const ValueKey('petSwitcherItem-2')));
      await tester.pump();
      await tester.pump();

      expect(c.read(currentPetIdProvider), 2);
      expect(find.byKey(const ValueKey('diarySwitching')), findsOneWidget);
      expect(title('Kopi'), findsOneWidget, reason: '名字取列表项，不等档案请求');
      expect(find.byKey(const ValueKey('diarySwitchingSpinner')), findsOneWidget);
      expect(tester.widget<PetAvatarItem>(find.byKey(const ValueKey('petSwitcherItem-2'))).selected, isTrue);
      // 不是整页居中转圈：切换器还在、可点。
      expect(find.byKey(const ValueKey('petSwitcher')), findsOneWidget);

      // 切换中再点回 Orenji：切换器照常响应。
      await tester.tap(find.byKey(const ValueKey('petSwitcherItem-1')));
      await tester.pumpAndSettle();
      expect(c.read(currentPetIdProvider), 1);
      expect(title('Orenji'), findsOneWidget);

      // 再切到 Kopi 并放行请求 → 落到 Kopi 的档案页。
      await tester.tap(find.byKey(const ValueKey('petSwitcherItem-2')));
      await tester.pump();
      repo.release(2);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('diarySwitching')), findsNothing);
      expect(title('Kopi'), findsOneWidget);
      expect(timelineFetches[2], 1, reason: '时间线按 petId 分族取当前宠物');
    });

    testWidgets('B3：时间线失败 → 失败白卡；重试只重取当前宠物这一页', (tester) async {
      failingTimeline.add(1);
      await pump(tester);

      expect(find.byKey(const ValueKey('timelineError')), findsOneWidget);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.growthLoadFailed), findsOneWidget);
      expect(find.text(l10n.growthLoadRetry), findsOneWidget);
      final before1 = timelineFetches[1]!;

      failingTimeline.clear();
      await tester.tap(find.byKey(const ValueKey('timelineRetry')));
      await tester.pumpAndSettle();

      expect(timelineFetches[1], greaterThan(before1));
      expect(timelineFetches[2], isNull, reason: '不连带别的宠物');
      expect(find.byKey(const ValueKey('timelineError')), findsNothing);
    });

    // V1.4.0 gift Story 4.1 · AC6.1 / AC6.2：礼物格取当前宠物的统计；切换后随之变，点进的是当前宠物的礼物页。
    testWidgets('gift 4.1：切换 A → B 礼物格数值随之变、点礼物格进 B 的礼物页', (tester) async {
      final pushed = <String>[];
      await pump(tester, giftValues: const {1: 53000, 2: 5000}, pushed: pushed);
      String giftText() =>
          tester.widget<Text>(find.byKey(const ValueKey('petInfoGiftValue'))).textSpan!.toPlainText();
      expect(giftText(), 'Rp53rb');

      await tester.tap(find.byKey(const ValueKey('petSwitcherItem-2')));
      await tester.pumpAndSettle();
      expect(title('Kopi'), findsOneWidget);
      expect(giftText(), 'Rp5rb');

      await tester.tap(find.byKey(const ValueKey('petInfoGiftStat')));
      await tester.pumpAndSettle();
      expect(pushed, ['/pets/2/gifts']);
    });

    testWidgets('B5：只有 1 只 → 切换器 = 1 个头像 +「+」', (tester) async {
      await pump(tester, list: const [PetListItem(id: 1, name: 'Orenji', isDefault: true)]);
      expect(find.byKey(const ValueKey('petSwitcher')), findsOneWidget);
      expect(find.byKey(const ValueKey('petSwitcherItem-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('petSwitcherAdd')), findsOneWidget);
    });

    testWidgets('B6：没有宠物 → 现有空态，不渲染切换器', (tester) async {
      await pump(tester, list: const []);
      expect(find.byKey(const ValueKey('petSwitcher')), findsNothing);
      expect(find.byKey(const ValueKey('growthCreateButton')), findsOneWidget);
    });

    testWidgets('记住上次查看：偏好里是 Kopi → 进来就停在 Kopi', (tester) async {
      SharedPreferences.setMockInitialValues({CurrentPetIdNotifier.prefKey: 2});
      await pump(tester);
      expect(title('Kopi'), findsOneWidget);
      expect(tester.widget<PetAvatarItem>(find.byKey(const ValueKey('petSwitcherItem-2'))).selected, isTrue);
    });

    testWidgets('code review #4：换账号 → 重取期间不渲染上个账号的宠物，也不拿它的 petId 取数', (tester) async {
      tester.view.physicalSize = const Size(1080, 3200);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.reset);
      const mochi = PetProfile(id: 3, name: 'Mochi', cardToken: 'T3');
      final repoAB = _MultiPetRepo({1: orenji, 2: kopi, 3: mochi});
      final gateB = Completer<void>();
      final c = ProviderContainer(overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(const AuthState(
            status: AuthStatus.authenticated,
            role: 'USER',
            profile: UserProfile(id: 1, petStatus: 'HAS_PET')))),
        profileRepositoryProvider.overrideWithValue(repoAB),
        // 与真实 provider 同样跟随当前账号（依赖变化 → reload）。
        petListProvider.overrideWith((ref) async {
          final uid = ref.watch(authControllerProvider.select((s) => s.profile?.id));
          if (uid == 2) {
            await gateB.future;
            return const [PetListItem(id: 3, name: 'Mochi', isDefault: true)];
          }
          return pets;
        }),
        timelineFirstPageProvider.overrideWith((ref, petId) async {
          timelineFetches[petId] = (timelineFetches[petId] ?? 0) + 1;
          return const TimelinePage(items: []);
        }),
        archiveStatsProvider.overrideWith((ref, _) async => const ArchiveStats(
            happyMomentCount: 1, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
        shareFabAnimatedShownProvider.overrideWith((ref) async => true),
      ]);
      addTearDown(c.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: const MaterialApp(
          locale: Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: GrowthArchivePage(),
        ),
      ));
      await tester.pumpAndSettle();
      expect(title('Orenji'), findsOneWidget);
      final fetchesBefore = Map<int, int>.of(timelineFetches);

      // 换到账号 B（列表还没回来）。
      c.read(authControllerProvider.notifier)
          .applyProfile(const UserProfile(id: 2, petStatus: 'HAS_PET'));
      await tester.pump();
      await tester.pump();

      expect(find.byKey(const ValueKey('diaryAccountSwitching')), findsOneWidget);
      expect(title('Orenji'), findsNothing, reason: '不得渲染上个账号的宠物');
      expect(find.byKey(const ValueKey('petSwitcher')), findsNothing);
      expect(timelineFetches, fetchesBefore, reason: '换号重取期间不发按宠物的请求');

      gateB.complete();
      await tester.pumpAndSettle();
      expect(title('Mochi'), findsOneWidget);
      expect(title('Orenji'), findsNothing);
    });

    testWidgets('记住的那只已删除 → 回落默认宠物', (tester) async {
      SharedPreferences.setMockInitialValues({CurrentPetIdNotifier.prefKey: 99});
      await pump(tester);
      expect(title('Orenji'), findsOneWidget);
    });

    // ===== V1.4.0 multi-pet Story 2.4 · AC5.2（UI 稿 B4）=====
    testWidgets('B4：当前宠物只读 → 页头前插只读横幅、编辑铅笔隐藏、上报 pet_readonly_banner_shown（无宠物名）',
        (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      await pump(tester, list: const [
        PetListItem(id: 1, name: 'Orenji', isDefault: true, status: PetListItem.statusReadOnly),
        PetListItem(id: 2, name: 'Kopi'),
      ]);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      final banner = find.byKey(const ValueKey('petReadonlyBanner'));
      expect(banner, findsOneWidget);
      expect(find.text(l10n.petReadonlyBannerTitle), findsOneWidget);
      expect(find.text(l10n.petReadonlyBannerBody), findsOneWidget);
      expect(find.byKey(const ValueKey('petReadonlyBannerRenew')), findsOneWidget);
      expect(tester.getRect(banner).bottom, lessThanOrEqualTo(tester.getRect(title('Orenji')).top),
          reason: '横幅在 DiaryHeader 之前');
      expect(find.byKey(const ValueKey('editProfileButton')), findsNothing);
      final shown = events.where((e) => e.$1 == 'pet_readonly_banner_shown').toList();
      expect(shown, hasLength(1));
      expect(shown.single.$2?.values.contains('Orenji') ?? false, isFalse);
    });

    testWidgets('B4：正常宠物零变化（无横幅、铅笔在）', (tester) async {
      await pump(tester);
      expect(find.byKey(const ValueKey('petReadonlyBanner')), findsNothing);
      expect(find.byKey(const ValueKey('editProfileButton')), findsOneWidget);
    });
  });
}

/// 按 petId 返回档案；[hold] 的宠物请求挂起到 [release]。
class _MultiPetRepo implements ProfileRepository {
  _MultiPetRepo(this.byId);

  final Map<int, PetProfile> byId;
  final Map<int, Completer<void>> _gates = {};

  void hold(int petId) => _gates[petId] = Completer<void>();
  void release(int petId) => _gates.remove(petId)?.complete();

  @override
  Future<PetProfile?> getMyProfile({int? petId}) async {
    final gate = _gates[petId];
    if (gate != null) await gate.future;
    return byId[petId] ?? byId.values.first;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
