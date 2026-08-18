import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/domain/visitor_profile.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.6 Story 2.3 · L0/widget：访客只读态渲染（AD-2 Rule 5 · AD-3 · AD-4）。
///
/// 这组测试守的是**减法有没有真做**。
/// 「访客看不到编辑按钮」这种事，人工点一遍很容易漏 ——
/// 而漏掉的后果不是不好看：**身份证入口跳的是当前登录用户自己的卡**，
/// 访客点进去要么看到自己的宠物、要么直接出错。
///
/// ⚠️ 特别注意「不渲染」与「不可点」的区别：把回调传 null 只会让按钮变灰、图标仍在，
/// 那不算移除（AD-2 Rule 5 要求两张入口卡**整块移除**）。下面用 `findsNothing` 钉死。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

const String _token = 'SHARE-TOK';

const VisitorProfile _profile = VisitorProfile(
  name: 'Mochi',
  petType: 'CAT',
  breed: 'Kucing Domestik',
  intro: 'Suka tidur',
  ownerNickname: 'Rina',
);

TimelineItem _moment({required int postId, required bool openable, String? text}) => TimelineItem(
      kind: TimelineKind.happyMoment,
      date: DateTime(2026, 5, 12),
      eventDate: DateTime(2026, 5, 12),
      postId: postId,
      imageUrls: const [],
      text: text ?? 'catatan $postId',
      itemType: TimelineItemType.happyMoment,
      openable: openable,
    );

Widget _wrap({
  required AuthState auth,
  List<TimelineItem> items = const [],
  VisitorProfile profile = _profile,
}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      visitorProfileProvider(_token).overrideWith((ref) async => profile),
      visitorStatsProvider(_token).overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 24, consultCount: 3, milestoneCompleted: 7, milestoneTotal: 30)),
      visitorTimelineProvider(_token).overrideWith((ref) async => TimelinePage(items: items)),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GrowthArchivePage(visitorToken: _token),
    ),
  );
}

void main() {
  group('AC2 减法：作者专属入口一个都不能留', () {
    /// 🛡 本组最要紧的一条。
    ///
    /// 身份证入口在作者态跳 `/profile/id-card` —— 那是**当前登录用户自己的**身份证页。
    /// 访客点进去：已登录的看到自己的宠物（莫名其妙），未登录的撞上登录墙。
    /// 「留个锁」解决不了，必须整块不渲染。
    testWidgets('访客态不渲染：编辑铅笔 · 健康记录入口 · 身份证入口 · 分享按钮', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('editProfileButton')), findsNothing,
          reason: '编辑铅笔仍在 —— 注意传 null 只是变灰，要的是不渲染');
      expect(find.byKey(const ValueKey('diaryHealthEntry')), findsNothing,
          reason: '健康记录入口仍在 —— 健康数据整块不对访客开放');
      expect(find.byKey(const ValueKey('diaryIdCardButton')), findsNothing,
          reason: '身份证入口仍在 —— 它跳的是当前登录用户自己的卡，访客点进去必然出错');
      expect(find.byKey(const ValueKey('shareFab')), findsNothing,
          reason: '分享入口仍在 —— 访客不得二次转发');
    });

    testWidgets('该保留的都在：统计条三列 · 里程碑进度 · 来源横幅（无视图切换）', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('archiveMilestoneBar')), findsOneWidget);
      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget);
      // ⚠️ 2026-08-18 产品决定：访客态先不做日历屏 → 没有视图切换行。
      // 若哪天接回来，这两条断言要改成 findsOneWidget。
      expect(find.byKey(const ValueKey('visitorViewTimeline')), findsNothing);
      expect(find.byKey(const ValueKey('visitorViewCalendar')), findsNothing);
      // 统计数字与作者态同源（AD-1 Rule 8）
      expect(find.text('24'), findsOneWidget);
      expect(find.text('3'), findsOneWidget);
    });

    /// AD-4：Diary 页从来没有「陪伴天数」，那是 H5 名片独有的，访客态不得引入。
    testWidgets('🛡 不引入「陪伴天数」', (tester) async {
      await tester.pumpWidget(_wrap(auth: const AuthState.guest()));
      await tester.pumpAndSettle();
      expect(find.textContaining('hari bersama'), findsNothing);
      expect(find.textContaining('bersama'), findsNothing);
    });
  });

  group('AC3 条目点击：公开可点开，私密就地拦', () {
    /// 🛡 私密条目**不跳转**，给一句解释性提示 —— 私密内容因此始终不越出链接边界。
    testWidgets('私密条目点击不跳转，出现解释性提示', (tester) async {
      await tester.pumpWidget(_wrap(
        auth: const AuthState.guest(),
        items: [_moment(postId: 1, openable: false, text: 'rahasia')],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('rahasia'));
      await tester.pump(const Duration(milliseconds: 100));

      // 仍停在访客视图（没有跳去内容详情页）
      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget);
      // ⚠️ 取 l10n 要用**页面内部**的节点：MaterialApp 本身在 Localizations 之上，拿不到。
      final l10n = AppLocalizations.of(
          tester.element(find.byKey(const ValueKey('visitorSharedBanner'))));
      expect(find.text(l10n.visitorPrivateItemNotice), findsOneWidget,
          reason: '私密条目点了应给一句解释，而不是默默无反应');
      // toast 自带定时器；不等它走完，测试结束时会报「仍有 Timer 未完成」。
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });

    /// 🛡 `openable` 缺失（后端漏发）时**按不可点处理**（fail-closed）。
    ///
    /// 默认可点会让私密内容悄悄变得可点开 —— 那是隐私事故；
    /// 默认不可点最多是「本该能点的点不开」，一眼就能看见。
    testWidgets('openable 缺失时按不可点处理', (tester) async {
      final item = TimelineItem(
        kind: TimelineKind.happyMoment,
        date: DateTime(2026, 5, 12),
        postId: 9,
        imageUrls: const [],
        text: 'tanpa-flag',
        itemType: TimelineItemType.happyMoment,
        // openable 不传 → null
      );
      await tester.pumpWidget(_wrap(auth: const AuthState.guest(), items: [item]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('tanpa-flag'));
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget,
          reason: 'openable 缺失时不该跳转 —— fail-closed');
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });
  });

  group('AD-2 Rule 2：已登录非作者与未登录访客看到同一套', () {
    testWidgets('登录与否，渲染出的减法结果一致', (tester) async {
      for (final auth in <AuthState>[
        const AuthState.guest(),
        const AuthState(status: AuthStatus.authenticated, role: 'USER'),
      ]) {
        await tester.pumpWidget(_wrap(auth: auth));
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('editProfileButton')), findsNothing);
        expect(find.byKey(const ValueKey('diaryIdCardButton')), findsNothing);
        expect(find.byKey(const ValueKey('visitorSharedBanner')), findsOneWidget);
      }
    });
  });
}
