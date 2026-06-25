import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/profile_created_flow.dart';
import 'package:tailtopia/features/profile/presentation/profile_created_celebration_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

void main() {
  group('AC4 来源分支（纯函数 · F15）', () {
    test('FR-0G 正常建档 → 展示庆祝页', () {
      expect(showsBuildCelebration(BuildOrigin.onboarding), isTrue);
    });
    test('FR-16 问诊存档 / FR-12 灰选发布 → 跳过庆祝页', () {
      expect(showsBuildCelebration(BuildOrigin.triageArchive), isFalse);
      expect(showsBuildCelebration(BuildOrigin.graySelectPublish), isFalse);
    });
    test('来源解析：未知/缺省 → onboarding', () {
      expect(buildOriginFromName(null), BuildOrigin.onboarding);
      expect(buildOriginFromName('whatever'), BuildOrigin.onboarding);
      expect(buildOriginFromName('triageArchive'), BuildOrigin.triageArchive);
      expect(buildOriginFromName('graySelectPublish'), BuildOrigin.graySelectPublish);
    });
  });

  Future<void> pumpCelebration(
    WidgetTester tester, {
    required Future<void> Function() onStartExplore,
  }) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: ProfileCreatedCelebrationPage(
        petName: 'Momo',
        avatarUrl: null,
        onStartExplore: onStartExplore,
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('AC4: 庆祝页渲染（头像/问候/里程碑卡/主次双 CTA，对齐 pet-success）', (tester) async {
    await pumpCelebration(tester, onStartExplore: () async {});

    expect(find.byKey(const ValueKey('celebrationAvatar')), findsOneWidget);
    expect(find.text('Hello, Momo! 🎉'), findsOneWidget); // 问候（en 默认 locale）
    expect(find.text('First milestone unlocked!'), findsOneWidget); // 里程碑解锁卡
    expect(find.byKey(const ValueKey('celebrationStartExplore')), findsOneWidget); // 主 CTA
    expect(find.byKey(const ValueKey('celebrationViewProfile')), findsOneWidget); // 次 CTA
  });

  testWidgets('AC4: 主 CTA → 触发推送/进首页钩子', (tester) async {
    var started = false;
    await pumpCelebration(tester, onStartExplore: () async => started = true);

    await tester.tap(find.byKey(const ValueKey('celebrationStartExplore')));
    await tester.pumpAndSettle();
    expect(started, isTrue);
  });

  testWidgets('AC4: 次 CTA「Lihat profil dulu」→ 同样进 App（共用 onStartExplore）', (tester) async {
    var started = false;
    await pumpCelebration(tester, onStartExplore: () async => started = true);

    await tester.tap(find.byKey(const ValueKey('celebrationViewProfile')));
    await tester.pumpAndSettle();
    expect(started, isTrue);
  });
}
