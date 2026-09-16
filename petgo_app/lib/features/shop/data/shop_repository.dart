import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shop_banner.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart';

/// Toko 数据层（Story 1.6，消费 1-1 的只读接口）。
///
/// 🔒 **本接口对游客开放**（Story 1.1 已在 `SecurityConfig` 放行 GET，实测游客 200）。
/// 因此这里**不做任何登录判断、不触发登录引导**——FR-93A 的整个意思就是这一层不设门槛。
///
/// 错误以 [DioException] 抛给控制器；401 由 AuthInterceptor 处理，repository 不自理
/// （与 `order_repository.dart` 同范式）。
class ShopRepository {
  ShopRepository({required this.dio});

  final Dio dio;

  /// 拉商品列表。[category] 为空 = 全部精选（区域④）。
  ///
  /// [keyword] 为关键词搜索（2026-08-31），命中商品名或品牌，忽略大小写。
  /// 🔴 **搜索在后端做**：本接口一次性返回整个上架目录，目录一大，前端过滤会连首屏
  /// 一起拖垮。后端加 `q` 之后这里只是把参数透传下去。
  ///
  /// 🔴 [keyword] 与 [category] 是**与关系**：选了品类再搜，只在该品类内搜。
  Future<List<ShopProductSummary>> fetchProducts({
    ShopCategory? category,
    String? keyword,
  }) async {
    // 空白关键词不发 q —— 让「清空搜索框」回到与从未搜过**逐字相同**的请求，
    // 顺带让 dio 的请求签名一致，provider 的缓存也就能命中同一条。
    final q = keyword?.trim();
    final resp = await dio.get<List<dynamic>>(
      ApiPaths.shopProducts,
      queryParameters: {
        'category': ?category?.api,
        'q': ?(q == null || q.isEmpty ? null : q),
      },
    );
    final rows = resp.data ?? const [];
    return rows
        .whereType<Map<String, dynamic>>()
        .map(ShopProductSummary.fromJson)
        .toList(growable: false);
  }

  /// 拉商品列表的**一页**（Story 4-5 · SHOP-FR-13）。
  ///
  /// 🔴 <b>只要带上 cursor 或 size，后端就切到 `{items, nextCursor, hasMore}` 信封</b>；
  /// 不带则仍返回老的全量数组（那条分支是给线上老版本 App 留的，见后端
  /// `ShopProductController.list` 的注释）。本方法**永远带 size**，所以永远拿信封 ——
  /// 不要「第一页省掉 size」，那会让第一页走老分支、拿到一个数组而解析失败。
  ///
  /// [cursor] 为 null = 第一页。[category] / [keyword] 语义与 [fetchProducts] 逐字相同。
  Future<ShopProductPage> fetchProductPage({
    ShopCategory? category,
    String? keyword,
    String? cursor,
    int size = kShopPageSize,
  }) async {
    final q = keyword?.trim();
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.shopProducts,
      queryParameters: {
        'category': ?category?.api,
        'q': ?(q == null || q.isEmpty ? null : q),
        'cursor': ?cursor,
        'size': size,
      },
    );
    return ShopProductPage.fromJson(resp.data ?? const {});
  }

  /// Toko 顶部 banner（2026-08-27）。**没有可展示的 banner 时返回 null**。
  ///
  /// 🔴 判据是 **204 No Content**，不是"data 为空"：后端在拼不出 URL 时也回 204，
  /// 把"有没有 banner"收敛成了一个明确的状态码。这里照着它判，不要改成判 data ——
  /// 判 data 会把网络层的空响应也误当成"没有 banner"。
  ///
  /// ⚠️ 拉取失败一律当作**没有 banner**（返回 null）而不是抛错：banner 是锦上添花的
  /// 展示位，它挂了不该让整个 Toko 页进入错误态 —— 那是主次颠倒。
  Future<ShopBanner?> fetchBanner() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopBanner);
      if (resp.statusCode == 204 || resp.data == null) return null;
      return ShopBanner.fromJson(resp.data!);
    } catch (_) {
      return null;
    }
  }

  /// 商品详情（Story 1.7）。未上架/不存在 → 后端 404（`DioException` 抛给页面）。
  /// 🔒 同样对游客开放，不做登录判断。
  Future<ShopProductDetail> fetchDetail(String token) async {
    final resp = await dio.get<Map<String, dynamic>>('${ApiPaths.shopProducts}/$token');
    return ShopProductDetail.fromJson(resp.data!);
  }
}

final shopRepositoryProvider =
    Provider<ShopRepository>((ref) => ShopRepository(dio: ref.read(dioProvider)));

/// 商品列表的族键：品类 + 关键词（2026-08-31 加入搜索）。
///
/// 🔴 用 **record** 而不是自定义类：record 天生结构相等，family 的缓存/去重直接就对了；
/// 换成普通类就得手写 `==`/`hashCode`，漏一个就会每次重建都当成新族键、无限重拉。
typedef ShopProductsQuery = ({ShopCategory? category, String? keyword});

