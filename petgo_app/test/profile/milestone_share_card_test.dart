import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/domain/celebration_pet.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_badge_assets.dart';
import 'package:tailtopia/features/profile/domain/milestone_compose_preset.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_share_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.4.2 social-topic Story 3.1 · AC1（UI D6 / D7）：里程碑卡结构断言（视觉还原度 L2 本地验，不写 golden）。
void main() {
  setUp(() => MilestoneBadgeAssets.debugOverride = const {});
  tearDown(() => MilestoneBadgeAssets.debugOverride = null);

  MilestoneItem ms(String code, MilestoneLevel level) => MilestoneItem(
      code: code, title: code, level: level, trigger: MilestoneTrigger.systemAuto, completed: true);

  List<CelebrationPet> pets(int n) => [
        for (final name in ['Orenji', 'Kopi', 'Boba', 'Mochi', 'Kiki'].take(n)) CelebrationPet(name: name, petType: 'CAT'),
      ];

  Future<void> pump(WidgetTester tester,
      {MilestoneItem? item, int petCount = 1, Locale locale = const Locale('id')}) async {
    await tester.pumpWidget(MaterialApp(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Center(
        child: SizedBox(
          width: 360,
          child: CardFrame(
            boundaryKey: GlobalKey(),
            canvas: kMilestoneShareCanvas,
            child: MilestoneShareCard(
                item: item ?? ms('C-S1', MilestoneLevel.s), pets: pets(petCount), completedAt: DateTime(2026, 10, 28, 12)),
          ),
        ),
      ),
    ));
    await tester.pump();
  }

  BoxDecoration deco(WidgetTester tester, String key) =>
      tester.widget<Container>(find.byKey(ValueKey(key))).decoration! as BoxDecoration;

  Color? fill(WidgetTester tester, String key) =>
      (tester.widget<DecoratedBox>(find.byKey(ValueKey(key))).decoration as BoxDecoration).color;

  group('级别色（花结环 / 飘带 / 芯片）', () {
    testWidgets('S 级：绿色芯片 / 色环 / 飘带，飘带无描边', (tester) async {
      await pump(tester, item: ms('C-S1', MilestoneLevel.s));
      expect(deco(tester, 'milestoneShareLevelChip').color, AppColors.triageGreen);
      expect(fill(tester, 'milestoneShareRosetteRing'), AppColors.triageGreen);
      expect(deco(tester, 'milestoneShareRibbonL').color, AppColors.triageGreen);
      expect(deco(tester, 'milestoneShareRibbonL').border, isNull);
    });

    testWidgets('M 级：芯片 / 色环 mint，飘带 mint600 + 12px 白描边', (tester) async {
      await pump(tester, item: ms('C-M1', MilestoneLevel.m));
      expect(deco(tester, 'milestoneShareLevelChip').color, AppColors.mint);
      expect(fill(tester, 'milestoneShareRosetteRing'), AppColors.mint);
      for (final k in ['milestoneShareRibbonL', 'milestoneShareRibbonR']) {
        final d = deco(tester, k);
        expect(d.color, AppColors.mint600);
        final border = d.border! as Border;
        expect(border.top.width, 12);
        expect(border.top.color, Colors.white);
      }
    });

    testWidgets('L 级：金色，飘带无描边', (tester) async {
      await pump(tester, item: ms('C-L2', MilestoneLevel.l));
      expect(deco(tester, 'milestoneShareLevelChip').color, AppColors.gold);
      expect(fill(tester, 'milestoneShareRosetteRing'), AppColors.gold);
      expect(deco(tester, 'milestoneShareRibbonR').color, AppColors.gold);
      expect(deco(tester, 'milestoneShareRibbonR').border, isNull);
    });
  });

  testWidgets('卡面五项：字标 SVG、标头、级别芯片、短标题（localizedMilestoneTitle）、日期短格式', (tester) async {
    await pump(tester);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.byKey(const ValueKey('milestoneShareWordmark')), findsOneWidget);
    expect(find.byType(SvgPicture), findsOneWidget);
    expect(find.text(l10n.milestoneCelebrateUnlocked.toUpperCase()), findsOneWidget);
    expect(find.text(l10n.milestoneLevelChipS), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('milestoneShareTitle'))).data, 'Profil dibuat');
    expect(tester.widget<Text>(find.byKey(const ValueKey('milestoneShareTitle'))).maxLines, 3);
    expect(tester.widget<Text>(find.byKey(const ValueKey('milestoneShareDate'))).data, '28 Okt 2026');
  });

  testWidgets('EN 日期短格式 28 Oct 2026；标题英文', (tester) async {
    await pump(tester, locale: const Locale('en'));
    expect(tester.widget<Text>(find.byKey(const ValueKey('milestoneShareDate'))).data, '28 Oct 2026');
    expect(tester.widget<Text>(find.byKey(const ValueKey('milestoneShareTitle'))).data, 'Profile created');
  });

  testWidgets('无二维码、无水印', (tester) async {
    await pump(tester);
    expect(find.byType(CardQr), findsNothing);
    expect(find.byType(CardWatermark), findsNothing);
  });

  group('页脚 1 / 2 / 3 / 5 只', () {
    String names(WidgetTester tester) => tester.widget<Text>(find.byKey(const ValueKey('milestoneSharePetNames'))).data!;
    double nameSize(WidgetTester tester) =>
        tester.widget<Text>(find.byKey(const ValueKey('milestoneSharePetNames'))).style!.fontSize!;

    testWidgets('1 只：头像 112、名字 40', (tester) async {
      await pump(tester, petCount: 1);
      expect(tester.getSize(find.byKey(const ValueKey('milestoneShareAvatar-0'))), const Size(112, 112));
      expect(names(tester), 'Orenji');
      expect(nameSize(tester), 40);
    });

    testWidgets('2 只：头像 88、「A & B」40', (tester) async {
      await pump(tester, petCount: 2);
      expect(tester.getSize(find.byKey(const ValueKey('milestoneShareAvatar-1'))), const Size(88, 88));
      expect(names(tester), 'Orenji & Kopi');
      expect(nameSize(tester), 40);
    });

    testWidgets('3 只：「A, B & C」34，头像每只左移 22 叠放', (tester) async {
      await pump(tester, petCount: 3);
      expect(names(tester), 'Orenji, Kopi & Boba');
      expect(nameSize(tester), 34);
      final a0 = tester.widget<Positioned>(
          find.ancestor(of: find.byKey(const ValueKey('milestoneShareAvatar-0')), matching: find.byType(Positioned)).first);
      final a1 = tester.widget<Positioned>(
          find.ancestor(of: find.byKey(const ValueKey('milestoneShareAvatar-1')), matching: find.byType(Positioned)).first);
      expect(a1.left! - a0.left!, 88 - 22);
    });

    testWidgets('5 只：5 个头像、句式无 dkk.、最多 2 行', (tester) async {
      await pump(tester, petCount: 5);
      for (var i = 0; i < 5; i++) {
        expect(find.byKey(ValueKey('milestoneShareAvatar-$i')), findsOneWidget);
      }
      expect(names(tester), 'Orenji, Kopi, Boba, Mochi & Kiki');
      expect(names(tester).contains('dkk'), isFalse);
      expect(tester.widget<Text>(find.byKey(const ValueKey('milestoneSharePetNames'))).maxLines, 2);
    });

    testWidgets('上半部不随宠物数变：标题 / 花结位置一致', (tester) async {
      await pump(tester, petCount: 1);
      final t1 = tester.getTopLeft(find.byKey(const ValueKey('milestoneShareTitle')));
      final r1 = tester.getTopLeft(find.byKey(const ValueKey('milestoneShareRosette')));
      await pump(tester, petCount: 5);
      expect(tester.getTopLeft(find.byKey(const ValueKey('milestoneShareTitle'))), t1);
      expect(tester.getTopLeft(find.byKey(const ValueKey('milestoneShareRosette'))), r1);
    });
  });

  testWidgets('花结素材缺失 → 同心圆兜底；徽章素材缺失 → 紫渐变奖杯回落', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('milestoneShareRosetteFallback')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneShareRosetteArt')), findsNothing);
    expect(find.byKey(const ValueKey('milestoneShareBadgeFallback')), findsOneWidget);
  });

  testWidgets('新编码 first_friend（C-S19）素材未到 → 回落，不报错', (tester) async {
    await pump(tester, item: ms('C-S19', MilestoneLevel.s));
    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('milestoneShareBadgeFallback')), findsOneWidget);
  });

  testWidgets('花结素材入包 → 切到 SVG（不画兜底同心圆）', (tester) async {
    MilestoneBadgeAssets.debugOverride = {milestoneRosetteAssetPath(MilestoneLevel.m.name)};
    await pump(tester, item: ms('C-M1', MilestoneLevel.m));
    expect(find.byKey(const ValueKey('milestoneShareRosetteArt')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneShareRosetteFallback')), findsNothing);
  });

  testWidgets('徽章素材入包 → 画该枚素材（不走回落）', (tester) async {
    MilestoneBadgeAssets.debugOverride = {milestoneBadgeAssetPath(milestoneBadgeKeyOf('C-S1')!)};
    await pump(tester);
    expect(find.byKey(const ValueKey('milestoneShareBadge_art_true')), findsOneWidget);
  });

  test('formatMilestoneShortDate：id / en 月份表', () {
    expect(formatMilestoneShortDate(DateTime(2026, 8, 3), const Locale('id')), '3 Agu 2026');
    expect(formatMilestoneShortDate(DateTime(2026, 5, 3), const Locale('id')), '3 Mei 2026');
    expect(formatMilestoneShortDate(DateTime(2026, 12, 31), const Locale('id')), '31 Des 2026');
    expect(formatMilestoneShortDate(DateTime(2026, 5, 3), const Locale('en')), '3 May 2026');
  });
}
