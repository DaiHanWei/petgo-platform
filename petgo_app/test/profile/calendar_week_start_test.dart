import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/presentation/widgets/archive_calendar.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 日历表头与**周起始日**（V1.1.2 · UI 稿 A7；2026-08-04 用户拍板「周日起头」）。
///
/// 此前整行星期名缺失，「1 号落在第几列」全靠数格子。补表头时起始日必须与网格的
/// 前置空格数同源 —— 一处改一处不改就会整列错位，这是本文件唯一要守住的东西。
void main() {
  Future<void> pump(WidgetTester tester, {required String locale}) async {
    final now = DateTime.now();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        calendarMonthProvider.overrideWith((ref, ym) async =>
            CalendarMonth(year: now.year, month: now.month, days: const [])),
      ],
      child: MaterialApp(
        locale: Locale(locale),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: ArchiveCalendar(onOpenDay: (_) {}, onAddOnDate: (_) {}),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  /// 表头一行 7 个窄写星期名，按屏幕从左到右取。
  List<String> weekdayLabels(WidgetTester tester) {
    final row = find.byKey(const ValueKey('calWeekdayRow'));
    final texts = find.descendant(of: row, matching: find.byType(Text));
    return tester.widgetList<Text>(texts).map((t) => t.data ?? '').toList();
  }

  testWidgets('表头存在且是 7 列', (tester) async {
    await pump(tester, locale: 'id');
    expect(weekdayLabels(tester).length, 7);
  });

  testWidgets('印尼语按周日起头 → M S S R K J S（Minggu 打头，与 A7 稿一致）', (tester) async {
    await pump(tester, locale: 'id');
    expect(weekdayLabels(tester), ['M', 'S', 'S', 'R', 'K', 'J', 'S']);
  });

  testWidgets('英文同样按周日起头 → S M T W T F S', (tester) async {
    await pump(tester, locale: 'en');
    expect(weekdayLabels(tester), ['S', 'M', 'T', 'W', 'T', 'F', 'S']);
  });

  testWidgets('网格前置空格数 = 当月 1 号在「周日起头」下的列号（表头与日期不得错位）',
      (tester) async {
    await pump(tester, locale: 'id');

    final first = DateTime(DateTime.now().year, DateTime.now().month, 1);
    // 周日起头：Dart 的 weekday Mon=1…Sun=7 → 取模 7 即列号（周日=0）。
    final expectedColumn = first.weekday % 7;

    // 网格是 7 列 GridView：1 号格子的下标 = 前置空格数 = 它所在列号。
    final grid = tester.widget<GridView>(find.byType(GridView));
    final children = (grid.childrenDelegate as SliverChildListDelegate).children;
    final firstDayIndex = children.indexWhere((w) => w is! SizedBox);

    expect(firstDayIndex, expectedColumn,
        reason: '前置空格与表头起始日必须同源，否则整列错位');
  });
}
