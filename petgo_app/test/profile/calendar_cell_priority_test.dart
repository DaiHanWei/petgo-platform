import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/health_record_icons.dart';
import 'package:tailtopia/features/profile/presentation/widgets/archive_calendar.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.4 · L0：日历格子「三大类取其一、只显一个标记」+ 纯文字日记错显 🏥 的修复。
///
/// ⚠️ 本文件同时是 **AD-16 的守门人**：日历与时间线的优先级方向相反（日历 diary 优先、时间线
/// 健康记录优先）**是刻意设计**。若有人为了「统一」把日历也改成健康优先，`
/// diaryImageWins_evenWhenDayAlsoHasHealthRecord` 会红 —— 那不是缺陷，别改断言。
void main() {
  // 日历默认显示**当月**，且未来日期渲染为不可点空格 —— 测试数据必须落在当月且不晚于今天。
  // 统一放在「1 号」：无论今天几号都成立。
  final now = DateTime.now();

  Future<void> pump(WidgetTester tester, List<CalendarDayCell> cells) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        calendarMonthProvider.overrideWith((ref, ym) async =>
            CalendarMonth(year: now.year, month: now.month, days: cells)),
      ],
      child: MaterialApp(
        locale: const Locale('id'),
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

  group('AC2 格子优先级：只显一个标记', () {
    testWidgets('② 有 diary 但全无图（纯文字日记）→ 通用 diary 标记，**不显问诊图标**', (tester) async {
      await pump(tester, const [CalendarDayCell(day: 1, hasHappyMoment: true)]);

      expect(find.byIcon(kDiaryGenericIcon.icon), findsOneWidget);
      expect(find.byIcon(Icons.local_hospital_outlined), findsNothing,
          reason: 'AC3 现网缺陷：纯文字日记曾错显 🏥，任何情况下都不得回退问诊图标');
    });

    testWidgets('③ 无 diary 有问诊 → 问诊图标（图标而非 emoji）', (tester) async {
      await pump(tester, const [CalendarDayCell(day: 1, hasHealthEvent: true)]);

      expect(find.byIcon(Icons.local_hospital_outlined), findsOneWidget);
      expect(find.text('🏥'), findsNothing, reason: 'FR-84：不得用字面 emoji（无法着色 / 老安卓豆腐块）');
    });

    testWidgets('④ 只有结构化健康记录：单条 → 类型图标', (tester) async {
      await pump(tester,
          const [CalendarDayCell(day: 1, healthRecordType: 'VACCINE', healthRecordCount: 1)]);

      expect(find.byIcon(Icons.vaccines_outlined), findsOneWidget);
      expect(find.byIcon(kHealthRecordGenericIcon.icon), findsNothing);
    });

    testWidgets('④ 多条 → 通用医疗箱（不可用 💊，驱虫已占用）', (tester) async {
      await pump(tester,
          const [CalendarDayCell(day: 1, healthRecordType: 'VACCINE', healthRecordCount: 3)]);

      expect(find.byIcon(kHealthRecordGenericIcon.icon), findsOneWidget);
      expect(kHealthRecordGenericIcon.icon, Icons.medical_services_outlined);
      expect(find.byIcon(Icons.vaccines_outlined), findsNothing);
      expect(find.byIcon(Icons.medication_outlined), findsNothing);
    });

    testWidgets('同一天既有纯文字 diary 又有问诊 + 健康记录 → 只出 diary 一个标记', (tester) async {
      await pump(tester, const [
        CalendarDayCell(
            day: 1,
            hasHappyMoment: true,
            hasHealthEvent: true,
            healthRecordType: 'VACCINE',
            healthRecordCount: 2)
      ]);

      expect(find.byIcon(kDiaryGenericIcon.icon), findsOneWidget);
      expect(find.byIcon(Icons.local_hospital_outlined), findsNothing);
      expect(find.byIcon(kHealthRecordGenericIcon.icon), findsNothing);
      expect(find.byIcon(Icons.vaccines_outlined), findsNothing);
    });
  });

  group('AC6 与时间线的优先级不对称是刻意的（AD-16）', () {
    testWidgets('diaryImageWins_evenWhenDayAlsoHasHealthRecord', (tester) async {
      // 日历：整天取一个代表标记 → 日记首图胜出、**不叠任何角标**。
      // 时间线同一天会出两条（照片卡 + 健康胶囊）——方向相反，刻意不对齐，别为「统一」改这里。
      await pump(tester, const [
        CalendarDayCell(
            day: 1,
            firstImageUrl: 'asset:assets/demo_diary/demo_diary_night.jpg',
            hasHappyMoment: true,
            healthRecordType: 'VACCINE',
            healthRecordCount: 1)
      ]);

      // 图铺满格子；健康记录的分类图标不作为角标出现
      expect(find.byIcon(Icons.vaccines_outlined), findsNothing);
      expect(find.byIcon(kHealthRecordGenericIcon.icon), findsNothing);
      expect(find.byIcon(Icons.local_hospital_outlined), findsNothing);
    });
  });

  group('AC4 图标总表（全项目一份）', () {
    test('月经按 FR-84 改为实心水滴 + 红色（同时作用于健康记录列表页）', () {
      final m = healthRecordIconFor('MENSTRUATION');
      expect(m.icon, Icons.water_drop, reason: 'FR-84：实心水滴，覆盖 V1.1.0 的描边版');
      expect(m.color, isNot(equals(const Color(0xFF5B9BD5))), reason: '不再是 infoBlue');
    });

    test('两个通用标记就位，且未与既有图标撞车', () {
      expect(kHealthRecordGenericIcon.icon, Icons.medical_services_outlined);
      expect(kDiaryGenericIcon.icon, Icons.edit_note_outlined);
      // 通用健康图标不得撞驱虫的药丸
      expect(kHealthRecordGenericIcon.icon, isNot(equals(Icons.medication_outlined)));
      final used = kHealthRecordIcons.values.map((e) => e.icon).toSet();
      expect(used.contains(kHealthRecordGenericIcon.icon), isFalse);
      expect(used.contains(kDiaryGenericIcon.icon), isFalse);
    });
  });
}