/// 商品列表，按「品类 + 关键词」分族（都为空 = 全部精选，即区域④）。
///
/// 选中态由页面自己的 State 持有 —— 一个纯 UI 筛选没必要提升成全局 provider，
/// 也就顺带避开了 Riverpod 3 已移除 `StateProvider` 的问题。
/// 商品列表的可翻页状态（Story 4-5）。
///
/// 🔴 [items] 是**累积**的（第一页 + 已加载的后续页），不是「当前这一页」——
/// 页面渲染的是一条连续的流，用户往下滑不该看到列表被替换。
class ShopProductFeed {
  const ShopProductFeed({
    required this.items,
    required this.hasMore,
    this.cursor,
    this.loadingMore = false,
  });

  final List<ShopProductSummary> items;

  /// 下一页游标；[hasMore] 为 false 时为 null。
  final String? cursor;

  /// 还有下一页。🔴 「到底了」的唯一判据 —— 不要改成看 `cursor != null`。
  final bool hasMore;

  /// 正在加载下一页（首屏加载是 `AsyncLoading`，这个只表示**追加**中）。
  final bool loadingMore;

  ShopProductFeed copyWith({
    List<ShopProductSummary>? items,
    String? cursor,
    bool? hasMore,
    bool? loadingMore,
  }) =>
      ShopProductFeed(
        items: items ?? this.items,
        cursor: cursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
      );
}

/// 商品列表控制器（Story 4-5 · SHOP-FR-13）。
///
/// 🔴 <b>「切品类 / 改搜索词 → 游标重置」是靠 family 键天然成立的</b>，
/// 不是靠某处手写的 reset：族键就是 `(category, keyword)`，换了键就是换了一个
/// provider 实例，它的 `build` 从头拉第一页。手写 reset 才会漏 ——
/// 漏了就会出现「换了品类、却接着上一个品类的游标往下翻」。
///
/// ⚠️ 仍是 `autoDispose`：搜索按输入产生多个族键（页面侧已防抖），
/// 不自动回收的话这些一次性的键会一直挂着。
class ShopProductListController extends AsyncNotifier<ShopProductFeed> {
  ShopProductListController(this._query);

  /// 本实例对应的族键。Riverpod 3 的家族 notifier 由工厂接参，`build()` 不带参数。
  final ShopProductsQuery _query;

  @override
  Future<ShopProductFeed> build() async {
    final page = await ref
        .read(shopRepositoryProvider)
        .fetchProductPage(category: _query.category, keyword: _query.keyword);
    return ShopProductFeed(
        items: page.items, cursor: page.nextCursor, hasMore: page.hasMore);
  }

  /// 追加下一页。
  ///
  /// 🔴 **并发与到底双重短路**：`loadingMore` 防止滚动回调连发时重复请求
  /// （同一页会被追加两次 —— 用户会看到重复商品）；`hasMore` 防止到底后还去打空请求。
  ///
  /// 🔴 **失败不把整页打成错误态**：首屏已经在了，用户的列表还在那儿。
  /// 只是这一次「加载更多」没成，复位 [ShopProductFeed.loadingMore] 让他能再滑一次。
  Future<void> loadMore() async {
    final current = state.asData?.value;
    if (current == null || !current.hasMore || current.loadingMore) return;
    state = AsyncData(current.copyWith(cursor: current.cursor, loadingMore: true));
    try {
      final page = await ref.read(shopRepositoryProvider).fetchProductPage(
            category: _query.category,
            keyword: _query.keyword,
            cursor: current.cursor,
          );
      state = AsyncData(ShopProductFeed(
        items: [...current.items, ...page.items],
        cursor: page.nextCursor,
        hasMore: page.hasMore,
      ));
    } catch (_) {
      state = AsyncData(current.copyWith(cursor: current.cursor, loadingMore: false));
    }
  }
}

final shopProductsProvider = AsyncNotifierProvider.autoDispose
    .family<ShopProductListController, ShopProductFeed, ShopProductsQuery>(
        ShopProductListController.new);

/// Toko 顶部 banner。null = 没有可展示的 banner（页面据此显示白色顶栏）。
///
/// 🔴 **不用 autoDispose**：banner 变动极少，而 Toko 是高频进出的 Tab ——
/// 每次进出都重拉一次纯属浪费，且会让顶部在每次返回时闪一下。
final shopBannerProvider = FutureProvider<ShopBanner?>((ref) async {
  return ref.read(shopRepositoryProvider).fetchBanner();
});

/// 商品详情（按 token）。
final shopProductDetailProvider =
    FutureProvider.autoDispose.family<ShopProductDetail, String>((ref, token) async {
  return ref.read(shopRepositoryProvider).fetchDetail(token);
});
