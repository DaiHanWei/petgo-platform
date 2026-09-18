import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
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

  /// 🔴🔴 <b>响应形状按实际形状分支，不靠强转</b>（2026-09-18 复审 #11）。
  ///
  /// `fetchProductPage` 恒传 `size`，所以新后端恒回信封 —— 但那只防住了
  /// 「老 App + 新后端」**一个方向**。反方向同样会发生，而且更常见：
  /// App 先于后端发版、或后端回滚到没有分页的版本，端点回的就是**老的全量数组**。
  /// 原写法 `dio.get<Map<String, dynamic>>` 会在那一刻抛 `TypeError`，
  /// Toko 与搜索**整页挂掉**（数据其实就在手里，只是形状不同）。
  ///
  /// 🎯 **变异靶子**：把 `dio.get<dynamic>` 改回 `dio.get<Map<String, dynamic>>`
  /// 并直接 `ShopProductPage.fromJson(resp.data ?? const {})`，本组第一条必须变红。
  group('🔴 响应形状容错（真 ShopRepository，只换掉最外层网络）', () {
    ShopRepository repoReturning(String body) => ShopRepository(
        dio: Dio()..httpClientAdapter = _BodyAdapter(body));

    test('🎯 后端返回**数组**（老接口 / 回滚）→ 当成一页装完的全量，不抛', () async {
      final repo = repoReturning(
          '[{"token":"a","name":"A","brand":"B","minPrice":1000},'
          '{"token":"b","name":"B","brand":"B","minPrice":2000}]');

      final page = await repo.fetchProductPage();

      expect(page.items.map((e) => e.token), ['a', 'b'],
          reason: '数据就在手里，只是形状不同 —— 不该让用户看到一个错误页');
      expect(page.hasMore, isFalse,
          reason: '老接口一次给全量：再去翻第二页是拿着不存在的游标空转');
      expect(page.nextCursor, isNull);
    });

    test('后端返回信封 → 照常解析（别把正常路径一起改坏了）', () async {
      final repo = repoReturning(
          '{"items":[{"token":"a","name":"A","brand":"B","minPrice":1000}],'
          '"nextCursor":"c1","hasMore":true}');

      final page = await repo.fetchProductPage();

      expect(page.items.map((e) => e.token), ['a']);
      expect(page.nextCursor, 'c1');
      expect(page.hasMore, isTrue);
    });

    test('后端返回 null / 其它形状 → 空页，不抛（页面显示空态而不是错误页）', () async {
      final page = await repoReturning('null').fetchProductPage();

      expect(page.items, isEmpty);
      expect(page.hasMore, isFalse);
    });
  });

  /// 🔴🔴 <b>autoDispose 回收后不得再写 state</b>（2026-09-18 复审 #12）。
  ///
  /// `shopProductsProvider` 是 `autoDispose.family`：用户在这一页还在路上时切品类、
  /// 改搜索词或离开页面，旧族键当场被回收。此后给 `state` 赋值会抛，而 `loadMore`
  /// 是滚动回调里 **fire-and-forget** 调的 —— 没人 await 它的 Future，
  /// 抛出来的异常没有任何接手方，debug 包直接红屏。
  ///
  /// ⚠️ 更隐蔽的是 `catch` 那一支：原实现在 try 里抛一次，catch 里做**同一个赋值**
  /// 再抛第二次，第二次连 catch 都没有。两条路径都要测。
  ///
  /// 🎯 **变异靶子**：删掉 `loadMore` 里任意一句 `if (!ref.mounted) return;`，
  /// 对应那条用例必须变红。
  group('🔴 加载中途被回收：不得抛（fire-and-forget 没人接）', () {
    test('🎯 成功返回时才发现族键已回收 → 静默收手', () async {
      final repo = _FakeRepo([
        ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
        ShopProductPage(items: [p('b')], hasMore: false),
      ], holdSecond: true);
      final c = hosted(repo);

      // 🔴 用 listen 建立订阅再取消 —— autoDispose 的回收就是这么发生的
      //    （页面 dispose / 切族键，最后一个监听者走了）。
      final sub = c.listen(shopProductsProvider(query), (_, _) {});
      await c.read(shopProductsProvider(query).future);
      final n = c.read(shopProductsProvider(query).notifier);

      final pending = n.loadMore();      // 卡在第二页的网络上
      sub.close();                       // 用户切品类 / 离开页面 → 这一族被回收
      // ⚠️ 必须让出一次事件循环：autoDispose 的回收不是同步发生的。
      //    不等就会变成「请求先返回、回收后发生」，测不到要测的那一格。
      await Future<void>.delayed(Duration.zero);
      expect(c.exists(shopProductsProvider(query)), isFalse,
          reason: '这一族没被回收的话，本用例只是在测一条普通的成功路径（假绿）');

      repo.release();

      await expectLater(pending, completes,
          reason: '写一个已回收的 provider 会抛 UnmountedRefException，'
              '而这个 Future 没有任何接手方 —— debug 包直接红屏');
    });

    test('🎯 失败路径同样不得抛（catch 里那句赋值也会炸）', () async {
      final repo = _FakeRepo([
        ShopProductPage(items: [p('a')], nextCursor: 'c1', hasMore: true),
      ], holdSecond: true, failAfterFirst: true);
      final c = hosted(repo);

      final sub = c.listen(shopProductsProvider(query), (_, _) {});
      await c.read(shopProductsProvider(query).future);
      final n = c.read(shopProductsProvider(query).notifier);

      final pending = n.loadMore();
      sub.close();
      await Future<void>.delayed(Duration.zero);
      expect(c.exists(shopProductsProvider(query)), isFalse);

      repo.release();   // 放行之后这一发请求才抛 boom

      await expectLater(pending, completes,
          reason: '失败路径上的恢复动作自己也会失败 —— 这类崩溃最难查');
    });
  });
}

/// 固定返回一段原始响应体的适配器：真 [ShopRepository] + 真 dio 在跑，
/// 只把最外层网络换掉 —— 形状容错要测的正是「dio 把它解成什么」那一层。
class _BodyAdapter implements HttpClientAdapter {
  _BodyAdapter(this.body);

  final String body;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async =>
      ResponseBody.fromString(body, 200, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });

  @override
  void close({bool force = false}) {}
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
    // ⚠️ 先 hold 再判失败：两个开关同时打开时要模拟的是
    //    「请求挂在路上 → 期间 provider 被回收 → 然后才失败」。
    //    顺序反了就变成「立刻失败」，测不到 catch 分支里那句赋值。
    if (holdSecond && _i == 1) {
      await _gate.future;
    }
    if (failAfterFirst && _i >= 1) {
      throw Exception('boom');
    }
    return _pages[_i++];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('本类只用到 fetchProductPage');
}
