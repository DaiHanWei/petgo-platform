import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/problem_detail.dart';
import '../../auth/domain/auth_state.dart';
import '../domain/shop_cart.dart';

/// 购物车数据层（Story 3.6，消费 3-1 的 `/me/cart`）。
///
/// 🔒 **全部端点要求登录**（与 Toko 商品浏览的游客开放策略相反，FR-96）。
/// 401 由 AuthInterceptor 处理，repository 不自理——但**游客根本不该走到这里**，
/// 拦在上游的 [CartController.build]（见那里的注释）。
///
/// 每个写操作后端都回**整份 CartView**，所以客户端不需要自己拼状态：
/// 直接拿返回值覆盖，失效判定与合计永远与服务端同源。
class CartRepository {
  CartRepository({required this.dio});

  final Dio dio;

  Future<CartView> view() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meCart);
    return CartView.fromJson(resp.data!);
  }

  /// 加购。
  ///
  /// 🔴 [entrySource] 是**归因链的起点**（Story 3.10）：商品「从哪个入口进的购物车」
  /// 只有此刻知道，服务端记在购物车行上、下单时抄到订单行，后台据此算触发卡转化率
  /// （AB-13B 判定 A-16）。**拿不到就不传** —— 写 null 是诚实的「未知」，
  /// 编一个值会污染看板且事后无法识别。
  Future<CartView> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType}) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meCartItems,
      queryParameters: {
        'skuToken': skuToken,
        'qty': qty,
        'entrySource': ?entrySource,
        'triggerType': ?triggerType,
      },
    );
    return CartView.fromJson(resp.data!);
  }

  /// 改数量。🔴 `qty <= 0` 后端视为删除——减号减到 0 就是删，不需要另一个端点。
  Future<CartView> setQty(String skuToken, int qty) async {
    final resp = await dio.put<Map<String, dynamic>>(
      ApiPaths.meCartItem(skuToken),
      queryParameters: {'qty': qty},
    );
    return CartView.fromJson(resp.data!);
  }

  Future<CartView> remove(String skuToken) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.meCartItem(skuToken));
    return CartView.fromJson(resp.data!);
  }

  Future<CartView> clearInvalid() async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.meCartInvalidItems);
    return CartView.fromJson(resp.data!);
  }
}

final Provider<CartRepository> cartRepositoryProvider =
    Provider<CartRepository>((ref) => CartRepository(dio: ref.read(dioProvider)));

/// 写操作的失败原因（页面据此选本地化文案）。
///
/// 🔴 **不把后端 `detail` 原文丢给用户**（CLAUDE.md：错误体走 ProblemDetail，
/// UI 文案用本地化字符串映射）。后端那句是中文运营语，直接展示既不合语种也泄实现。
enum CartMutationError {
  /// 该规格已售罄 / 想加的数量超过可售库存（后端 409）。
  stock,

  /// 其余（网络、5xx、未预期状态）。
  generic;

  static CartMutationError from(Object e) {
    if (e is DioException) {
      final status = ProblemDetail.fromDioException(e)?.status;
      if (status == 409) return stock;
    }
    return generic;
  }
}

/// 购物车状态。**全局单例**：购物车页读它，Toko / 详情页的角标也读它，
/// 任何一处加购后角标立刻跟上（同一份 state，不需要各自刷新）。
class CartController extends AsyncNotifier<CartView> {
  @override
  Future<CartView> build() async {
    // 🔒 游客不发请求，直接给空车。
    //
    // 🔴 这一行是有意的：游客打 `/me/cart` 会拿 401，而 AuthInterceptor 对 401 的处理是
    // 「续期 → 失败则 toGuest + 弹强登录引导」。也就是说，游客只要进一次 Toko（角标 watch 本
    // provider）就会被强弹窗糊脸——那正是 FR-93A 明令不要的登录墙，只不过换了个触发点。
    // 加购的软性引导由页面在**用户真的点了加购**时发起，不由数据层代劳。
    final auth = ref.watch(authControllerProvider);
    if (!auth.isLoggedIn) return CartView.empty;
    return ref.read(cartRepositoryProvider).view();
  }

  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }

  /// 加购。失败抛 [CartMutationError] 给调用方（页面负责选文案）。
  Future<void> add(String skuToken,
          {int qty = 1, String? entrySource, String? triggerType}) =>
      _mutate((repo) => repo.add(skuToken,
          qty: qty, entrySource: entrySource, triggerType: triggerType));

  Future<void> setQty(String skuToken, int qty) =>
      _mutate((repo) => repo.setQty(skuToken, qty));

  Future<void> remove(String skuToken) => _mutate((repo) => repo.remove(skuToken));

  Future<void> clearInvalid() => _mutate((repo) => repo.clearInvalid());

  /// 写操作统一出口：成功用返回的整份 CartView 覆盖，失败**保留原状态**
  /// （不把整页打成错误态——用户的车还在，只是这一次操作没成）。
  Future<void> _mutate(Future<CartView> Function(CartRepository) op) async {
    try {
      state = AsyncData(await op(ref.read(cartRepositoryProvider)));
    } catch (e) {
      throw CartMutationError.from(e);
    }
  }
}

final AsyncNotifierProvider<CartController, CartView> cartProvider =
    AsyncNotifierProvider<CartController, CartView>(CartController.new);

/// 角标件数（🔴 件数非种类数）。加载中 / 出错 / 游客 → 0，绝不显示占位数字。
final Provider<int> cartItemCountProvider =
    Provider<int>((ref) => ref.watch(cartProvider).asData?.value.itemCount ?? 0);
