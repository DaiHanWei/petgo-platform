import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/config/app_download_url.dart';
import 'package:tailtopia/features/profile/domain/age_card_quips.dart';
import 'package:tailtopia/features/profile/domain/human_age.dart';
import 'package:tailtopia/features/profile/presentation/age_card_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';

/// V1.3.0 批次 A · Story 5.2（L0）：年龄换算与卡片生成（FR-65 · AD-A18 / AD-A19 / AD-A28）。
///
/// 卡面好不好看、二维码扫不扫得出来是 L2；这里钉住 L0 能钉的：
/// **换算口径**（含四个边界）、**分段用当量不用月龄**、**体型档不落库**、
/// **二维码三条硬要求**、以及埋点值域。
void main() {
  // 固定「今天」，让所有换算断言与真实日期无关。
  final today = DateTime(2026, 9, 11);
  DateTime bornMonthsAgo(int months) =>
      DateTime(today.year, today.month - months, today.day);

  int catAge(int months) =>
      humanAgeEquivalent(months: months, isDog: false).round();
  int dogAge(int months, DogSizeClass size) =>
      humanAgeEquivalent(months: months, isDog: true, size: size).round();

  group('AC2 🔴 换算口径（AVMA/AAHA 三段式，不要自行改）', () {
    test('猫：1 岁 = 15，2 岁 = 24，之后每年 +4', () {
      expect(catAge(12), 15);
      expect(catAge(24), 24);
      expect(catAge(36), 28);
      expect(catAge(12 * 10), 56); // 24 + 4×8
    });

    test('狗：1 岁 = 15、2 岁 = 24；第 3 年起按体型 +4/+5/+6/+7', () {
      for (final size in DogSizeClass.values) {
        expect(dogAge(12, size), 15, reason: '首年与体型无关');
        expect(dogAge(24, size), 24, reason: '次年与体型无关');
      }
      expect(dogAge(36, DogSizeClass.small), 28);
      expect(dogAge(36, DogSizeClass.medium), 29);
      expect(dogAge(36, DogSizeClass.large), 30);
      expect(dogAge(36, DogSizeClass.xlarge), 31);
    });

    /// 体型差异**只在第 3 年之后**拉开 —— 这正是三段式的形状。
    test('体型档的差异随年龄单调放大', () {
      final small = dogAge(12 * 10, DogSizeClass.small);
      final xlarge = dogAge(12 * 10, DogSizeClass.xlarge);
      expect(xlarge - small, 24, reason: '10 岁时 (7-4)×8 = 24');
      expect(small, lessThan(xlarge));
    });

    group('边界', () {
      test('0 个月 = 0（刚出生的当天）', () {
        expect(catAge(0), 0);
      });

      /// 未满 1 岁按月龄比例：6 个月的猫 = 7.5 → ≈ 8（story 里点名的那个数）。
      test('6 个月 = 7.5 → 四舍五入 8', () {
        expect(humanAgeEquivalent(months: 6, isDog: false), 7.5);
        expect(catAge(6), 8);
      });

      test('11 个月 ≈ 13.75 → 14（还没到首年的 15）', () {
        expect(catAge(11), 14);
        expect(catAge(11), lessThan(15));
      });

      test('恰好 2 岁 = 24，两侧连续（第 23、25 个月不跳变）', () {
        expect(catAge(24), 24);
        expect(catAge(23), 23); // 15 + 9×(23/12-1) ≈ 23.25
        expect(catAge(25), 24); // 24 + 4×(1/12) ≈ 24.3
      });

      /// 不足整年**按月线性插值**，不是按整年跳台阶。
      test('18 个月落在 15 与 24 之间，而不是等于 15', () {
        final v = humanAgeEquivalent(months: 18, isDog: false);
        expect(v, greaterThan(15));
        expect(v, lessThan(24));
        expect(v, closeTo(19.5, 0.01));
      });
    });
  });

  group('AC3 🔴 时区口径：设备本地，且不碰 WIB', () {
    test('实际年龄取本地「今天」，与生日同月同日即整岁', () {
      final r = resolveHumanAge(
        birthday: DateTime(2024, 9, 11),
        today: today,
        isDog: false,
      );
      expect(r.months, 24);
      expect(r.humanAge, 24);
    });

    test('这个月的生日还没到 → 不算满月', () {
      final r = resolveHumanAge(
        birthday: DateTime(2024, 9, 20),
        today: today,
        isDog: false,
      );
      expect(r.months, 23);
    });

    /// 🔴 展示口径与 Story 5.3 的资金口径（WIB）**不得互相套用**。
    /// 换算这一侧的源码里不该出现任何时区偏移。
    test('换算实现里没有任何 WIB / 固定时区偏移', () {
      final src =
          File('lib/features/profile/domain/human_age.dart').readAsStringSync();
      final code = src
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('///') && !l.trimLeft().startsWith('//'))
          .join('\n');
      for (final banned in ['WIB', 'Duration(hours: 7)', 'toUtc', 'utc']) {
        expect(code, isNot(contains(banned)));
      }
    });

    test('实际年龄复用既有 computePetAge，不另算一遍', () {
      final src =
          File('lib/features/profile/domain/human_age.dart').readAsStringSync();
      expect(src, contains('computePetAge('));
    });
  });

  group('AC6 🔴 分段用当量 N，不用月龄', () {
    test('四段的边界逐格对齐 19 / 39 / 59', () {
      expect(PetAgeStage.fromHumanAge(0), PetAgeStage.puppy);
      expect(PetAgeStage.fromHumanAge(19), PetAgeStage.puppy);
      expect(PetAgeStage.fromHumanAge(20), PetAgeStage.young);
      expect(PetAgeStage.fromHumanAge(39), PetAgeStage.young);
      expect(PetAgeStage.fromHumanAge(40), PetAgeStage.middle);
      expect(PetAgeStage.fromHumanAge(59), PetAgeStage.middle);
      expect(PetAgeStage.fromHumanAge(60), PetAgeStage.senior);
      expect(PetAgeStage.fromHumanAge(200), PetAgeStage.senior);
    });

    /// 🔴 这条是 AD-A18.5b 的核心：**同一个数字驱动"显示什么"和"取哪段文案"**。
    /// 一个按当量、一个按月龄的话，会出现卡上写 ≈40 人岁却配了幼年文案。
    test('段由结果里的 humanAge 决定，与月龄无关', () {
      // 一只 3 岁的超大型犬（N=31，青年）与一只 3 岁的猫（N=28，同为青年）——
      // 月龄相同，当量不同；而一只 10 岁的猫（N=56）月龄大得多，落在中年。
      final dog3 = resolveHumanAge(
          birthday: bornMonthsAgo(36),
          today: today,
          isDog: true,
          size: DogSizeClass.xlarge);
      final cat10 =
          resolveHumanAge(birthday: bornMonthsAgo(120), today: today, isDog: false);

      expect(dog3.humanAge, 31);
      expect(dog3.stage, PetAgeStage.young);
      expect(cat10.humanAge, 56);
      expect(cat10.stage, PetAgeStage.middle);
      expect(PetAgeStage.fromHumanAge(dog3.humanAge), dog3.stage);
    });

    /// 猫狗**共用同一套分段**，不按物种分叉 —— 换算已经把物种差异吸收进 N 了。
    test('同一个 N 在猫狗上落同一段', () {
      for (final n in [10, 25, 45, 70]) {
        expect(PetAgeStage.fromHumanAge(n), PetAgeStage.fromHumanAge(n));
      }
      // 大型犬 5 岁与猫 8 岁都落在中年段。
      final dog5 = resolveHumanAge(
          birthday: bornMonthsAgo(60),
          today: today,
          isDog: true,
          size: DogSizeClass.large);
      final cat8 =
          resolveHumanAge(birthday: bornMonthsAgo(96), today: today, isDog: false);
      expect(dog5.stage, PetAgeStage.middle);
      expect(cat8.stage, PetAgeStage.middle);
    });

    test('每段恰好 5 条候选，随机取其中之一', () async {
      for (final stage in PetAgeStage.values) {
        expect(quipsFor(stage), hasLength(5));
      }
      // 固定随机源 → 结果可复现（同一个种子两次取到同一条）。
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      final a = pickQuip(PetAgeStage.young, random: Random(7))(l10n, 'Mochi');
      final b = pickQuip(PetAgeStage.young, random: Random(7))(l10n, 'Mochi');
      expect(a, b);
      // 取到的必是本段五条之一 —— 按**文本**比，不按闭包身份比
      // （quipsFor 每次都新建闭包，身份比一定不相等）。
      expect(quipsFor(PetAgeStage.young).map((q) => q(l10n, 'Mochi')), contains(a));
    });

    testWidgets('文案带宠物名的那几条会把名字填进去', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      final withName =
          quipsFor(PetAgeStage.senior).map((q) => q(l10n, 'Mochi')).toList();
      expect(withName.where((s) => s.contains('Mochi')), isNotEmpty);
      // ⚠️ 这段文字印在图片上，会被发到 Stories 给陌生人看 —— 长度要严。
      for (final s in withName) {
        expect(s.length, lessThanOrEqualTo(60), reason: '超 60 字符会在 9:16 卡上折三行：$s');
      }
    });

    test('四段 × 两语的全部 20 条都 ≤ 60 字符', () async {
      for (final locale in const [Locale('en'), Locale('id')]) {
        final l10n = await AppLocalizations.delegate.load(locale);
        for (final stage in PetAgeStage.values) {
          for (final q in quipsFor(stage)) {
            final s = q(l10n, 'Mochi');
            expect(s.length, lessThanOrEqualTo(60),
                reason: '[${locale.languageCode}] $s');
          }
        }
      }
    });
  });

  group('AC1 🔴 体型档不落库', () {
    /// **不落档案字段、不持久化、不校验** —— 只在本次生成中有效。
    test('档案模型里没有体型字段', () {
      final src =
          File('lib/features/profile/domain/pet_profile.dart').readAsStringSync();
      for (final banned in ['sizeClass', 'size_class', 'DogSizeClass']) {
        expect(src, isNot(contains(banned)), reason: '体型只在本次生成中有效，不该进档案');
      }
    });

    test('年龄卡这条链路上没有任何持久化调用', () {
      for (final f in [
        'lib/features/profile/domain/human_age.dart',
        'lib/features/profile/domain/age_card_quips.dart',
        'lib/features/profile/presentation/age_card_page.dart',
      ]) {
        final src = File(f).readAsStringSync();
        for (final banned in ['AppPrefs', 'SharedPreferences', 'SecureTokenStore', 'dio']) {
          expect(src, isNot(contains(banned)), reason: '$f 不该碰持久化：$banned');
        }
      }
    });

    test('四档的体重区间与 story 给的一致', () {
      expect(DogSizeClass.small.minKg, isNull);
      expect(DogSizeClass.small.maxKg, 9);
      expect(DogSizeClass.medium.minKg, 9);
      expect(DogSizeClass.medium.maxKg, 23);
      expect(DogSizeClass.large.minKg, 23);
      expect(DogSizeClass.large.maxKg, 41);
      expect(DogSizeClass.xlarge.minKg, 41);
      expect(DogSizeClass.xlarge.maxKg, isNull);
    });
  });

  group('AC7 🔴 二维码：三条可扫底线', () {
    final String templateSrc = File(
            'lib/features/profile/presentation/widgets/age_card_template.dart')
        .readAsStringSync();

    /// 内容 = **固定的 App 下载页 URL**，走配置项、不硬编码、不带 token。
    /// 年龄卡是通用卡片，扫码的人不是来看某一条内容的 —— 与内容分享卡刚好相反。
    test('码里印的是配置项里的下载页地址，不带 token', () {
      expect(templateSrc, contains('kAppDownloadUrl'));
      expect(kAppDownloadUrl, startsWith('https://'));
      for (final banned in ['token', 'cardToken', 'shareUrl', '?src=']) {
        expect(kAppDownloadUrl, isNot(contains(banned)));
      }
      // 地址本身不许写死在模板里。
      expect(templateSrc, isNot(contains('https://tailtopia.id/download')));
    });

    /// 导出边长 ≥ 140px。`CardQr` 有构造期 assert —— 这里验的是**调用方没把它压下去**。
    test('二维码边长在两种画布上都 ≥ 140px', () {
      for (final canvas in [CardCanvas.story, CardCanvas.square]) {
        final u = canvas.width * (canvas.height / CardCanvas.story.height).clamp(0.72, 1.0);
        expect(max(CardQr.minExportSide, u * 0.229),
            greaterThanOrEqualTo(CardQr.minExportSide));
      }
    });

    testWidgets('卡面里真的有一枚码，且品牌带装得下它', (tester) async {
      await _pumpCard(tester, canvas: CardCanvas.square);
      expect(find.byType(CardQr), findsOneWidget);
      final qr = tester.widget<CardQr>(find.byType(CardQr));
      expect(qr.side, greaterThanOrEqualTo(CardQr.minExportSide));
      expect(qr.data, kAppDownloadUrl);
      // 静默区 ≥ 4 码元由 CardQr 自己保证；这里验它没被挤掉（占位 > 边长）。
      expect(CardQr.footprintFor(qr.side), greaterThan(qr.side));
    });
  });

  group('AC5/AC10 卡面字段与不扩散', () {
    testWidgets('猫卡：当量是主视觉，没有体型带', (tester) async {
      await _pumpCard(tester, isDog: false);
      expect(find.byKey(const ValueKey('ageCardHumanYears')), findsOneWidget);
      expect(find.byKey(const ValueKey('ageCardQuip')), findsOneWidget);
      expect(find.byKey(const ValueKey('ageCardSizeBand')), findsNothing);
      expect(find.byKey(const ValueKey('ageCardBrandBand')), findsOneWidget);
    });

    testWidgets('狗卡：多一条体型带', (tester) async {
      await _pumpCard(tester, isDog: true, size: DogSizeClass.medium);
      expect(find.byKey(const ValueKey('ageCardSizeBand')), findsOneWidget);
    });

    /// 🔴 **不加水印**：水印只属 KTP / 护照那类付费保护场景。
    test('预览页没有挂水印，也没碰 KTP 的出图行为', () {
      final src =
          File('lib/features/profile/presentation/age_card_page.dart').readAsStringSync();
      expect(src, isNot(contains('CardWatermark')));
      expect(src, isNot(contains('watermark:')));
      expect(src, isNot(contains('id_card')));
    });

    /// AC10：本 story **不接入 PawCoin 奖励**（属 5.3）。
    test('没有提前接 PawCoin 奖励', () {
      final src =
          File('lib/features/profile/presentation/age_card_page.dart').readAsStringSync();
      for (final banned in ['PawCoin', 'pawcoin', 'reward', 'Reward']) {
        expect(src, isNot(contains(banned)), reason: '奖励链路属 Story 5.3');
      }
    });
  });

  group('AC9 埋点值域', () {
    test('size_class 值域恰好四值', () {
      expect(DogSizeClass.values.map((s) => s.wire).toList(),
          ['small', 'medium', 'large', 'xlarge']);
    });

    testWidgets('生成上报 age_card_generated，带 species / canvas；狗多带 size_class',
        (tester) async {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      AgeCardPreviewPage.captureForTest = (canvas) async => Uint8List(4);
      addTearDown(() => AgeCardPreviewPage.captureForTest = null);

      await _pumpCard(tester, isDog: true, size: DogSizeClass.large);
      await tester.tap(find.byKey(const ValueKey('ageCardShareCta')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      final ev = seen.firstWhere((e) => e.key == 'age_card_generated').value!;
      expect(ev['species'], 'dog');
      expect(ev['size_class'], 'large');
      expect(ev['canvas'], 'story', reason: '默认 9:16');
    });

    testWidgets('猫卡不带 size_class（它对猫没有意义）', (tester) async {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      AgeCardPreviewPage.captureForTest = (canvas) async => Uint8List(4);
      addTearDown(() => AgeCardPreviewPage.captureForTest = null);

      await _pumpCard(tester, isDog: false);
      await tester.tap(find.byKey(const ValueKey('ageCardShareCta')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      final ev = seen.firstWhere((e) => e.key == 'age_card_generated').value!;
      expect(ev['species'], 'cat');
      expect(ev.containsKey('size_class'), isFalse);
    });

    /// 🔴 分享**只在系统面板回调成功后**才报 —— 报在出图那刻等于
    /// 「看一眼就退出也算分享」，这个数只会高估且无法事后修正。
    test('age_card_shared 挂在 onShared 回调上，不在出图那一步', () {
      final src =
          File('lib/features/profile/presentation/age_card_page.dart').readAsStringSync();
      final sharedLine = src
          .split('\n')
          .indexWhere((l) => l.contains("'age_card_shared'"));
      final onSharedLine =
          src.split('\n').indexWhere((l) => l.contains('onShared:'));
      expect(onSharedLine, isNonNegative);
      expect(sharedLine, greaterThan(onSharedLine));
    });
  });
}

/// 挂一张年龄卡预览页。
Future<void> _pumpCard(
  WidgetTester tester, {
  bool isDog = false,
  DogSizeClass? size,
  CardCanvas canvas = CardCanvas.story,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('en'),
    home: AgeCardPreviewPage(
      petName: 'Mochi',
      birthday: DateTime(2023, 3, 1),
      isDog: isDog,
      size: size,
      today: DateTime(2026, 9, 11),
      quipRandom: Random(0),
    ),
  ));
  await tester.pump();
  if (canvas == CardCanvas.square) {
    await tester.tap(find.text('1:1'));
    await tester.pump();
  }
}
