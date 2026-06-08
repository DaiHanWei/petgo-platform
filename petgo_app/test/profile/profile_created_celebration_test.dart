import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/domain/card_link.dart';
import 'package:petgo/features/profile/domain/profile_created_flow.dart';
import 'package:petgo/features/profile/domain/share_service.dart';
import 'package:petgo/features/profile/presentation/profile_created_celebration_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

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
    required ShareFn shareFn,
  }) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [shareServiceProvider.overrideWithValue(shareFn)],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: ProfileCreatedCelebrationPage(
          petName: 'Momo',
          cardToken: 'tok123',
          avatarUrl: null,
          onStartExplore: onStartExplore,
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('AC4: 庆祝页渲染（头像/名字/文案/主副双 CTA）', (tester) async {
    await pumpCelebration(tester, onStartExplore: () async {}, shareFn: (_) async {});

    expect(find.byKey(const ValueKey('celebrationAvatar')), findsOneWidget);
    expect(find.text('Momo'), findsOneWidget);
    expect(find.text("Momo's very own profile is ready! 🎉"), findsOneWidget);
    expect(find.byKey(const ValueKey('celebrationStartExplore')), findsOneWidget);
    expect(find.byKey(const ValueKey('celebrationShare')), findsOneWidget);
  });

  testWidgets('AC4: 主 CTA「开始探索」→ 触发推送/进首页钩子', (tester) async {
    var started = false;
    await pumpCelebration(
      tester,
      onStartExplore: () async => started = true,
      shareFn: (_) async {},
    );

    await tester.tap(find.byKey(const ValueKey('celebrationStartExplore')));
    await tester.pumpAndSettle();
    expect(started, isTrue);
  });

  testWidgets('AC4: 副 CTA「分享宠物名片」→ 调系统分享传 FR-14 名片链接', (tester) async {
    String? shared;
    await pumpCelebration(
      tester,
      onStartExplore: () async {},
      shareFn: (text) async => shared = text,
    );

    await tester.tap(find.byKey(const ValueKey('celebrationShare')));
    await tester.pumpAndSettle();
    expect(shared, petCardShareUrl('tok123'));
  });
}
