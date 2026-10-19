import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/me/data/me_counts_provider.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/me/presentation/me_page.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_avatar_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../collect/collect_fakes.dart';

/// 单只 [PetProfile] → 列表项（diaryCount 12 = 原统计栏桩数）。
List<PetListItem> _listOf(PetProfile? pet) => [
      if (pet != null)
        PetListItem(
          id: pet.id,
          name: pet.name,
          avatarUrl: pet.avatarUrl,
          petType: pet.petType,
          birthday: pet.birthday,
          tailsonalityBadge: pet.tailsonalityBadge,
          isDefault: true,
          diaryCount: 12,
        ),
    ];

/// Story 7.1 · F8：信息架构重组——顶栏双图标 + 主体「人+宠物」 + AC5 宠物区位三态。
Future<void> _pump(
  WidgetTester tester, {
  required UserProfile profile,
  PetProfile? pet,
  List<MyPost> posts = const [],
  MeCounts? counts = const MeCounts(postCount: 15, likeCount: 3, followingCount: 50, followerCount: 30),
  Object? countsError,
  Completer<MeCounts>? countsPending,
  List<PetListItem>? pets,
}) async {
  tester.view.physicalSize = const Size(1200, 3200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final container = ProviderContainer(overrides: [
    myPostsProvider.overrideWith((ref) async => posts),
    // V1.3.4 Story 1.4：四格数据源（`GET /me`）。countsError 非空时模拟失败 → 回落 auth.profile。
    meCountsProvider.overrideWith((ref, userId) async {
      if (countsError != null) throw countsError;
      if (countsPending != null) return countsPending.future;
      return counts!;
    }),
    // V1.4.0 multi-pet（Story 1.5）：Hewanku 改读宠物列表；diary 角标取列表项的 diaryCount
    // （原先取统计栏 happyMomentCount = 12，这里给同一个数，断言不变）。测试不打真实后端。
    petListProvider.overrideWith((ref) async => pets ?? _listOf(pet)),
  ]);
  addTearDown(container.dispose);
  container.read(authControllerProvider.notifier).applyProfile(profile);

  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: MePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

/// V1.4.0 ① Story 1.5：带路由的版本（胶囊点击要 push `/me/growth`）。
Future<void> _pumpRouted(WidgetTester tester,
    {required UserProfile profile, MeCounts counts = const MeCounts(), bool countsFail = false}) async {
  tester.view.physicalSize = const Size(1200, 3200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(overrides: [
    myPostsProvider.overrideWith((ref) async => const <MyPost>[]),
    meCountsProvider.overrideWith((ref, userId) async {
      if (countsFail) throw StateError('me down');
      return counts;
    }),
    // V1.4.0 multi-pet（Story 1.5）：宠物区改读宠物列表（原 petProfileProvider / archiveStatsProvider 桩，合并后换成它）。
    petListProvider.overrideWith((ref) async => const <PetListItem>[]),
  ]);
  addTearDown(container.dispose);
  container.read(authControllerProvider.notifier).applyProfile(profile);
  final router = GoRouter(initialLocation: '/me', routes: [
    GoRoute(path: '/me', builder: (c, s) => const MePage()),
    GoRoute(path: '/me/growth', builder: (c, s) => const Text('LEVEL_PAGE')),
  ]);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  group('V1.4.0 ① Story 1.5 · AC1 等级胶囊', () {
    const brief = GrowthBrief(level: 7, tier: GrowthTier.buddy, frame: 'BASIC', lifetimeTotal: 1420, nextThreshold: 1800);

    testWidgets('有 growth → 昵称下显示胶囊（Lv7 Sahabat），相机角标仍在', (tester) async {
      await _pumpRouted(tester,
          profile: const UserProfile(id: 1, nickname: '小明'), counts: const MeCounts(postCount: 1, growth: brief));

      final capsule = find.byKey(const ValueKey('meLevelCapsule'));
      expect(capsule, findsOneWidget);
      expect(find.descendant(of: capsule, matching: find.text('Lv7')), findsOneWidget);
      expect(find.descendant(of: capsule, matching: find.text('Sahabat')), findsOneWidget);
      expect(find.byIcon(Icons.photo_camera), findsOneWidget);
      expect(find.bySemanticsLabel('Lv7 Sahabat'), findsOneWidget);
    });

    testWidgets('点胶囊 → 进等级页并上报 growth_level_page_viewed {source: me_page}', (tester) async {
      final captured = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      await _pumpRouted(tester, profile: const UserProfile(id: 1, nickname: '小明'), counts: const MeCounts(growth: brief));

      await tester.tap(find.byKey(const ValueKey('meLevelCapsule')));
      await tester.pumpAndSettle();

      expect(find.text('LEVEL_PAGE'), findsOneWidget);
      expect(captured.where((c) => c.$1 == 'growth_level_page_viewed'), hasLength(1));
      expect(captured.firstWhere((c) => c.$1 == 'growth_level_page_viewed').$2, {'source': 'me_page'});
    });

    testWidgets('/me 已加载且 growth 为 null（不参与）→ 不用登录态里的旧 growth', (tester) async {
      await _pumpRouted(tester, profile: const UserProfile(id: 1, nickname: '小明', growth: brief));

      expect(find.byKey(const ValueKey('meLevelCapsule')), findsNothing);
    });

    testWidgets('/me 还没拿到（失败）→ 回落登录态里的 growth', (tester) async {
      await _pumpRouted(tester, profile: const UserProfile(id: 1, nickname: '小明', growth: brief), countsFail: true);

      expect(find.byKey(const ValueKey('meLevelCapsule')), findsOneWidget);
    });

    testWidgets('growth 为 null → 不显示胶囊、不闪 Lv1', (tester) async {
      await _pumpRouted(tester, profile: const UserProfile(id: 1, nickname: '小明'));

      expect(find.byKey(const ValueKey('meLevelCapsule')), findsNothing);
      expect(find.text('Lv1'), findsNothing);
      expect(find.byIcon(Icons.photo_camera), findsOneWidget);
    });
  });

  testWidgets('AC1: 顶栏双图标 + 主体人+宠物 + 主体不再平铺语言/退出/注销', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
    );
    // 顶栏右上双图标（帮助 + 设置）
    expect(find.byKey(const ValueKey('meHelp')), findsOneWidget);
    expect(find.byKey(const ValueKey('meSettings')), findsOneWidget);
    // 主体：用户信息 / 宠物 mini / 我的发布（宠物状态区块已移除，对齐原型 p-profil）
    expect(find.text('小明'), findsOneWidget);
    expect(find.byKey(const ValueKey('meEditNickname')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetStatus')), findsNothing);
    expect(find.byKey(const ValueKey('meEditPetProfile')), findsNothing);
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget);
    // 🔄 F8：语言/退出/注销不再在主页平铺（已挪入二级设置页）
    expect(find.byKey(const ValueKey('meLanguage')), findsNothing);
    expect(find.byKey(const ValueKey('meLogout')), findsNothing);
    expect(find.byKey(const ValueKey('meDeleteAccount')), findsNothing);
  });

  testWidgets('AC5: 状态 A 且已建档 → 宠物卡片', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
    );
    expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsNothing);
    expect(find.text('Momo'), findsOneWidget);
  });

  testWidgets('AC5: 状态 A 未建档 → 引导卡', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: false),
    );
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetCard')), findsNothing);
  });

  testWidgets('回归: hasPetProfile=false 但真实档案存在 → 宠物卡片（不误显引导卡）', (tester) async {
    // 用户反馈：老用户登录后已有档案，/me 仍显示「创建宠物档案」引导卡。
    // 根因：登录响应 hasPetProfile 恒 false（stale）。修复：以真实 petProfileProvider 为准。
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: false),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
    );
    expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsNothing);
  });

  testWidgets('AC5: 状态 B/C → 宠物卡片与引导卡均不显示', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'PLANNING', hasPetProfile: false),
    );
    expect(find.byKey(const ValueKey('mePetCard')), findsNothing);
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsNothing);
    // 宠物状态区块已移除（对齐原型 p-profil，「我的」页不再承载改状态/编辑档案入口）。
    expect(find.byKey(const ValueKey('mePetStatus')), findsNothing);
    expect(find.byKey(const ValueKey('meEditPetProfile')), findsNothing);
  });

  testWidgets('AC6(F9): 我的发布原样渲染后端 created_at 倒序，不客户端重排', (tester) async {
    // 后端已按 created_at 倒序返回（id 30 最新 → 10 最旧）；前端不得重排（MyPost 无 event_date）。
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
      posts: const [
        MyPost(id: 30, type: 'GROWTH_MOMENT', text: '今天补录·事件很久前'),
        MyPost(id: 20, type: 'DAILY', text: '中间'),
        MyPost(id: 10, type: 'KNOWLEDGE', text: '最早发布'),
      ],
    );
    // 2 列网格 row-major 保持后端顺序：30 左上、20 右上（同行），10 换到次行左（在 30 下方）。
    final t30 = tester.getTopLeft(find.byKey(const ValueKey('postGridTile_30')));
    final t20 = tester.getTopLeft(find.byKey(const ValueKey('postGridTile_20')));
    final t10 = tester.getTopLeft(find.byKey(const ValueKey('postGridTile_10')));
    expect(t30.dx, lessThan(t20.dx)); // 同行：左→右
    expect(t30.dy, closeTo(t20.dy, 1)); // 30/20 同一行
    expect(t10.dy, greaterThan(t30.dy)); // 第三条换行到下一行
  });

  // ===== V1.3.4 follow Story 1.4 =====

  String cell(WidgetTester tester, String key) => tester
      .widgetList<Text>(find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Text)))
      .first
      .data!;

  testWidgets('V1.3.4 AC2：四格数字来自 /me，大数缩写；邮箱不展示', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', email: 'a@b.com', petStatus: 'PLANNING'),
      counts: const MeCounts(postCount: 1234, likeCount: 3, followingCount: 50, followerCount: 30),
    );
    expect(cell(tester, 'profileStatPosts'), '1.2K');
    expect(cell(tester, 'profileStatLikes'), '3');
    expect(cell(tester, 'profileStatFollowing'), '50');
    expect(cell(tester, 'profileStatFollowers'), '30');
    expect(find.text('a@b.com'), findsNothing, reason: 'PRD OQ-6：我的页不再展示邮箱');
  });

  testWidgets('V1.3.4 AC1.3：/me 失败 → 回落登录态里的计数', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(
          nickname: '小明', petStatus: 'PLANNING', postCount: 7, likeCount: 8, followingCount: 9, followerCount: 10),
      countsError: StateError('offline'),
    );
    expect(cell(tester, 'profileStatPosts'), '7');
    expect(cell(tester, 'profileStatFollowers'), '10');
  });

  testWidgets('V1.3.4 T-14：/me 失败且登录态也没有计数 → 显示「–」，不闪 0', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'PLANNING'),
      countsError: StateError('offline'),
    );
    for (final k in ['profileStatPosts', 'profileStatLikes', 'profileStatFollowing', 'profileStatFollowers']) {
      expect(cell(tester, k), '–', reason: k);
    }
  });

  testWidgets('V1.3.4 AC2.3：Mengikuti / Pengikut 两格本 story 不可点（无 InkWell）', (tester) async {
    await _pump(tester, profile: const UserProfile(nickname: '小明', petStatus: 'PLANNING'));
    for (final k in ['profileStatFollowing', 'profileStatFollowers']) {
      expect(find.descendant(of: find.byKey(ValueKey(k)), matching: find.byType(InkWell)), findsNothing);
    }
  });

  testWidgets('V1.3.4 AC2.4：整宽 Edit Profile 在四格下方', (tester) async {
    await _pump(tester, profile: const UserProfile(nickname: '小明', petStatus: 'PLANNING'));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    final btn = find.byKey(const ValueKey('meEditNickname'));
    expect(find.descendant(of: btn, matching: find.text(l10n.meEditProfileTitle)), findsOneWidget);
    expect(find.text(l10n.meEditButton), findsNothing);
    expect(tester.getSize(btn).height, 40);
    expect(tester.getCenter(btn).dy,
        greaterThan(tester.getCenter(find.byKey(const ValueKey('profileStatPosts'))).dy));
  });

  testWidgets('bug 590：Hewanku 小节用与他人主页同款圆形头像；meta 只含「物种 · 年龄」，diary 数在头像角标', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok', petType: 'CAT'),
    );
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.mePetsSection.toUpperCase()), findsOneWidget);
    final meta = tester.widget<Text>(find.byKey(const ValueKey('mePetCardMeta'))).data!;
    expect(meta, contains(l10n.petTypeCat));
    expect(meta, isNot(contains('diary')));
    // 同一个共享件（与他人主页 Hewan 区视觉一致），不再是整宽行卡。
    final card = find.byKey(const ValueKey('mePetCard'));
    expect(tester.widget(card), isA<PetAvatarItem>());
    expect(tester.getSize(card).width, 84);
    expect(find.descendant(of: find.byKey(const ValueKey('mePetDiaryTag')), matching: find.text('12')),
        findsOneWidget);
    // 无头像 → 宠物图标占位（与他人主页一致，不是昵称首字母）。
    expect(find.descendant(of: card, matching: find.byIcon(Icons.pets)), findsOneWidget);
    expect(find.textContaining(l10n.meViewArchive), findsNothing);
  });

  testWidgets('bug 590：佩戴 Tailsonality 时名字下方显示小标（与他人主页同款）', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok', petType: 'CAT', tailsonalityBadge: 'ENFP'),
    );
    expect(find.byKey(const ValueKey('mePetTailsonality')), findsOneWidget);
    expect(find.text('ENFP'), findsOneWidget);
  });

  testWidgets('V1.3.4 T-13：有宠物未建档时引导卡上方也有 Hewanku 标题；B/C 无标题', (tester) async {
    await _pump(tester, profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET'));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsOneWidget);
    expect(find.text(l10n.mePetsSection.toUpperCase()), findsOneWidget);
  });

  testWidgets('V1.3.4：状态 B/C 无 Hewanku 标题', (tester) async {
    await _pump(tester, profile: const UserProfile(nickname: '小明', petStatus: 'PLANNING'));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.mePetsSection.toUpperCase()), findsNothing);
  });

  // ===== code-review 2026-10-12（Story 1.4）补强 =====

  testWidgets('T-14：/me 还在加载、登录态也没计数（刚登录）→ 四格显示「–」', (tester) async {
    final pending = Completer<MeCounts>();
    await _pump(tester,
        profile: const UserProfile(id: 3, nickname: '小明', petStatus: 'PLANNING'), countsPending: pending);
    expect(cell(tester, 'profileStatPosts'), '–');
    expect(cell(tester, 'profileStatFollowers'), '–');
    pending.complete(const MeCounts(postCount: 4, likeCount: 0, followingCount: 1, followerCount: 2));
    await tester.pumpAndSettle();
    expect(cell(tester, 'profileStatPosts'), '4');
  });

  testWidgets('T-6：两边都有值时以 /me 为准，不用登录态里的', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(
          id: 3, nickname: '小明', petStatus: 'PLANNING', postCount: 1, likeCount: 1, followingCount: 1, followerCount: 1),
      counts: const MeCounts(postCount: 9, likeCount: 8, followingCount: 7, followerCount: 6),
    );
    expect(cell(tester, 'profileStatPosts'), '9');
    expect(cell(tester, 'profileStatFollowers'), '6');
  });

  testWidgets('AC4.3：页面顺序 Edit Profile → Hewanku → Aktivitas → Postinganku', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
    );
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    double y(Finder f) => tester.getCenter(f).dy;
    final edit = y(find.byKey(const ValueKey('meEditNickname')));
    final pets = y(find.text(l10n.mePetsSection.toUpperCase()));
    final orders = y(find.byKey(const ValueKey('meOrders')));
    // V1.4.0 ④ Story 2.4：Postinganku 小节标题换成 Tab 行（不再大写）→ 按 Tab 的 Key 定位，顺序语义不变。
    final posts = y(find.byKey(const ValueKey('meTabMyPosts')));
    expect(edit < pets && pets < orders && orders < posts, isTrue);
  });

  // ===== V1.4.0 multi-pet Story 1.5 · AC2（UI 稿 A0 ~ A3）=====
  group('V1.4.0 multi-pet：Hewanku 宠物列表', () {
    const two = [
      PetListItem(id: 1, name: 'Orenji', petType: 'CAT', isDefault: true, diaryCount: 2),
      PetListItem(id: 2, name: 'Kopi', petType: 'DOG', diaryCount: 5),
    ];

    setUp(() => SharedPreferences.setMockInitialValues({}));
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<ProviderContainer> pumpRouted(WidgetTester tester, List<PetListItem> pets) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final router = GoRouter(initialLocation: '/me', routes: [
        GoRoute(path: '/me', builder: (_, _) => const MePage()),
        GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('diary-tab'))),
        GoRoute(path: '/me/pet-slots', builder: (_, _) => const Scaffold(body: Text('slots'))),
      ]);
      addTearDown(router.dispose);
      final container = ProviderContainer(overrides: [
        myPostsProvider.overrideWith((ref) async => const []),
        meCountsProvider.overrideWith((ref, userId) async =>
            const MeCounts(postCount: 0, likeCount: 0, followingCount: 0, followerCount: 0)),
        petListProvider.overrideWith((ref) async => pets),
      ]);
      addTearDown(container.dispose);
      container
          .read(authControllerProvider.notifier)
          .applyProfile(const UserProfile(nickname: 'A', petStatus: 'HAS_PET', hasPetProfile: true));
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
        ),
      ));
      await tester.pumpAndSettle();
      return container;
    }

    testWidgets('A1：两只 → 两个头像 +「+」，标题右侧「Manage ›」', (tester) async {
      await _pump(tester,
          profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET'), pets: two);
      expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetCard-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetAdd')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetsManage')), findsOneWidget);
      expect(find.text('Manage'), findsOneWidget);
      // 每只的 diary 角标取各自的 diaryCount。
      expect(find.descendant(of: find.byKey(const ValueKey('mePetDiaryTag-1')), matching: find.text('5')),
          findsOneWidget);
      // 头像 56（UI 稿 A1）。
      final avatar = find.descendant(
          of: find.byKey(const ValueKey('mePetCard')), matching: find.byType(CircleAvatar));
      expect(tester.getSize(avatar).width, 56);
    });

    testWidgets('A2：只有 1 只 → 1 个头像 +「+」，同样有 Manage', (tester) async {
      await _pump(tester,
          profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET'), pets: [two.first]);
      expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetCard-1')), findsNothing);
      expect(find.byKey(const ValueKey('mePetAdd')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetsManage')), findsOneWidget);
    });

    testWidgets('A0：列表为空 → 沿用引导卡，没有「+」与 Manage', (tester) async {
      await _pump(tester,
          profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET'), pets: const []);
      expect(find.byKey(const ValueKey('mePetGuideCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetAdd')), findsNothing);
      expect(find.byKey(const ValueKey('mePetsManage')), findsNothing);
    });

    testWidgets('A3：只读宠物 → 锁标在头像左下（避开右上 diary 角标）', (tester) async {
      await _pump(tester, profile: const UserProfile(nickname: '小明', petStatus: 'HAS_PET'), pets: const [
        PetListItem(id: 1, name: 'Orenji', isDefault: true, diaryCount: 2),
        PetListItem(id: 2, name: 'Kopi', status: 'READ_ONLY', diaryCount: 5),
      ]);
      expect(find.byKey(const ValueKey('mePetLock')), findsNothing);
      final lock = find.byKey(const ValueKey('mePetLock-1'));
      expect(lock, findsOneWidget);
      final avatar = find.descendant(
          of: find.byKey(const ValueKey('mePetCard-1')), matching: find.byType(CircleAvatar));
      final a = tester.getRect(avatar);
      final l = tester.getCenter(lock);
      expect(l.dx, lessThan(a.center.dx), reason: '锁标在左');
      expect(l.dy, greaterThan(a.center.dy), reason: '锁标在下');
      final badge = tester.getCenter(find.byKey(const ValueKey('mePetDiaryTag-1')));
      expect(badge.dx, greaterThan(a.center.dx), reason: 'diary 角标仍在右上');
    });

    testWidgets('点另一只 → select + 跳 Diary Tab + pet_switched {source: me_page}', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      final c = await pumpRouted(tester, two);

      await tester.tap(find.byKey(const ValueKey('mePetCard-1')));
      await tester.pumpAndSettle();

      expect(c.read(currentPetIdProvider), 2);
      expect(find.text('diary-tab'), findsOneWidget);
      expect(events.where((e) => e.$1 == 'pet_switched').map((e) => e.$2), [
        {'from_pet_id': 1, 'to_pet_id': 2, 'source': 'me_page'},
      ]);
    });

    testWidgets('点当前那只 → 照样进 Diary Tab，但不报 pet_switched（from == to）', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      await pumpRouted(tester, two);

      await tester.tap(find.byKey(const ValueKey('mePetCard')));
      await tester.pumpAndSettle();

      expect(find.text('diary-tab'), findsOneWidget);
      expect(events.where((e) => e.$1 == 'pet_switched'), isEmpty);
    });

    testWidgets('code review #2：宠物列表失效后 diary 角标 / 性格小标跟着刷新', (tester) async {
      var calls = 0;
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final c = ProviderContainer(overrides: [
        myPostsProvider.overrideWith((ref) async => const []),
        meCountsProvider.overrideWith((ref, userId) async =>
            const MeCounts(postCount: 0, likeCount: 0, followingCount: 0, followerCount: 0)),
        petListProvider.overrideWith((ref) async {
          calls++;
          return [
            PetListItem(
                id: 1,
                name: 'Orenji',
                isDefault: true,
                diaryCount: calls == 1 ? 2 : 3,
                tailsonalityBadge: calls == 1 ? null : 'ENFP'),
          ];
        }),
      ]);
      addTearDown(c.dispose);
      c.read(authControllerProvider.notifier)
          .applyProfile(const UserProfile(nickname: 'A', petStatus: 'HAS_PET'));
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('en'),
          home: MePage(),
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.descendant(of: find.byKey(const ValueKey('mePetDiaryTag')), matching: find.text('2')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('mePetTailsonality')), findsNothing);

      // 发布 Diary / 佩戴小标后各调用点都会失效宠物列表（见 pet_list_refresh_guard_test）。
      c.invalidate(petListProvider);
      await tester.pumpAndSettle();
      expect(find.descendant(of: find.byKey(const ValueKey('mePetDiaryTag')), matching: find.text('3')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('mePetTailsonality')), findsOneWidget);
    });

    testWidgets('Manage › → 名额管理页（占位）', (tester) async {
      await pumpRouted(tester, two);
      await tester.tap(find.byKey(const ValueKey('mePetsManage')));
      await tester.pumpAndSettle();
      expect(find.text('slots'), findsOneWidget);
    });
  });

  // ===== V1.4.0 push-collect Story 3.1 · AC4.5：四线合并后的「我的」页同屏 =====
  testWidgets('3.1 AC4：等级胶囊 + Hewanku 两只 + Tab 行同屏；切到 Disimpan 后名片与 Hewanku 不变', (tester) async {
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    SharedPreferences.setMockInitialValues({});
    addTearDown(() => Analytics.debugCaptureSink = null);
    Analytics.debugCaptureSink = (_, _) {};
    const brief = GrowthBrief(level: 7, tier: GrowthTier.buddy, frame: 'BASIC', lifetimeTotal: 1420, nextThreshold: 1800);
    const two = [
      PetListItem(id: 1, name: 'Orenji', petType: 'CAT', isDefault: true, diaryCount: 2),
      PetListItem(id: 2, name: 'Kopi', petType: 'DOG', diaryCount: 5),
    ];
    final container = ProviderContainer(retry: (_, _) => null, overrides: [
      myPostsProvider.overrideWith((ref) async => const <MyPost>[]),
      meCountsProvider.overrideWith((ref, userId) async => const MeCounts(postCount: 1, growth: brief)),
      petListProvider.overrideWith((ref) async => two),
      collectRepositoryProvider.overrideWithValue(FakeCollectRepo()),
    ]);
    addTearDown(container.dispose);
    container
        .read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(id: 1, nickname: '小明', petStatus: 'HAS_PET', hasPetProfile: true));
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: MePage(),
      ),
    ));
    await tester.pumpAndSettle();

    double y(Finder f) => tester.getCenter(f).dy;
    void expectHeadAndPets() {
      expect(find.byKey(const ValueKey('meLevelCapsule')), findsOneWidget);
      expect(find.byKey(const ValueKey('meAvatarUpload')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetCard-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('mePetsManage')), findsOneWidget);
      // 自上而下：名片（胶囊）→ Edit Profil → Hewanku → Aktivitas → Tab 行。
      final capsule = y(find.byKey(const ValueKey('meLevelCapsule')));
      final edit = y(find.byKey(const ValueKey('meEditNickname')));
      final pets = y(find.byKey(const ValueKey('mePetCard')));
      final orders = y(find.byKey(const ValueKey('meOrders')));
      final tabs = y(find.byKey(const ValueKey('meTabMyPosts')));
      expect(capsule < edit && edit < pets && pets < orders && orders < tabs, isTrue);
      expect(y(find.byKey(const ValueKey('meTabSaved'))), tabs);
    }

    expectHeadAndPets();
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('meTabSaved')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('savedSegPost')), findsOneWidget);
    expect(find.byKey(const ValueKey('meNoPosts')), findsNothing);
    expectHeadAndPets();
  });
}
