import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/problem_detail.dart';
import '../../auth/domain/auth_state.dart';
import '../domain/shop_cart.dart';
import 'shipping_estimate_repository.dart';

/// 购物车数据层（Story 3.6，消费 3-1 的 `/me/cart`）。
///
/// 🔒 **全部端点要求登录**（与 Toko 商品浏览的游客开放策略相反，FR-96）。
/// 401 由 AuthInterceptor 处理，repository 不自理——但**游客根本不该走到这里**，
/// 拦在上游的 [CartController.build]（见那里的注释）。
///
/// 每个写操作后端都回**整份 CartView**，所以客户端不需要自己拼状态：
/// 直接拿返回值覆盖，失效判定与合计永远与服务端同源。
///
/// v1.4.2 shop Story 8.3：后端 `MeCartController` **7 个出口都装配 `estimate`、都接受可选
/// `regionKecamatanId`**（无默认地址时按该地区估运费）。App 在本机记过送达地区时每个请求都带上，
/// 变更后的预估与 GET 同口径，不需要再补一次 refresh。null 不发该键（旧后端忽略未知参数亦无害）。
class CartRepository {
  CartRepository({required this.dio});

  final Dio dio;

  Future<CartView> view({int? regionKecamatanId}) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meCart,
        queryParameters: {'regionKecamatanId': ?regionKecamatanId});
    return CartView.fromJson(resp.data!);
  }

  /// 加购。
  ///
  /// 🔴 [entrySource] 是**归因链的起点**（Story 3.10）：商品「从哪个入口进的购物车」
  /// 只有此刻知道，服务端记在购物车行上、下单时抄到订单行，后台据此算触发卡转化率
  /// （AB-13B 判定 A-16）。**拿不到就不传** —— 写 null 是诚实的「未知」，
  /// 编一个值会污染看板且事后无法识别。
  ///
  /// [buyNow] = 立即购买：服务端只勾这一件、车内其它取消勾选，结算页只结它（code review #4）。
  /// 旧后端不认这个参数会忽略它，退化为普通加购（已勾选），不报错。
  Future<CartView> add(String skuToken,
      {int qty = 1,
      String? entrySource,
      String? triggerType,
      bool buyNow = false,
      int? regionKecamatanId}) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meCartItems,
      queryParameters: {
        'skuToken': skuToken,
        'qty': qty,
        'entrySource': ?entrySource,
        'triggerType': ?triggerType,
        if (buyNow) 'buyNow': true,
        'regionKecamatanId': ?regionKecamatanId,
      },
    );
    return CartView.fromJson(resp.data!);
  }

  /// 改数量。🔴 `qty <= 0` 后端视为删除——减号减到 0 就是删，不需要另一个端点。
  Future<CartView> setQty(String skuToken, int qty, {int? regionKecamatanId}) async {
    final resp = await dio.put<Map<String, dynamic>>(
      ApiPaths.meCartItem(skuToken),
      queryParameters: {'qty': qty, 'regionKecamatanId': ?regionKecamatanId},
    );
    return CartView.fromJson(resp.data!);
  }

  Future<CartView> remove(String skuToken, {int? regionKecamatanId}) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.meCartItem(skuToken),
        queryParameters: {'regionKecamatanId': ?regionKecamatanId});
    return CartView.fromJson(resp.data!);
  }

  /// 勾选 / 取消勾选单行（Story 4-2 / SHOP-FR-04）。
  ///
  /// 🔴 **这不是删除**：取消勾选只改 `selected` 位，商品仍在车里、数量不变。
  /// 用 `DELETE` 模拟「不买这件」是用破坏性操作实现查询语义 —— 取消结算就丢数据。
  Future<CartView> setSelected(String skuToken, bool selected,
      {int? regionKecamatanId}) async {
    final resp = await dio.put<Map<String, dynamic>>(
      ApiPaths.meCartItemSelected(skuToken),
      queryParameters: {'selected': selected, 'regionKecamatanId': ?regionKecamatanId},
    );
    return CartView.fromJson(resp.data!);
  }

  /// 全选 / 全不选。
  Future<CartView> setAllSelected(bool selected, {int? regionKecamatanId}) async {
    final resp = await dio.put<Map<String, dynamic>>(
      ApiPaths.meCartSelection,
      queryParameters: {'selected': selected, 'regionKecamatanId': ?regionKecamatanId},
    );
    return CartView.fromJson(resp.data!);
  }

  Future<CartView> clearInvalid({int? regionKecamatanId}) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.meCartInvalidItems,
        queryParameters: {'regionKecamatanId': ?regionKecamatanId});
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
  /// 复审修复（v1.4.2 shop 8.3）：**单调请求序号**，build 与所有写操作共用。
  ///
  /// 🔴 每份 CartView 都是服务端整车快照，晚到的旧响应覆盖新响应 = 界面回退到过去
  /// （慢 GET 盖掉快 POST 的加购结果；A 行勾选的慢响应盖掉随后「全选」的结果）。
  /// 规则：**只接受最新一次发出的请求的结果**；更早发出、更晚到达的结果丢弃。
  int _seq = 0;

  /// 最近一次被接受的写操作结果及其序号（build 晚到时用来让位给更新的写结果）。
  CartView? _latest;
  int _latestSeq = 0;

  @override
  Future<CartView> build() async {
    final my = ++_seq;
    // 🔒 游客不发请求，直接给空车。
    //
    // 🔴 这一行是有意的：游客打 `/me/cart` 会拿 401，而 AuthInterceptor 对 401 的处理是
    // 「续期 → 失败则 toGuest + 弹强登录引导」。也就是说，游客只要进一次 Toko（角标 watch 本
    // provider）就会被强弹窗糊脸——那正是 FR-93A 明令不要的登录墙，只不过换了个触发点。
    // 加购的软性引导由页面在**用户真的点了加购**时发起，不由数据层代劳。
    final auth = ref.watch(authControllerProvider);
    if (!auth.isLoggedIn) return CartView.empty;
    // v1.4.2 shop Story 8.3 AC7.2：本机记过送达地区（8.2 详情页 B6）→ 带上，无默认地址时服务端按它估运费。
    // watch：在详情页换了地区，购物车随之按新地区重估。
    final area = await ref.watch(shopDeliveryAreaProvider.future);
    final v = await ref.read(cartRepositoryProvider).view(regionKecamatanId: area?.kecamatanId);
    // 本次 GET 发出后又有写操作发出且已先到 → 那份更新，让位给它（build 的返回值必然落成 state）。
    if (my != _seq && _latestSeq > my && _latest != null) return _latest!;
    _latest = v;
    _latestSeq = my;
    return v;
  }

  /// 本机记住的送达地区 id（变更类请求同带，保证返回的 `estimate` 与 GET 同口径）。读不到 → null。
  Future<int?> _regionId() async {
    try {
      return (await ref.read(shopDeliveryAreaProvider.future))?.kecamatanId;
    } catch (_) {
      return null;
    }
  }

  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }

  /// 加购。失败抛 [CartMutationError] 给调用方（页面负责选文案）。
  Future<void> add(String skuToken,
          {int qty = 1, String? entrySource, String? triggerType, bool buyNow = false}) =>
      _mutate((repo, region) => repo.add(skuToken,
          qty: qty,
          entrySource: entrySource,
          triggerType: triggerType,
          buyNow: buyNow,
          regionKecamatanId: region));

  Future<void> setQty(String skuToken, int qty) =>
      _mutate((repo, region) => repo.setQty(skuToken, qty, regionKecamatanId: region));

  Future<void> remove(String skuToken) =>
      _mutate((repo, region) => repo.remove(skuToken, regionKecamatanId: region));

  /// 勾选 / 取消勾选单行。
  ///
  /// 🔴 **不做乐观更新**：勾选是服务端状态，直接用端点返回的整份 CartView 覆盖。
  /// 先在本地勾上再发请求，一旦失败就会留下「看着勾上了其实没勾上」——
  /// 而底栏金额是按服务端的 selectedSubtotal 显示的，两者会当场对不上。
  Future<void> setSelected(String skuToken, bool selected) =>
      _mutate((repo, region) =>
          repo.setSelected(skuToken, selected, regionKecamatanId: region));

  Future<void> setAllSelected(bool selected) =>
      _mutate((repo, region) => repo.setAllSelected(selected, regionKecamatanId: region));

  Future<void> clearInvalid() =>
      _mutate((repo, region) => repo.clearInvalid(regionKecamatanId: region));

  /// 写操作统一出口：成功用返回的整份 CartView 覆盖，失败**保留原状态**
  /// （不把整页打成错误态——用户的车还在，只是这一次操作没成）。
  Future<void> _mutate(Future<CartView> Function(CartRepository, int?) op) async {
    // 🔴 序号在任何 await 之前取：「发出顺序」以用户操作的先后为准。
    final my = ++_seq;
    final region = await _regionId();
    try {
      final v = await op(ref.read(cartRepositoryProvider), region);
      // 之后又发出过请求（写或 GET）→ 本结果已过时，丢弃；最新那次的结果会落成 state。
      if (my != _seq) return;
      _latest = v;
      _latestSeq = my;
      state = AsyncData(v);
    } catch (e) {
      // 最新一次失败、而它之前还有请求的结果被丢弃 / 仍在路上 → 服务端此刻的车不可知，重拉一次对齐。
      // 只有它一个请求时保持原状态（不把整页打成加载态，用户的车还在）。
      if (my == _seq && my - 1 > _latestSeq) ref.invalidateSelf();
      throw CartMutationError.from(e);
    }
  }
}

final AsyncNotifierProvider<CartController, CartView> cartProvider =
    AsyncNotifierProvider<CartController, CartView>(CartController.new);

/// 角标件数（🔴 件数非种类数）。加载中 / 出错 / 游客 → 0，绝不显示占位数字。
final Provider<int> cartItemCountProvider =
    Provider<int>((ref) => ref.watch(cartProvider).asData?.value.itemCount ?? 0);
