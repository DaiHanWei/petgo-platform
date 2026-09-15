import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/place_summary.dart';

/// 场所数据层（V1.3.0 batch-b1 Story 1.1，消费 `GET /api/v1/places`）。
///
/// 🔒 **本接口对游客开放**（后端 `SecurityConfig` 已放行 GET）。因此这里
/// **不做任何登录判断、不触发登录引导** —— 场所列表是「这个功能里已经攒了些什么地方」
/// 的展示面，用登录墙拦它没有意义（同 Toko 商品列表的既定取舍）。
///
/// 错误以 [DioException] 抛给页面（F13 统一口径：保留已加载内容 + 给重试入口）；
/// 401 由 AuthInterceptor 处理，repository 不自理（与 `shop_repository.dart` 同范式）。
class PlaceRepository {
  PlaceRepository({required this.dio});

  final Dio dio;

  /// 场所列表。
  ///
  /// 带坐标 → 服务端走距离分支；不带 → 按最新（FR-112.2 明定的正常态，不是降级）。
  ///
  /// 🔴 **两个坐标同时给或同时不给**：只给一个后端回 422（它不静默忽略 ——
  /// 静默忽略会让客户端拿到按最新的列表却以为是按距离排的）。
  ///
  /// 🛡 坐标**只出现在这一个请求的 query 里**：不进埋点、不进日志（NFR-4 / NFR-5）。
  /// ⚠️ 「不进日志」不是自动成立的 —— 两侧的日志都会拼 query string，所以两边都做了**按键打码**：
  /// App 侧 `api_log_interceptor.dart` 的 `_redactQueryKeys`（debug 控制台），
  /// 服务端侧 `ApiAccessLoggingFilter.redactQuery`（prod INFO 落盘、留 14 天，这条更要紧）。
  /// 加新的位置类参数时两处都要加。
  Future<PlaceListResult> fetchPlaces({double? lat, double? lng}) async {
    final withCoords = lat != null && lng != null;
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.places,
      queryParameters: {
        'lat': ?(withCoords ? lat : null),
        'lng': ?(withCoords ? lng : null),
      },
    );
    final data = resp.data;
    if (data == null) {
      // 空响应体当作空列表（走空态），而不是抛错 —— 空库是正常态，不是故障。
      return const PlaceListResult(items: [], sortMode: PlaceSortMode.recent);
    }
    return PlaceListResult.fromJson(data);
  }
}

final placeRepositoryProvider =
    Provider<PlaceRepository>((ref) => PlaceRepository(dio: ref.read(dioProvider)));

/// 列表的族键：一对可空坐标（都为 null = 按最新）。
///
/// 🔴 用 **record** 而不是自定义类：record 天生结构相等，family 的缓存/去重直接就对了；
/// 换成普通类就得手写 `==`/`hashCode`，漏一个就会每次重建都当成新族键、无限重拉
/// （同 `shop_repository.dart` 的 `ShopProductsQuery`）。
typedef PlaceListQuery = ({double? lat, double? lng});

/// 按最新（无坐标）的族键常量 —— 省得各处重复写字面量。
const PlaceListQuery placeListRecentQuery = (lat: null, lng: null);

/// 族键里坐标保留的小数位（3 位 ≈ 110 m）。
///
/// 🔴 **不用原始坐标当族键**：GPS 每次定点都在米级抖动，原始值当键等于每次抖动都产生一个
/// **全新的、没有缓存的 family provider** —— 页面会被打回 loading、整屏列表换成转圈，
/// 正是 F13 要避免的。按 ~110 m 归一之后，站着不动就命中同一个键。
///
/// 距离显示本来也只到百米级（`850 m` / `1,2 km`），这点精度损失看不出来；
/// 顺带少往服务端送几位精度。
const int _queryCoordinatePrecision = 3;

/// 从一对坐标构造族键（null 坐标 → [placeListRecentQuery]）。
PlaceListQuery placeListQueryFor(double? lat, double? lng) {
  if (lat == null || lng == null) return placeListRecentQuery;
  return (lat: _round(lat), lng: _round(lng));
}

double _round(double v) {
  final f = 1000; // 10^_queryCoordinatePrecision
  assert(_queryCoordinatePrecision == 3);
  return (v * f).roundToDouble() / f;
}

/// 场所列表，按坐标分族。
///
/// `autoDispose`：场所列表是从首页入口推进来的一次性页面，退出后没必要留着；
/// 而且坐标每次定位都可能微变 —— 不自动回收的话这些一次性族键会一直挂着。
final placeListProvider = FutureProvider.autoDispose
    .family<PlaceListResult, PlaceListQuery>((ref, q) async {
  return ref.read(placeRepositoryProvider).fetchPlaces(lat: q.lat, lng: q.lng);
});
