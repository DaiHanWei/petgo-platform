import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/diary_demo_data.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/diary_demo_detail_page.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/diary_header.dart';
import 'package:tailtopia/features/profile/presentation/widgets/timeline_item_tile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

/// Story 2.2 · L0：五类条目渲染组件 + 游客引导态 + 示例详情。
///
/// 重点锁三件事：
/// 1. **五类样式各自可辨**（AC3）——组件是游客示例与真实时间线的共用件，样式漂移就是 NFR-7 破功；
/// 2. **组件不内置跳转**——点击回调由调用方注入，注入什么就跳什么；
/// 3. **游客态零网络 + 引导收口**（AC4/AC8）——3 条带图进示例详情，其余 6 条与详情页互动一律建档引导。
Widget _wrapTile(Widget child) => ProviderScope(
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: ListView(children: [child])),
      ),
    );

/// 页面级测试用「加高画布」：默认 800px 高的测试窗口装不下 9 条示例本，
/// 列表懒加载会导致靠下的条目根本没被构建（点不到、也断言不到）。
/// 加高到 2400 逻辑像素后整页一次构建完，测试只关注行为不受视口高度干扰。
///
/// 宽度取 500 而非真机的 ~400：420 宽时**既有** [LoginHardDialog]（Story 1.4 组件）的
/// 「Lanjutkan dengan Google」按钮内 Row 会横向溢出 28px 而使测试失败 —— 那是本 Story 之外的
/// 既有布局问题（印尼语文案更长时更易触发），已单独记录，不在此顺手改。
Future<void> _pumpTall(WidgetTester tester, Widget child) async {
  await tester.binding.setSurfaceSize(const Size(500, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(_wrapPage(child));
  await tester.pumpAndSettle();
}

Widget _wrapPage(Widget child) => ProviderScope(
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: child,
      ),
    );

TimelineItem _item(TimelineItemType type,
        {String? milestoneCode,
        String? milestoneLevel,
        String? healthRecordType,
        String? idCardSerial,
        String? aiLevel,
        List<String> imageUrls = const []}) =>
    TimelineItem(
      kind: TimelineKind.unknown,
      itemType: type,
      date: DateTime.utc(2026, 5, 20, 9),
      milestoneCode: milestoneCode,
      milestoneLevel: milestoneLevel,
      healthRecordType: healthRecordType,
      idCardSerial: idCardSerial,
      aiLevel: aiLevel,
      imageUrls: imageUrls,
      text: 'Nyebur pertama kali',
    );

void main() {
  group('AC3 itemType 词表（前后端契约，Story 3.2 后端须采纳）', () {
    test('恰好五个取值，线格式为约定的 UPPER_SNAKE 字面量', () {
      expect(TimelineItemType.values.map((t) => t.wire).toList(), <String>[
        'HAPPY_MOMENT',
        'HAPPY_MOMENT_MILESTONE',
        'MILESTONE_BANNER',
        'HEALTH_RECORD',
        'ID_CARD_ISSUED',
      ]);
    });

    test('解析线格式；未知 / 缺失 → null（由 resolvedType 兜底）', () {
      expect(TimelineItemType.parse('MILESTONE_BANNER'), TimelineItemType.milestoneBanner);
      expect(TimelineItemType.parse('SOMETHING_NEW'), isNull);
      expect(TimelineItemType.parse(null), isNull);
    });

    test('3.2 上线前的过渡兜底：只有 kind 时映射到类 ① / ④', () {
      expect(
        TimelineItem(kind: TimelineKind.happyMoment, date: DateTime.utc(2026, 5, 1)).resolvedType,
        TimelineItemType.happyMoment,
      );
      expect(
        TimelineItem(kind: TimelineKind.healthEvent, date: DateTime.utc(2026, 5, 1)).resolvedType,
        TimelineItemType.healthRecord,
      );
    });

    test('itemType 从 JSON 解析（契约字段就位）', () {
      final item = TimelineItem.fromJson({
        'kind': 'HAPPY_MOMENT',
        'date': '2026-05-20T09:00:00Z',
        'itemType': 'HAPPY_MOMENT_MILESTONE',
        'milestoneCode': 'D-S13',
        'milestoneLevel': 'S',
        'healthRecordType': null,
        'idCardSerial': null,
      });
      expect(item.itemType, TimelineItemType.happyMomentMilestone);
      expect(item.milestoneCode, 'D-S13');
      expect(item.milestoneLevel, 'S');
    });
  });

  group('AC3 五类样式各自可辨（A6 稿基准）', () {
    testWidgets('① 普通照片卡：有卡片、无金徽章', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.happyMoment), petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineHappyCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineMilestoneStamp')), findsNothing);
      expect(find.byKey(const ValueKey('timelineMilestoneBanner')), findsNothing);
    });

    testWidgets('② 照片卡 + 金徽章角标，徽章文案取里程碑本地化短名', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.happyMomentMilestone, milestoneCode: 'D-S13'),
          petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineHappyCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineMilestoneStamp')), findsOneWidget);
      expect(find.textContaining('Berenang pertama'), findsOneWidget);
    });

    testWidgets('③ 通栏 banner：无照片卡、有等级角标，文案取庆祝文案（含宠物名）', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.milestoneBanner,
              milestoneCode: 'C-L2', milestoneLevel: 'L'),
          petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineMilestoneBanner')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineHappyCard')), findsNothing);
      expect(find.text('L'), findsOneWidget);
      expect(find.textContaining('100 hari bersama Mochi'), findsOneWidget);
    });

    testWidgets('④a 问诊条：粉底条 + 分诊等级徽章（沿用现状）', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.healthRecord,
              healthRecordType: 'CONSULT', aiLevel: 'GREEN'),
          petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineConsultRow')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineHealthCapsule')), findsNothing);
      expect(find.textContaining('Hijau'), findsOneWidget);
    });

    testWidgets('④b 结构化健康记录：矮胶囊 + Kesehatan 标签 + 类型图标取 FR-84 总表', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.healthRecord, healthRecordType: 'VACCINE'),
          petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineHealthCapsule')), findsOneWidget);
      expect(find.text('Kesehatan'), findsOneWidget);
      expect(find.byIcon(Icons.vaccines_outlined), findsOneWidget);
      // 胶囊明显矮于照片卡：照片卡缩略图 50px，胶囊整条高度应更小
      final capsule = tester.getSize(find.byKey(const ValueKey('timelineHealthCapsule')));
      expect(capsule.height < 50, isTrue, reason: '胶囊高度 ${capsule.height} 应小于照片卡缩略图 50');
    });

    testWidgets('⑤ 证件卡：编号有则显示，无则不渲染占位符', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.idCardIssued, idCardSerial: '#00842'),
          petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineIdCard')), findsOneWidget);
      expect(find.text('#00842'), findsOneWidget);
      expect(find.textContaining('Mochi'), findsOneWidget);

      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.idCardIssued), petName: 'Mochi')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('timelineIdCard')), findsOneWidget);
      expect(find.textContaining('#'), findsNothing);
    });
  });

  group('AC3 组件不内置跳转：点击回调由调用方注入', () {
    testWidgets('整条点击走 onTap；类② 徽章点击走 onBadgeTap（互不串台）', (tester) async {
      var taps = 0, badgeTaps = 0;
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
        item: _item(TimelineItemType.happyMomentMilestone, milestoneCode: 'D-S13'),
        petName: 'Mochi',
        onTap: () => taps++,
        onBadgeTap: () => badgeTaps++,
      )));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('timelineMilestoneStampTap')));
      await tester.pumpAndSettle();
      expect(badgeTaps, 1);
      expect(taps, 0, reason: '徽章是独立可点区域，不应同时触发整条回调');

      await tester.tap(find.byKey(const ValueKey('timelineHappyCard')));
      await tester.pumpAndSettle();
      expect(taps, 1);
    });

    testWidgets('不注入回调 → 该条不可点（组件自身没有任何跳转行为）', (tester) async {
      await tester.pumpWidget(_wrapTile(TimelineItemTile(
          item: _item(TimelineItemType.milestoneBanner,
              milestoneCode: 'C-S1', milestoneLevel: 'S'),
          petName: 'Mochi')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('timelineTileTap_MILESTONE_BANNER')), findsNothing);
    });
  });

  group('AC1/AC2/AC4 游客引导态', () {
    testWidgets('页面结构：示例标示 + 共用页头 + 9 条示例 + 情感化标题 + 唯一主 CTA', (tester) async {
      await _pumpTall(tester, const DiaryGuestPage());

      expect(find.byKey(const ValueKey('diaryDemoStrip')), findsOneWidget);
      expect(find.text('✨ Contoh'), findsOneWidget);
      // 页头与真实态同一组件
      expect(find.byType(DiaryHeader), findsOneWidget);
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('diaryHealthEntry')), findsOneWidget);
      expect(find.byKey(const ValueKey('diaryIdCardButton')), findsOneWidget);
      // 个性签名行（A1 pet-hero-intro）
      expect(find.textContaining('Tukang tidur'), findsOneWidget);
      // 主 CTA 常驻底部（不随滚动）
      expect(find.byKey(const ValueKey('diaryGuestPrimaryCta')), findsOneWidget);
      expect(find.byKey(const ValueKey('diaryGuestPitch')), findsOneWidget);
    });

    testWidgets('9 条示例混排五类，类别构成与 A1 稿一致', (tester) async {
      await _pumpTall(tester, const DiaryGuestPage());

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final items = DiaryDemoData.items(l10n);
      expect(items.length, 9);
      final counts = <TimelineItemType, int>{};
      for (final it in items) {
        counts[it.resolvedType] = (counts[it.resolvedType] ?? 0) + 1;
      }
      expect(counts[TimelineItemType.happyMoment], 2);
      expect(counts[TimelineItemType.happyMomentMilestone], 1);
      expect(counts[TimelineItemType.milestoneBanner], 3);
      expect(counts[TimelineItemType.healthRecord], 2);
      expect(counts[TimelineItemType.idCardIssued], 1);
      // 只有 3 条带图（#3 两张 + #7 + #8）；其余 6 条为默认卡片，不需要配图
      expect(items.where((it) => it.imageUrls.isNotEmpty).length, 3);
    });

    testWidgets('AC4 零网络：示例配图全部是内置 asset（无 http 源）', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final urls = [
        ...DiaryDemoData.items(l10n).expand((it) => it.imageUrls),
        DiaryDemoData.profile(l10n).avatarUrl!,
      ];
      expect(urls, isNotEmpty);
      for (final u in urls) {
        expect(u.startsWith('asset:assets/demo_diary/'), isTrue, reason: '非内置资源: $u');
      }
    });

    testWidgets('AC5 页内无任何滚动式 / 弹层式登录推荐：滚到底也不弹窗', (tester) async {
      await tester.pumpWidget(_wrapPage(const DiaryGuestPage()));
      await tester.pumpAndSettle();

      await tester.drag(find.byKey(const ValueKey('diaryGuestScroll')), const Offset(0, -2000));
      await tester.pumpAndSettle();
      await tester.drag(find.byKey(const ValueKey('diaryGuestScroll')), const Offset(0, -2000));
      await tester.pumpAndSettle();

      expect(find.byType(LoginHardDialog), findsNothing);
    });

    testWidgets('主 CTA 文案不含「登录」字样，点击才触发登录引导', (tester) async {
      await _pumpTall(tester, const DiaryGuestPage());

      final cta =
          tester.widget<FilledButton>(find.byKey(const ValueKey('diaryGuestPrimaryCta')));
      final label = ((cta.child as Text).data ?? '').toLowerCase();
      for (final banned in ['login', 'masuk', 'daftar', 'sign in']) {
        expect(label.contains(banned), isFalse, reason: 'CTA 文案不得出现登录字样: $label');
      }

      await tester.tap(find.byKey(const ValueKey('diaryGuestPrimaryCta')));
      await tester.pumpAndSettle();
      expect(find.byType(LoginHardDialog), findsOneWidget);
    });
  });

  group('印尼语 + 真机宽度：布局不溢出（印尼语文案更长，最易在窄屏爆行）', () {
    testWidgets('393×830（Pixel 级逻辑分辨率）下游客页与示例详情均无溢出', (tester) async {
      await tester.binding.setSurfaceSize(const Size(393, 830));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(_wrapPage(const DiaryGuestPage()));
      await tester.pumpAndSettle();
      // 滚到底把所有条目都构建一遍（溢出是布局期错误，构建到才会暴露）
      for (var i = 0; i < 4; i++) {
        await tester.drag(find.byKey(const ValueKey('diaryGuestScroll')), const Offset(0, -600));
        await tester.pumpAndSettle();
      }
      expect(tester.takeException(), isNull);

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final item = DiaryDemoData.items(l10n).firstWhere(DiaryDemoData.hasDemoDetail);
      await tester.pumpWidget(_wrapPage(DiaryDemoDetailPage(item: item)));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });

  group('AC8 示例详情与建档引导收口', () {
    test('只有带图的内容条目可进示例详情；其余 6 条不可', () async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final items = DiaryDemoData.items(l10n);
      expect(items.where(DiaryDemoData.hasDemoDetail).length, 3);
      for (final it in items.where((i) => !DiaryDemoData.hasDemoDetail(i))) {
        expect(it.imageUrls, isEmpty);
      }
    });

    testWidgets('点带图条目 → 进示例详情，且不弹登录窗', (tester) async {
      await _pumpTall(tester, const DiaryGuestPage());

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_HAPPY_MOMENT_MILESTONE')));
      await tester.pumpAndSettle();

      expect(find.byType(DiaryDemoDetailPage), findsOneWidget);
      expect(find.byType(LoginHardDialog), findsNothing);
      // 详情图文来自内置常量
      expect(find.byKey(const ValueKey('demoDetailCarousel')), findsOneWidget);
    });

    testWidgets('点 banner / 胶囊 / 证件卡 → 建档引导（不进任何受控页）', (tester) async {
      for (final key in const [
        'timelineTileTap_MILESTONE_BANNER',
        'timelineTileTap_HEALTH_RECORD',
        'timelineTileTap_ID_CARD_ISSUED',
      ]) {
        await _pumpTall(tester, const DiaryGuestPage());

        await tester.tap(find.byKey(ValueKey(key)).first);
        await tester.pumpAndSettle();

        expect(find.byType(LoginHardDialog), findsOneWidget, reason: '$key 应触发建档引导');
        expect(find.byType(DiaryDemoDetailPage), findsNothing);
        // 点遮罩收起：强登录窗有并发单例守卫，不关掉的话下一轮会被守卫吞掉（不是实现缺陷）。
        await tester.tapAt(const Offset(4, 4));
        await tester.pumpAndSettle();
      }
    });

    testWidgets('详情页三个互动按钮都在，点击一律触发建档引导（不执行真实互动）', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final item = DiaryDemoData.items(l10n).firstWhere(DiaryDemoData.hasDemoDetail);

      for (final tap in <Future<void> Function(WidgetTester)>[
        (t) => t.tap(find.byKey(const ValueKey('demoDetailLike'))),
        (t) => t.tap(find.byKey(const ValueKey('demoDetailComment'))),
        (t) async {
          await t.tap(find.byKey(const ValueKey('demoDetailMenu')));
          await t.pumpAndSettle();
          await t.tap(find.byKey(const ValueKey('demoDetailMenuReport')));
        },
      ]) {
        await _pumpTall(tester, DiaryDemoDetailPage(item: item));

        // 三个入口都可见
        expect(find.byKey(const ValueKey('demoDetailLike')), findsOneWidget);
        expect(find.byKey(const ValueKey('demoDetailComment')), findsOneWidget);
        expect(find.byKey(const ValueKey('demoDetailMenu')), findsOneWidget);

        await tap(tester);
        await tester.pumpAndSettle();
        expect(find.byType(LoginHardDialog), findsOneWidget);
        await tester.tapAt(const Offset(4, 4));
        await tester.pumpAndSettle();
      }
    });
  });
}
