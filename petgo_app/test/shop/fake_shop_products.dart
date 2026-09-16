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
class FakeShopProductList extends ShopProductListController {
  FakeShopProductList(this.query, this._items, {this.seen}) : super(query);

  final ShopProductsQuery query;
  final List<ShopProductSummary> _items;

  /// 记录被请求过的族键（用来断言「切品类 / 改搜索词换了源」）。
  final List<ShopProductsQuery>? seen;

  @override
  Future<ShopProductFeed> build() async {
    seen?.add(query);
    // 🔴 hasMore=false：绝大多数用例只关心首屏。要测「加载更多」的用例自己覆写它 ——
    //    把 true 设成默认会让每个用例都意外多打一次请求。
    return ShopProductFeed(items: _items, hasMore: false);
  }
}

/// 固定返回 [items] 的 override。
Override fakeShopProducts(List<ShopProductSummary> items,
        {List<ShopProductsQuery>? seen}) =>
    shopProductsProvider
        .overrideWith2((query) => FakeShopProductList(query, items, seen: seen));
