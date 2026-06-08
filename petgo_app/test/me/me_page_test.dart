import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/me/data/my_posts_repository.dart';
import 'package:petgo/features/me/presentation/me_page.dart';
import 'package:petgo/features/profile/data/profile_repository.dart';
import 'package:petgo/features/profile/domain/pet_profile.dart';
import 'package:petgo/l10n/app_localizations.dart';

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
      profile: const UserProfile(nickname: '小明', petStatus: 'A', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
    );
    // 顶栏右上双图标（帮助 + 设置）
    expect(find.byKey(const ValueKey('meHelp')), findsOneWidget);
    expect(find.byKey(const ValueKey('meSettings')), findsOneWidget);
    // 主体：用户信息 / 宠物状态 / 我的发布
    expect(find.text('小明'), findsOneWidget);
    expect(find.byKey(const ValueKey('meEditNickname')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetStatus')), findsOneWidget);
    expect(find.byKey(const ValueKey('meEditPetProfile')), findsOneWidget);
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget);
    // 🔄 F8：语言/退出/注销不再在主页平铺（已挪入二级设置页）
    expect(find.byKey(const ValueKey('meLanguage')), findsNothing);
    expect(find.byKey(const ValueKey('meLogout')), findsNothing);
    expect(find.byKey(const ValueKey('meDeleteAccount')), findsNothing);
  });

  testWidgets('AC5: 状态 A 且已建档 → 宠物卡片', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'A', hasPetProfile: true),
      pet: const PetProfile(id: 1, name: 'Momo', cardToken: 'tok'),
    );
    expect(find.byKey(const ValueKey('mePetCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsNothing);
    expect(find.text('Momo'), findsOneWidget);
  });

  testWidgets('AC5: 状态 A 未建档 → 引导卡', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'A', hasPetProfile: false),
    );
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetCard')), findsNothing);
  });

  testWidgets('AC5: 状态 B/C → 宠物卡片与引导卡均不显示', (tester) async {
    await _pump(
      tester,
      profile: const UserProfile(nickname: '小明', petStatus: 'B', hasPetProfile: false),
    );
    expect(find.byKey(const ValueKey('mePetCard')), findsNothing);
    expect(find.byKey(const ValueKey('mePetGuideCard')), findsNothing);
    // 宠物状态区块仍在（B 不显示档案编辑入口）
    expect(find.byKey(const ValueKey('mePetStatus')), findsOneWidget);
    expect(find.byKey(const ValueKey('meEditPetProfile')), findsNothing);
  });
}
