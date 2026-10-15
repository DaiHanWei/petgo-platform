import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone_badge_assets.dart';
import 'package:tailtopia/features/profile/domain/milestone_celebration_copy.dart';
import 'package:tailtopia/features/profile/domain/milestone_checkin_prompt_copy.dart';
import 'package:tailtopia/features/profile/domain/milestone_titles.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';

/// V1.3.4 Story 4.1（FR-126 · D-12 / D-15）：「第一次场所打卡」「解锁性格测试结果」两项的
/// 标题 / 庆祝文案 / 说明文案 / 徽章映射（L0）。
void main() {
  const checkinCodes = ['C-S17', 'D-S17', 'G-S10'];
  const tailsonalityCodes = ['C-S18', 'D-S18', 'G-S11'];

  group('kMilestoneTitles', () {
    test('打卡三条', () {
      for (final c in checkinCodes) {
        expect(kMilestoneTitles[c]?.en, 'First check-in', reason: c);
        expect(kMilestoneTitles[c]?.id, 'Check-in pertama', reason: c);
      }
    });
    test('性格测试三条', () {
      for (final c in tailsonalityCodes) {
        expect(kMilestoneTitles[c]?.en, 'Personality revealed', reason: c);
        expect(kMilestoneTitles[c]?.id, 'Kenal kepribadian', reason: c);
      }
    });
  });

  group('kMilestoneCelebrationCopy（D-12）', () {
    test('打卡三条四字段 + 标题以 📍 结尾', () {
      for (final c in checkinCodes) {
        final copy = kMilestoneCelebrationCopy[c]!;
        expect(copy.titleId, 'Check-in pertama {name}! 📍', reason: c);
        expect(copy.bodyId, '{name} resmi jadi anabul jalan-jalan.', reason: c);
        expect(copy.titleEn, "{name}'s first check-in! 📍", reason: c);
        expect(copy.bodyEn, '{name} is officially a pet on the go.', reason: c);
        expect(copy.titleId.endsWith('📍'), isTrue, reason: c);
        expect(copy.titleEn.endsWith('📍'), isTrue, reason: c);
      }
    });
    test('性格测试三条四字段 + 标题以 🔮 结尾', () {
      for (final c in tailsonalityCodes) {
        final copy = kMilestoneCelebrationCopy[c]!;
        expect(copy.titleId, '{name} sudah kenal dirinya! 🔮', reason: c);
        expect(copy.bodyId, 'Kepribadian {name} sudah terbuka.', reason: c);
        expect(copy.titleEn, '{name} knows who they are now! 🔮', reason: c);
        expect(copy.bodyEn, "{name}'s personality is unlocked.", reason: c);
        expect(copy.titleId.endsWith('🔮'), isTrue, reason: c);
        expect(copy.titleEn.endsWith('🔮'), isTrue, reason: c);
      }
    });
    test('localizedMilestoneCelebration 替换宠物名', () {
      expect(localizedMilestoneCelebration('C-S17', const Locale('id'), 'Momo'),
          (title: 'Check-in pertama Momo! 📍', body: 'Momo resmi jadi anabul jalan-jalan.'));
      expect(localizedMilestoneCelebration('G-S11', const Locale('en'), 'Momo'),
          (title: 'Momo knows who they are now! 🔮', body: "Momo's personality is unlocked."));
    });
  });

  group('kMilestoneCheckinPromptCopy（D-15）', () {
    test('打卡说明（id），header 为空', () {
      final p = localizedMilestoneCheckinPrompt('D-S17', const Locale('id'), 'Momo');
      expect(p.body, 'Otomatis selesai saat Momo pertama kali check-in di sebuah tempat.');
      expect(p.header, isEmpty);
    });
    test('性格测试说明（en），header 为空', () {
      final p = localizedMilestoneCheckinPrompt('C-S18', const Locale('en'), 'Momo');
      expect(p.body, "Automatically completed when Momo's full personality result is unlocked.");
      expect(p.header, isEmpty);
    });
    test('六个 code 均已配说明', () {
      for (final c in [...checkinCodes, ...tailsonalityCodes]) {
        expect(localizedMilestoneCheckinPrompt(c, const Locale('id'), 'Momo').body, isNotEmpty, reason: c);
        expect(localizedMilestoneCheckinPrompt(c, const Locale('en'), 'Momo').body, isNotEmpty, reason: c);
      }
    });
  });

  group('徽章映射与素材', () {
    test('语义键 + 素材文件存在', () {
      for (final c in checkinCodes) {
        expect(milestoneBadgeKeyOf(c), 'place_checkin', reason: c);
      }
      for (final c in tailsonalityCodes) {
        expect(milestoneBadgeKeyOf(c), 'tailsonality', reason: c);
      }
      expect(File('assets/milestone/place_checkin.webp').existsSync(), isTrue);
      expect(File('assets/milestone/tailsonality.webp').existsSync(), isTrue);
    });
  });

  group('MilestoneBadge 组件', () {
    tearDown(() => MilestoneBadgeAssets.debugOverride = null);

    Future<void> pump(WidgetTester tester, Widget badge) =>
        tester.pumpWidget(MaterialApp(home: Scaffold(body: Center(child: badge))));

    testWidgets('C-S17 有素材 → 渲染 place_checkin.webp', (tester) async {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/place_checkin.webp'};
      await pump(tester, const MilestoneBadge(code: 'C-S17', size: 64));
      final img = tester.widget<Image>(find.byKey(const ValueKey('milestoneBadgeArt_C-S17')));
      expect((img.image as AssetImage).assetName, 'assets/milestone/place_checkin.webp');
    });

    testWidgets('locked → 不读真图', (tester) async {
      MilestoneBadgeAssets.debugOverride = {'assets/milestone/place_checkin.webp'};
      await pump(tester, const MilestoneBadge(code: 'C-S17', size: 64, locked: true));
      expect(find.byIcon(Icons.lock_outline_rounded), findsOneWidget);
      expect(find.byType(Image), findsNothing);
    });
  });
}
