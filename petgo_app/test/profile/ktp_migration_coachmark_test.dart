import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/onboarding_mark_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/coachmark_overlay.dart';

/// V1.3.0 批次 A · Story 5.4（L0）：迁移引导蒙层与引导标记（FR-65 · AD-A21）。
///
/// 「聚光区是不是真的更亮」只能真机看（AC5 的 L2 部分）。这里钉住 L0 能钉的：
/// **挖孔不用超大 box-shadow**、**分块遮罩不重叠**、**离线降级按未看过**、
/// 以及**本批次只有一个键**。
void main() {
  final String overlaySrc =
      File('lib/shared/widgets/coachmark_overlay.dart').readAsStringSync();

  group('AC5 🔴 聚光挖孔的实现禁忌', () {
    /// 🔴 UI 稿第四步实测：3000px 的 box-shadow 叠在另一层半透明遮罩上会**双重叠加** ——
    /// 遮罩比设计意图更暗，而聚光区**并没有变亮**（它只是"没有再暗一层"）。
    test('没有用超大 box-shadow 挖孔', () {
      final code = overlaySrc
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('///') && !l.trimLeft().startsWith('//'))
          .join('\n');

      expect(code, isNot(contains('BoxShadow')));
      expect(code, isNot(contains('boxShadow')));
      expect(code, isNot(contains('spreadRadius')));
    });

    /// 采用**分块遮罩**：高亮框那一格什么都不画，所以聚光区是原样的页面。
    testWidgets('遮罩是四块，高亮区不被任何一块覆盖', (tester) async {
      const hole = Rect.fromLTWH(100, 200, 120, 60);
      await _pumpOverlay(tester, hole);

      final scrims = find.byKey(const ValueKey('coachmarkScrim'));
      expect(scrims, findsNWidgets(4), reason: '上 / 下 / 左 / 右各一块');

      // 逐块验：没有任何一块盖住高亮区。
      for (var i = 0; i < 4; i++) {
        final rect = tester.getRect(scrims.at(i));
        expect(rect.overlaps(hole), isFalse,
            reason: '遮罩 $rect 盖住了聚光区 $hole —— 聚光区必须是原样的页面');
      }
    });

    /// 🔴 四块之间也**互不重叠** —— 重叠处会比别处更暗，正是"双重叠加"的另一种形态。
    testWidgets('四块遮罩两两不重叠', (tester) async {
      await _pumpOverlay(tester, const Rect.fromLTWH(100, 200, 120, 60));

      final scrims = find.byKey(const ValueKey('coachmarkScrim'));
      final rects = [for (var i = 0; i < 4; i++) tester.getRect(scrims.at(i))];

      for (var i = 0; i < rects.length; i++) {
        for (var j = i + 1; j < rects.length; j++) {
          final overlap = rects[i].intersect(rects[j]);
          expect(overlap.width <= 0 || overlap.height <= 0, isTrue,
              reason: '${rects[i]} 与 ${rects[j]} 重叠 → 那一块会比别处更暗');
        }
      }
    });

    /// UI 稿同一处踩到的第二个坑：flex 容器里的文字与按钮被同容器内的绝对定位兄弟挡住。
    /// 这里说明卡是 Stack 的**最后一个兄弟**（后来者在上），必须可见可点。
    testWidgets('说明卡与「知道了」不被遮罩挡住', (tester) async {
      var dismissed = 0;
      await _pumpOverlay(tester, const Rect.fromLTWH(100, 200, 120, 60),
          onDismiss: () => dismissed++);

      expect(find.byKey(const ValueKey('coachmarkCard')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('coachmarkGotIt')));
      await tester.pump();

      expect(dismissed, 1);
    });

    /// 点遮罩也能关 —— 一次性告知不该把人困在一层黑幕里。
    testWidgets('点遮罩也关', (tester) async {
      var dismissed = 0;
      await _pumpOverlay(tester, const Rect.fromLTWH(100, 200, 120, 60),
          onDismiss: () => dismissed++);

      await tester.tapAt(const Offset(20, 20)); // 高亮区之外
      await tester.pump();

      expect(dismissed, 1);
    });

    /// 高亮区贴边（入口卡靠屏幕右侧）时，被压成 0 宽的那块不该画成负尺寸。
    testWidgets('高亮区贴边不崩', (tester) async {
      await _pumpOverlay(tester, const Rect.fromLTWH(0, 0, 800, 100));
      expect(tester.takeException(), isNull);
    });

    /// 说明卡挂在高亮区下方；下方放不下时翻到上方 —— 否则它会被挤出屏幕。
    testWidgets('高亮区在屏幕底部时，说明卡翻到上方', (tester) async {
      await _pumpOverlay(tester, const Rect.fromLTWH(100, 520, 120, 60));

      final card = tester.getRect(find.byKey(const ValueKey('coachmarkCard')));
      expect(card.bottom, lessThanOrEqualTo(520 + 1));
    });
  });

  group('AC4/AC6 标记：离线降级与键的隔离', () {
    /// 🔴 **读不到一律按「未看过」处理**（AC4）：离线首启、接口失败都走这条。
    /// 多弹一次是已接受的代价；而"读失败就当看过"会让引导对一批人**永远不出现**。
    testWidgets('取标记失败 → 按空集合处理（即未看过）', (tester) async {
      final container = ProviderContainer(overrides: [
        onboardingMarkRepositoryProvider.overrideWithValue(_ThrowingRepo()),
      ]);
      addTearDown(container.dispose);

      final marks = await container.read(onboardingMarksProvider.future);

      expect(marks, isEmpty);
      expect(marks.contains(kOnboardingMarkKtpMoved), isFalse);
    });

    /// 🔴 本批次只有一个键。批次 C 的性格测试引导**必须另起一个键** ——
    /// 共用会让看过第一次的人再也收不到第二次（PRD 明确那是两次独立触发）。
    test('客户端侧的键与服务端登记的一致，且只有这一个', () {
      expect(kOnboardingMarkKtpMoved, 'ktp_moved');

      final repoSrc =
          File('lib/features/profile/data/onboarding_mark_repository.dart').readAsStringSync();
      final keys = RegExp(r"const String kOnboardingMark\w+ = '([a-z_]+)';")
          .allMatches(repoSrc)
          .map((m) => m.group(1))
          .toList();
      expect(keys, ['ktp_moved'], reason: '批次 C 加键时这条会红 —— 那是有意的');
    });

    /// 服务端的键登记表（枚举）与客户端常量必须是同一个字符串 ——
    /// 两边各写一份字面量，错一个字母就是"引导永远不置位"。
    test('与后端 OnboardingMarkKey 的线格式一致', () {
      final backend = File('../petgo-backend/src/main/java/com/tailtopia/onboarding/'
              'domain/OnboardingMarkKey.java')
          .readAsStringSync();
      expect(backend, contains('KTP_MOVED("$kOnboardingMarkKtpMoved")'));
    });
  });
}

/// 离线：取标记必失败。
class _ThrowingRepo implements OnboardingMarkRepository {
  // 只实现对外的两个方法；私有的 _ref 不属于接口面，测试替身用不到它。
  @override
  Future<Set<String>> fetchMarks() async => throw Exception('offline');

  @override
  Future<void> mark(String key) async {}

  @override
  noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<void> _pumpOverlay(
  WidgetTester tester,
  Rect spotlight, {
  VoidCallback? onDismiss,
}) async {
  final l10n = await AppLocalizations.delegate.load(const Locale('en'));
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('en'),
    home: CoachmarkOverlay(
      spotlight: spotlight,
      text: l10n.ktpMovedCoachmark,
      confirmLabel: l10n.commonGotIt,
      onDismiss: onDismiss ?? () {},
    ),
  ));
  await tester.pumpAndSettle();
}
