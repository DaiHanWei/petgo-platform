
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/presentation/publish_result_page.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：发布成功页的「分享成长册」CTA（留存运营作战手册 · 抓手 2）。
///
/// 手册点名的浪费：**106 人发布过内容，只有 15 人触发过分享**——分享链路没接上。
/// 用户得自己摸到成长档案页那个 FAB 才找得到分享入口，而分享落地页是诊断报告里
/// 唯一验证过的增长通道（落地 → 注册 70%）。
///
/// 本文件钉住三件事：① 有档案就当场给分享入口；② 分享出去的是 `/p/{cardToken}`
/// 成长册页而**不是**刚发的那条内容；③ 没档案时不渲染、更不能崩。
void main() {
  const token = 'abc123token';

  Future<List<String>> pumpAndTapShare(
    WidgetTester tester,
    PublishResultArgs args, {
    bool tap = true,
  }) async {
    final shared = <String>[];
    await tester.pumpWidget(ProviderScope(
      overrides: [
        shareServiceProvider.overrideWithValue(
          (String text, {Rect? sharePositionOrigin}) async => shared.add(text),
        ),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: PublishDonePage(args: args),
      ),
    ));
    await tester.pumpAndSettle();
    if (tap) {
      await tester.tap(find.byKey(const ValueKey('publishDoneShareGrowthBook')));
      await tester.pumpAndSettle();
    }
    return shared;
  }

  const withPet = PublishResultArgs(
    excerpt: 'Oyen hari ini',
    typeLabel: 'Momen',
    photoCount: 1,
    cardToken: token,
    petName: 'Mochi',
  );

  testWidgets('有档案 → 发布成功当场给分享入口，分享的是成长册 H5 而非这条内容', (tester) async {
    final shared = await pumpAndTapShare(tester, withPet);
    expect(shared, hasLength(1));
    // 🔴 必须是 /p/{cardToken}——那是已验证 70% 注册转化的落地页。
    //    绝不能改成内容详情链接：那条路的转化没有被验证过。
    expect(shared.single, endsWith('/p/$token'));
    // 不可枚举 token 对外，绝不出现顺序 id。
    expect(shared.single, isNot(contains('/content/')));
  });

  testWidgets('CTA 文案带宠物名（手册铁律：具体的理由 > 泛泛的号召）', (tester) async {
    await pumpAndTapShare(tester, withPet, tap: false);
    expect(find.textContaining('Mochi'), findsWidgets);
  });

  testWidgets('无档案（cardToken 为空）→ 不渲染分享入口，页面照常可用', (tester) async {
    await pumpAndTapShare(
      tester,
      const PublishResultArgs(excerpt: 'x', typeLabel: 'Momen', photoCount: 0),
      tap: false,
    );
    expect(find.byKey(const ValueKey('publishDoneShareGrowthBook')), findsNothing);
    // 主 CTA 仍在——分享是加分项，不能把成功页拖下水。
    expect(find.byKey(const ValueKey('publishDoneViewFeed')), findsOneWidget);
  });

  testWidgets('私密保存也给分享入口——分享的是宠物成长册主页，不是刚才那条私密内容', (tester) async {
    final shared = await pumpAndTapShare(
      tester,
      const PublishResultArgs(
        excerpt: 'x',
        typeLabel: 'Momen',
        photoCount: 0,
        isPrivate: true,
        cardToken: token,
        petName: 'Mochi',
      ),
    );
    expect(shared.single, endsWith('/p/$token'));
  });

  testWidgets('分享抛异常 → 不打扰用户（刚发布成功，此刻弹错误只会冲淡正反馈）', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        shareServiceProvider.overrideWithValue(
          (String text, {Rect? sharePositionOrigin}) async => throw StateError('boom'),
        ),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: const PublishDonePage(args: withPet),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('publishDoneShareGrowthBook')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  test('withReasons 保留 cardToken/petName（被拒页复用同一个 args）', () {
    final r = withPet.withReasons(const ['__text__']);
    expect(r.cardToken, token);
    expect(r.petName, 'Mochi');
  });
}
