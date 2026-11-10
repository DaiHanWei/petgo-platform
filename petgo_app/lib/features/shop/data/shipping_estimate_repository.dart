/// 商品详情运费估算 + 本机记住的送达地区（v1.4.2 shop Story 8.2 AC2 / AC3）。
library;

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/storage/prefs.dart';
import '../domain/shipping_estimate.dart';

class ShippingEstimateRepository {
  ShippingEstimateRepository({required this.dio});

  final Dio dio;

  /// 估运费。🔒 游客可用；限流键与日志都不含地址（服务端）。
  /// 🔴 详情页本身无数量，[qty] 固定 1（story 拍板点 2）。
  Future<ShippingEstimate> estimate(ShippingEstimateQuery q, {int qty = 1}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.shopProductShippingEstimate(q.product),
      queryParameters: {
        'skuToken': q.sku,
        'qty': qty,
        'addressToken': ?q.address,
        'regionKecamatanId': ?(q.address == null ? q.region : null),
      },
    );
    return ShippingEstimate.fromJson(resp.data ?? const {});
  }
}

final shippingEstimateRepositoryProvider = Provider<ShippingEstimateRepository>(
    (ref) => ShippingEstimateRepository(dio: ref.read(dioProvider)));

/// 估运费。请求整体失败（网络 / 5xx / 422）→ [ShippingEstimate.failed]（B15：可重试），不抛 —— 运费行不该把整页打成错误态。
final shippingEstimateProvider = FutureProvider.autoDispose
    .family<ShippingEstimate, ShippingEstimateQuery>((ref, q) async {
  try {
    return await ref.read(shippingEstimateRepositoryProvider).estimate(q);
  } catch (_) {
    return ShippingEstimate.failed;
  }
});

/// 本机记住的送达地区（B6 选择结果，**只记本地、不生成收货地址**）。8.3 购物车无地址时同用。
///
/// 读不到 prefs（测试环境 / 平台异常）→ null（按「未选地区」处理）。不 autoDispose：跨页共享同一份。
class ShopDeliveryAreaController extends AsyncNotifier<ShopDeliveryArea?> {
  @override
  Future<ShopDeliveryArea?> build() async {
    try {
      return (await AppPrefs.create()).shopDeliveryArea;
    } catch (_) {
      return null;
    }
  }

  /// 记住所选地区。先更新内存态（页面立刻用它估运费），写盘失败不影响本次会话。
  Future<void> select(ShopDeliveryArea area) async {
    state = AsyncData(area);
    try {
      await (await AppPrefs.create()).setShopDeliveryArea(area);
    } catch (_) {
      // 写不进本地只影响「下次进来还记得」，本次已生效。
    }
  }

  /// 清掉本机记住的地区（8.2 复审 #4：退出登录 / 换账号时清，更保守 —— 不让下一个使用者看到上一位选过的区）。
  Future<void> clear() async {
    state = const AsyncData(null);
    try {
      await (await AppPrefs.create()).clearShopDeliveryArea();
    } catch (_) {}
  }
}

/// 是否离开了某个账号（退出登录 / 换账号 / 切兽医）：此前有用户 id、现在不是同一个。
/// 冷启动恢复会话（prev 无 id）不算 —— 否则每次重启都丢掉「记住的地区」。
bool leftUserAccount(int? prevUserId, int? nextUserId) =>
    prevUserId != null && nextUserId != prevUserId;

final shopDeliveryAreaProvider =
    AsyncNotifierProvider<ShopDeliveryAreaController, ShopDeliveryArea?>(
        ShopDeliveryAreaController.new);
