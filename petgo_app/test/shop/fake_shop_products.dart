// ⚠️ 不能只 `show AsyncData`：`asData` 是 `AsyncValueX` 上的**扩展**取值器，
//    扩展不在 show 列表里就不可见（报错长得像「getter 未定义」，很容易误判成版本问题）。
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';

/// 商品列表 provider 的测试替身（Story 4-5 起）。
///
/// ⚠️ `shopProductsProvider` 在 4-5 从 `FutureProvider<List<…>>` 变成了分页的
/// `AsyncNotifierProvider<…, ShopProductFeed>`，六处测试的 override 写法随之改变。
/// 收在这里一份，免得六个文件各写一个略有出入的假货 ——
/// 那种出入正是「测试里绿、真机上红」的常见来源。
///
/// ⚠️ 家族 provider 的 override 走的是 **`overrideWith2`**（带族键的那个重载）；
/// 不带参数的 `overrideWith` 在 Riverpod 3 里已标 deprecated，且拿不到族键，
/// 于是 `seen` 这类「断言换了源」的用例就没法写。
///
/// 🔴 <b>[hasMore] 必须可配</b>（2026-09-18 复审）：本替身此前把 `hasMore` **硬写成
/// false**，7 个调用点全受影响 —— 真实 `loadMore()` 的第一道守卫就是
/// `if (!current.hasMore) return`，于是**没有任何 widget 测试真正走到过翻页那条路**。
/// Story 4-5 的滚动接线因此在 widget 层完全没被测过，
/// 「横向滚动误触发预加载」那个缺陷才能带着满屏绿灯提交。
/// 默认仍是 false（绝大多数用例只关心首屏，设 true 会让它们意外多打一次请求），
/// 但要测翻页的用例现在**可以**把它打开。
class FakeShopProductList extends ShopProductListController {
  FakeShopProductList(
    this.query,
    this._items, {
    this.seen,
    this.hasMore = false,
    this.nextPageItems = const [],
    this.loadMoreCalls,
  }) : super(query);

  final ShopProductsQuery query;
  final List<ShopProductSummary> _items;

  /// 记录被请求过的族键（用来断言「切品类 / 改搜索词换了源」）。
  final List<ShopProductsQuery>? seen;

  /// 首屏之后还有没有下一页。见类注释：**默认 false 是为了不打扰只关心首屏的用例**。
  final bool hasMore;

  /// [loadMore] 追加的那一页内容。
  final List<ShopProductSummary> nextPageItems;

  /// 🔴 记录**页面调用 loadMore 的次数**（每个元素是一次调用）。
  ///
  /// 记在方法入口、**短路判断之前** —— 本列表要回答的是「页面的滚动监听有没有开火」，
  /// 而不是「有没有真的取到下一页」。放在短路之后就测不出
  /// 「横划误触发」这类缺陷（它们在 hasMore=false 时同样会被吞掉）。
  final List<ShopProductsQuery>? loadMoreCalls;

  @override
  Future<ShopProductFeed> build() async {
    seen?.add(query);
    return ShopProductFeed(
      items: _items,
      hasMore: hasMore,
      cursor: hasMore ? 'cursor-1' : null,
    );
  }

  /// 不打网络的 [ShopProductListController.loadMore]。
  ///
  /// ⚠️ 两道短路（`hasMore` / `loadingMore`）刻意与真实实现逐字同构 ——
  /// 替身若比真货宽松，用例就会在一条真机上不存在的路径上变绿。
  @override
  Future<void> loadMore() async {
    loadMoreCalls?.add(query);
    final current = state.asData?.value;
    if (current == null || !current.hasMore || current.loadingMore) return;
    state = AsyncData(ShopProductFeed(
      items: [...current.items, ...nextPageItems],
      hasMore: false,
    ));
  }
}

/// 固定返回 [items] 的 override。
Override fakeShopProducts(
  List<ShopProductSummary> items, {
  List<ShopProductsQuery>? seen,
  bool hasMore = false,
  List<ShopProductSummary> nextPageItems = const [],
  List<ShopProductsQuery>? loadMoreCalls,
}) =>
    shopProductsProvider.overrideWith2((query) => FakeShopProductList(
          query,
          items,
          seen: seen,
          hasMore: hasMore,
          nextPageItems: nextPageItems,
          loadMoreCalls: loadMoreCalls,
        ));
