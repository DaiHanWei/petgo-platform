import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';

/// L0：商品列表分页的状态机（Story 4-5 · SHOP-FR-13 / AC5）。
///
/// 用假 repository 驱动真 notifier —— 这里要钉的是**累积、游标传递、并发短路、
/// 失败不打翻首屏**，都跟网络层无关。
void main() {
  ShopProductSummary p(String token) =>
      ShopProductSummary(token: token, name: token, brand: 'B', minPrice: 1000);

  ProviderContainer hosted(_FakeRepo repo) {
    final c = ProviderContainer(
      overrides: [shopRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(c.dispose);
    return c;
  }

  const query = (category: null, keyword: null);

  test('首屏拉第一页：不带游标，items 就是第一页', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a'), p('b')], nextCursor: 'c1', hasMore: true),
    ]);
    final c = hosted(repo);

    final feed = await c.read(shopProductsProvider(query).future);

    expect(repo.cursors, [null], reason: '第一页不该带游标');
    expect(feed.items.map((e) => e.token), ['a', 'b']);
    expect(feed.hasMore, isTrue);
    expect(feed.cursor, 'c1');
  });

  test('🔴 loadMore 把新一页**追加**在后面，不是替换', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
      ShopProductPage(items: [p('b')], hasMore: false),
    ]);
    final c = hosted(repo);
    await c.read(shopProductsProvider(query).future);

    await c.read(shopProductsProvider(query).notifier).loadMore();

    final feed = c.read(shopProductsProvider(query)).asData!.value;
    expect(feed.items.map((e) => e.token), ['a', 'b'],
        reason: '替换的话用户往下滑会看到列表被换掉，而不是变长');
    expect(repo.cursors, [null, 'c1'], reason: '第二页必须带上第一页给的游标');
    expect(feed.hasMore, isFalse);
    expect(feed.cursor, isNull);
  });

  test('🔴 到底之后再 loadMore 是无操作 —— 不打空请求', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a')], hasMore: false),
    ]);
    final c = hosted(repo);
    await c.read(shopProductsProvider(query).future);

    await c.read(shopProductsProvider(query).notifier).loadMore();
    await c.read(shopProductsProvider(query).notifier).loadMore();

    expect(repo.cursors, [null], reason: '滚动回调在底部会连发，每次都打一发就是白烧流量');
  });

  test('🔴 并发短路：连发两次 loadMore 只请求一次（否则同一页会被追加两遍）', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
      ShopProductPage(items: [p('b')], nextCursor: 'c2', hasMore: true),
    ], holdSecond: true);
    final c = hosted(repo);
    await c.read(shopProductsProvider(query).future);

    final n = c.read(shopProductsProvider(query).notifier);
    final first = n.loadMore();
    await n.loadMore();      // 第二次应当被 loadingMore 短路掉
    repo.release();
    await first;

    expect(repo.cursors, [null, 'c1']);
    expect(c.read(shopProductsProvider(query)).asData!.value.items.map((e) => e.token),
        ['a', 'b'], reason: '没短路的话 b 会出现两次 —— 用户看到重复商品');
  });

  test('🔴 loadMore 失败不把整页打成错误态：首屏还在，且能再试', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
    ], failAfterFirst: true);
    final c = hosted(repo);
    await c.read(shopProductsProvider(query).future);

    await c.read(shopProductsProvider(query).notifier).loadMore();

    final state = c.read(shopProductsProvider(query));
    expect(state.hasError, isFalse, reason: '用户的列表还在那儿，不该整页变成错误');
    expect(state.asData!.value.items.map((e) => e.token), ['a']);
    expect(state.asData!.value.loadingMore, isFalse, reason: '复位后他才能再滑一次');
  });

  test('🔴 切品类 = 换族键 = 重新拉第一页（游标不会串到新品类上）', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
      ShopProductPage(items: [p('x')], hasMore: false),
    ]);
    final c = hosted(repo);
    await c.read(shopProductsProvider(query).future);

    // 换品类：另一个族键 ⇒ 另一个 provider 实例 ⇒ build() 从头拉。
    // 「重置游标」是靠族键天然成立的，不是靠某处手写的 reset —— 手写才会漏。
    await c.read(shopProductsProvider(
            (category: ShopCategory.makanan, keyword: null))
        .future);

    expect(repo.cursors, [null, null],
        reason: '第二次仍是 null：接着上一个品类的游标往下翻会串货');
  });

  test('关键词变化同理换族键', () async {
    final repo = _FakeRepo([
      ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
      ShopProductPage(items: [p('x')], hasMore: false),
    ]);
    final c = hosted(repo);
    await c.read(shopProductsProvider(query).future);

    await c.read(shopProductsProvider((category: null, keyword: 'royal')).future);

    expect(repo.cursors, [null, null]);
    expect(repo.keywords, [null, 'royal']);
  });

  test('ShopProductPage.fromJson：末页缺 nextCursor 键读成 null', () {
    // 后端 NON_NULL 下末页整键省略 —— 缺键与 null 同样得到 null。
    final page = ShopProductPage.fromJson(const {
      'items': [
        {'token': 'a', 'name': 'A', 'brand': 'B', 'minPrice': 1000}
      ],
      'hasMore': false,
    });

    expect(page.nextCursor, isNull);
    expect(page.hasMore, isFalse);
    expect(page.items, hasLength(1));
  });

  test('ShopProductPage.fromJson：空响应不炸', () {
    final page = ShopProductPage.fromJson(const {});

    expect(page.items, isEmpty);
    expect(page.hasMore, isFalse);
  });
}

/// 按脚本逐页返回的假 repository。
class _FakeRepo implements ShopRepository {
  _FakeRepo(this._pages, {this.holdSecond = false, this.failAfterFirst = false});

  final List<ShopProductPage> _pages;
  final bool holdSecond;
  final bool failAfterFirst;
  int _i = 0;

  /// 每次请求带的游标，按顺序记下 —— 断言「第几页带了什么」。
  final List<String?> cursors = [];
  final List<String?> keywords = [];

  final _gate = Completer<void>();
  void release() => _gate.complete();

  @override
  Future<ShopProductPage> fetchProductPage({
    ShopCategory? category,
    String? keyword,
    String? cursor,
    int size = kShopPageSize,
  }) async {
    cursors.add(cursor);
    keywords.add(keyword);
    if (failAfterFirst && _i >= 1) {
      throw Exception('boom');
    }
    if (holdSecond && _i == 1) {
      await _gate.future;
    }
    return _pages[_i++];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('本类只用到 fetchProductPage');
}
