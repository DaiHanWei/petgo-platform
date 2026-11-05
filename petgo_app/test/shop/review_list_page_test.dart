import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/data/shop_review_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_review.dart';
import 'package:tailtopia/features/shop/presentation/review_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// B12 全部评价页（v1.4.2 shop Story 8.2 AC7.4）。
void main() {
  ShopReviewItem item(int id, {String? nickname = 'Dewi S.', String? spec = '3 kg'}) => ShopReviewItem(
        id: id,
        rating: 4,
        imageUrls: const [],
        content: 'Review $id',
        nickname: nickname,
        specName: spec,
      );

  Future<_FakeReviewRepo> open(WidgetTester t, _FakeReviewRepo repo) async {
    t.view.physicalSize = const Size(411, 891);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(ProviderScope(
      // 关掉 Riverpod 3 的自动重试：首屏失败要停在失败态，才看得见「重试」按钮。
      retry: (_, _) => null,
      overrides: [shopReviewRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: const ReviewListPage(token: 'tok1'),
      ),
    ));
    await t.pumpAndSettle();
    return repo;
  }

  testWidgets('顶栏「Ulasan (n)」+ 头部「{avg} dari 5 · {n} ulasan」', (t) async {
    await open(t, _FakeReviewRepo([ProductReviews(total: 12, averageRating: 4.6, items: [item(1)])]));
    expect(find.text('Ulasan (12)'), findsOneWidget);
    expect(find.text('4.6 dari 5 · 12 ulasan'), findsOneWidget);
  });

  testWidgets('列表字段：昵称 / Varian；缺失时不画该行', (t) async {
    await open(
        t,
        _FakeReviewRepo([
          ProductReviews(total: 2, averageRating: 4.0, items: [
            item(1),
            item(2, nickname: null, spec: null),
          ]),
        ]));
    expect(find.text('Dewi S.'), findsOneWidget);
    expect(find.text('Varian: 3 kg'), findsOneWidget);
    expect(find.text('Review 2'), findsOneWidget);
    expect(find.byKey(const ValueKey('shopReviewNickname')), findsOneWidget);
    expect(find.byKey(const ValueKey('shopReviewSpec')), findsOneWidget);
  });

  testWidgets('触底按游标翻页', (t) async {
    final repo = await open(
        t,
        _FakeReviewRepo([
          ProductReviews(
              total: 30,
              averageRating: 4.0,
              items: [for (var i = 1; i <= 20; i++) item(i)],
              nextCursor: 'c1',
              hasMore: true),
          ProductReviews(total: 30, averageRating: 4.0, items: [for (var i = 21; i <= 30; i++) item(i)]),
        ]));
    expect(repo.cursors, [null]);
    await t.drag(find.byKey(const ValueKey('reviewListView')), const Offset(0, -6000));
    await t.pumpAndSettle();
    expect(repo.cursors, [null, 'c1']);
    await t.drag(find.byKey(const ValueKey('reviewListView')), const Offset(0, -6000));
    await t.pumpAndSettle();
    expect(find.text('Review 30'), findsOneWidget);
    expect(repo.cursors, [null, 'c1'], reason: '到底后不再请求');
  });

  testWidgets('首屏失败 → 通用重试，重试后出数据', (t) async {
    final repo = _FakeReviewRepo([ProductReviews(total: 1, averageRating: 5, items: [item(1)])])
      ..failFirst = true;
    await open(t, repo);
    expect(find.byKey(const ValueKey('shopRetryButton')), findsOneWidget);
    await t.tap(find.byKey(const ValueKey('shopRetryButton')));
    await t.pumpAndSettle();
    expect(find.text('Review 1'), findsOneWidget);
  });

  testWidgets('0 条 → 「Belum ada ulasan」', (t) async {
    await open(t, _FakeReviewRepo([const ProductReviews(total: 0, items: [])]));
    expect(find.text('Belum ada ulasan'), findsOneWidget);
  });
}

class _FakeReviewRepo extends ShopReviewRepository {
  _FakeReviewRepo(this.pages) : super(dio: Dio());

  final List<ProductReviews> pages;
  final List<String?> cursors = [];
  bool failFirst = false;

  @override
  Future<ProductReviews> page(String productToken, {String? cursor, int limit = 20}) async {
    if (failFirst) {
      failFirst = false;
      throw DioException(requestOptions: RequestOptions(path: '/x'));
    }
    cursors.add(cursor);
    return pages[cursors.length - 1];
  }
}
