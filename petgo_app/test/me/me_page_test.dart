import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/me/presentation/me_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 7.1 · F8：信息架构重组——顶栏双图标 + 主体「人+宠物」 + AC5 宠物区位三态。
Future<void> _pump(
  WidgetTester tester, {
  required UserProfile profile,
  PetProfile? pet,
  List<MyPost> posts = const [],
}) async {
  tester.view.physicalSize = const Size(1200, 3200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final container = ProviderContainer(overrides: [
    myPostsProvider.overrideWith((ref) async => posts),
    petProfileProvider.overrideWith((ref) async => pet),
    // 宠物卡元数据「momen 数」取此 provider；测试不打真实后端。
    archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
        happyMomentCount: 12, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
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

void main() {
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
    final t30 = tester.getTopLeft(find.byKey(const ValueKey('myPost_30')));
    final t20 = tester.getTopLeft(find.byKey(const ValueKey('myPost_20')));
    final t10 = tester.getTopLeft(find.byKey(const ValueKey('myPost_10')));
    expect(t30.dx, lessThan(t20.dx)); // 同行：左→右
    expect(t30.dy, closeTo(t20.dy, 1)); // 30/20 同一行
    expect(t10.dy, greaterThan(t30.dy)); // 第三条换行到下一行
  });
}
