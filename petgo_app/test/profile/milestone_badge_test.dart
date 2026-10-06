import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_badge_assets.dart';
import 'package:tailtopia/features/profile/domain/milestone_titles.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';

/// V1.3.2 Story 5.1：里程碑徽章映射表与公共组件（L0）。
void main() {
  group('AC1 映射表：按完整 code 精确寻址', () {
    test('78 个 code 各一条、恰 40 个语义键，code 集合 == 标题表全集', () {
      expect(kMilestoneBadgeKeys, hasLength(78));
      expect(kMilestoneBadgeKeys.values.toSet(), hasLength(40));
      expect(kMilestoneBadgeKeys.keys.toSet(), kMilestoneTitles.keys.toSet());
      for (final v in kMilestoneBadgeKeys.values) {
        expect(RegExp(r'^[a-z0-9_]+$').hasMatch(v), isTrue, reason: v);
      }
      expect(kMilestoneBadgeKeys.values, isNot(contains(kMilestoneBadgeLockedKey)));
    });

    test('🔴 不按后缀寻址：G-S8（点赞）≠ C-S8（零食）；C-S8 == G-S6', () {
      expect(milestoneBadgeKeyOf('G-S8'), isNot(milestoneBadgeKeyOf('C-S8')));
      expect(milestoneBadgeKeyOf('C-S8'), milestoneBadgeKeyOf('G-S6'));
      expect(milestoneBadgeKeyOf('C-S8'), 'first_treat');
      expect(milestoneBadgeKeyOf('G-S8'), 'first_like');
    });

    test('三组跨物种共用：C-S8/D-S8/G-S6、C-M5/D-M5/G-M1、C-S16/D-S16/G-S9', () {
      for (final group in [
        ['C-S8', 'D-S8', 'G-S6'],
        ['C-M5', 'D-M5', 'G-M1'],
        ['C-S16', 'D-S16', 'G-S9'],
      ]) {
        expect(group.map(milestoneBadgeKeyOf).toSet(), hasLength(1), reason: '$group');
      }
    });

    test('共用一个语义键的 code，标题（id）也相同 —— 映射不会把两件事并成一枚', () {
      final byKey = <String, Set<String>>{};
      kMilestoneBadgeKeys.forEach((code, key) => (byKey[key] ??= {}).add(kMilestoneTitles[code]!.id));
      byKey.forEach((key, titles) => expect(titles, hasLength(1), reason: key));
    });

    test('不在表里的 code → null', () {
      for (final c in ['X-S8', 'C-S99', '']) {
        expect(milestoneBadgeKeyOf(c), isNull, reason: c);
      }
    });

    test('路径：assets/milestone/<键>.webp', () {
      expect(milestoneBadgeAssetPath('first_treat'), 'assets/milestone/first_treat.webp');
    });

    test('源码扫描：映射与组件不做任何字符串切分 / 正则', () {
      for (final path in [
        'lib/features/profile/domain/milestone_badge_assets.dart',
        'lib/features/profile/presentation/widgets/milestone_badge.dart',
      ]) {
        final code = File(path)
            .readAsLinesSync()
            .where((l) => !l.trimLeft().startsWith('//'))
            .join('\n');
        for (final banned in ['substring(', 'split(', 'endsWith(', 'startsWith(', 'RegExp(']) {
          expect(code, isNot(contains(banned)), reason: '$path: $banned');
        }
      }
    });

    test('源码扫描：assets/milestone/ 只在映射文件里出现（没有大小两套路径）', () {
      final hits = Directory('lib')
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart') && f.readAsStringSync().contains('assets/milestone/'))
          .map((f) => f.path.replaceAll('\\', '/'))
          .toList();
      expect(hits, ['lib/features/profile/domain/milestone_badge_assets.dart']);
    });
  });

  group('AC5 素材目录', () {
    test('目录存在、pubspec 已声明', () {
      expect(Directory('assets/milestone').existsSync(), isTrue);
      expect(File('pubspec.yaml').readAsStringSync(), contains('- assets/milestone/'));
    });

    test('每个 .webp 文件名都是某个语义键或 locked（防拼错的孤儿文件）', () {
      final allowed = {...kMilestoneBadgeKeys.values, kMilestoneBadgeLockedKey};
      final orphans = Directory('assets/milestone')
          .listSync()
          .whereType<File>()
          .map((f) => f.uri.pathSegments.last)
          .where((n) => n.endsWith('.webp'))
          .map((n) => n.substring(0, n.length - '.webp'.length))
          .where((k) => !allowed.contains(k))
          .toList();
      expect(orphans, isEmpty);
    });
  });

  group('AC2 MilestoneBadge 组件', () {
    tearDown(() => MilestoneBadgeAssets.debugOverride = null);

    Future<void> pump(WidgetTester tester, Widget badge) =>
        tester.pumpWidget(MaterialApp(home: Scaffold(body: Center(child: badge))));

    testWidgets('有素材 → Image，路径 = 语义键路径；共用语义的三个 code 解析出同一路径', (tester) async {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/first_treat.webp'};
      final names = <String>{};
      for (final code in ['C-S8', 'D-S8', 'G-S6']) {
        await pump(tester, MilestoneBadge(code: code, size: 64));
        final img = tester.widget<Image>(find.byKey(ValueKey('milestoneBadgeArt_$code')));
        names.add((img.image as AssetImage).assetName);
        expect(img.width, 64);
      }
      expect(names, {'assets/milestone/first_treat.webp'});
    });

    testWidgets('无素材 → 默认「奖杯 + 级别色圆底」', (tester) async {
      MilestoneBadgeAssets.debugOverride = const {};
      await pump(tester, const MilestoneBadge(code: 'C-S8', size: 64, level: MilestoneLevel.l));
      expect(find.byIcon(Icons.emoji_events_rounded), findsOneWidget);
      expect(find.byType(Image), findsNothing);
    });

    testWidgets('locked 无锁定图 → 锁图标；🔴 有该枚真图也不读它', (tester) async {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/first_treat.webp'};
      await pump(tester, const MilestoneBadge(code: 'C-S8', size: 64, locked: true));
      expect(find.byIcon(Icons.lock_outline_rounded), findsOneWidget);
      expect(find.byType(Image), findsNothing);
    });

    testWidgets('locked 有锁定图 → 全局锁定图', (tester) async {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/locked.webp'};
      await pump(tester, const MilestoneBadge(code: 'C-S8', size: 64, locked: true));
      final img = tester.widget<Image>(find.byType(Image));
      expect((img.image as AssetImage).assetName, 'assets/milestone/locked.webp');
    });

    testWidgets('传 fallback / lockedFallback → 无素材时渲染它们', (tester) async {
      MilestoneBadgeAssets.debugOverride = const {};
      await pump(tester, MilestoneBadge(code: 'C-S8', size: 34, fallback: (_) => const Text('🎉')));
      expect(find.text('🎉'), findsOneWidget);
      await pump(tester,
          MilestoneBadge(code: 'C-S8', size: 34, locked: true, lockedFallback: (_) => const Text('locked!')));
      expect(find.text('locked!'), findsOneWidget);
    });

    test('hasArtFor：按完整 code 查语义键路径', () {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/first_treat.webp'};
      expect(MilestoneBadgeAssets.hasArtFor('G-S6'), isTrue);
      expect(MilestoneBadgeAssets.hasArtFor('G-S8'), isFalse);
      expect(MilestoneBadgeAssets.hasArtFor('PET_BIRTHDAY'), isFalse);
    });

    testWidgets('不在表里的 code（如生日节点的 targetRef）→ 回落', (tester) async {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/first_treat.webp'};
      await pump(tester, MilestoneBadge(code: 'PET_BIRTHDAY', size: 32, fallback: (_) => const Icon(Icons.cake)));
      expect(find.byIcon(Icons.cake), findsOneWidget);
    });
  });
}
