import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/place_comment.dart';
import '../domain/place_detail.dart';
import '../domain/place_list_filter.dart';
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
  ///
  /// 筛选（Story 1.11）：[filter] 非空时追加**可重复**的 `type` / `tag` 参数
  /// （`?type=CAFE&type=PARK&tag=PET_MENU`，dio 的 [ListFormat.multi]）。
  /// 语义在服务端：类型间「或」、标签间「且」；非法值服务端回 422（客户端只送枚举字面量，不会触发）。
  /// 🔴 [filter] 为空时**一个参数都不加** —— 与 1.11 之前的请求一字不差（AC1「不传 = 不筛」）。
  Future<PlaceListResult> fetchPlaces({
    double? lat,
    double? lng,
    PlaceListFilter filter = PlaceListFilter.none,
  }) async {
    final withCoords = lat != null && lng != null;
    final types = filter.typeParams;
    final tags = filter.tagParams;
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.places,
      queryParameters: {
        'lat': ?(withCoords ? lat : null),
        'lng': ?(withCoords ? lng : null),
        if (types.isNotEmpty) 'type': types,
        if (tags.isNotEmpty) 'tag': tags,
      },
      // 显式钉 multi：不依赖 BaseOptions 的默认值（改成 csv 的话服务端会把 "CAFE,PARK" 当成一个非法值回 422）。
      options: filter.isEmpty ? null : Options(listFormat: ListFormat.multi),
    );
    final data = resp.data;
    if (data == null) {
      // 空响应体当作空列表（走空态），而不是抛错 —— 空库是正常态，不是故障。
      return const PlaceListResult(items: [], sortMode: PlaceSortMode.recent);
    }
    return PlaceListResult.fromJson(data);
  }

  /// 标记一个场所（Story 1.3）。返回新场所的不可枚举 token。
  ///
  /// 🔴 **没有、也不会有 `updatePlace` / `deletePlace`**：本版用户不可修改、不可删除自己标记的
  /// 场所（2026-09-15 拍板），服务端也不提供那两个端点（后端有一条反射测试钉着）。
  /// 纠错走后台 AB-17A。
  ///
  /// 校验失败（422）与审核硬拦截（TEXT/IMAGE_BLOCKED）都以 [DioException] 抛给页面。
  /// ⚠️ 客户端在必填未满时**根本不发这个请求**（保存按钮是灰的，AC2/AC7），
  /// 所以真正走到 422 的只有「客户端与服务端口径漂了」这一种情况 —— 那是 bug，不是用户错误。
  Future<String> createPlace({
    required String name,
    required PlaceType type,
    required List<PlaceTag> tags,
    required double latitude,
    required double longitude,
    required String addressText,
    required List<String> photoUrls,
    String? description,
    String? idempotencyKey,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.places,
      // 🔴 **幂等键是必须的**：用户既不能编辑也不能删除自己标记的场所。丢一个 201
      // （弱网下很常见）+ 用户再点一次保存 = 一个**永久重复**的场所，只能等运营去后台合并。
      // 同一次提交必须复用同一个 key —— 所以它由调用方（页面）生成并持有，不在这里造。
      options: idempotencyKey == null
          ? null
          : Options(headers: {'Idempotency-Key': idempotencyKey}),
      data: {
        'name': name,
        'type': type.api,
        'tags': tags.map((t) => t.api).toList(growable: false),
        'latitude': latitude,
        'longitude': longitude,
        'addressText': addressText,
        'photoUrls': photoUrls,
        'description': ?description,
      },
    );
    final token = resp.data?['token']?.toString();
    if (token == null || token.isEmpty) {
      // 服务端契约是「201 + token」。拿不到 token 说明契约漂了 ——
      // 不要静默当成功：调用方会跳去一个 token 为空的详情页。
      throw StateError('创建场所成功但响应里没有 token');
    }
    return token;
  }

  /// 场所详情（Story 1.5）。
  ///
  /// 🔴 **下架与不存在都是 404**（后端刻意不可区分，防泄漏「这个 token 曾经存在」）——
  /// 页面把 404 渲染成统一的「场所不存在」空态，不区分两种情况。
  Future<PlaceDetail> fetchDetail(String token, {double? lat, double? lng}) async {
    final withCoords = lat != null && lng != null;
    final resp = await dio.get<Map<String, dynamic>>(
      '${ApiPaths.places}/$token',
      queryParameters: {
        'lat': ?(withCoords ? lat : null),
        'lng': ?(withCoords ? lng : null),
      },
    );
    return PlaceDetail.fromJson(resp.data ?? const {});
  }

  /// 场所评论列表（Story 1.7）。🔒 **游客可读**（后端 GET 放行）。
  ///
  /// [cursor] 为空取第一页；下一页传上一页的 `nextCursor`。
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      '${ApiPaths.places}/$token/comments',
      queryParameters: {'cursor': ?cursor},
    );
    final data = resp.data;
    if (data == null) return PlaceCommentPage.empty;
    return PlaceCommentPage.fromJson(data);
  }

  /// 发表一条场所评论（Story 1.7 · AC2/AC3）。
  ///
  /// 🔴 **没有 `parentId` 参数，也不会有**：场所评论只有一级（PRD ③）。
  ///
  /// [attitude] 可空 —— 可以不表态（AC3）。反过来**不能只表态不写评论**（B1-D3），
  /// 所以正文是必填的位置参数。
  ///
  /// 审核拦截（422 `comment-blocked`）与场所已下架（404）都以 [DioException] 抛给页面 ——
  /// 两者的提示文案不同，判别在页面侧（同 `comment_composer.dart` 的既定处理）。
  Future<PlaceComment> createComment(
    String token,
    String body, {
    PlaceCommentAttitude? attitude,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      '${ApiPaths.places}/$token/comments',
      data: {'body': body, 'attitude': ?attitude?.api},
    );
    return PlaceComment.fromJson(resp.data ?? const {});
  }

  /// 删除**自己的**场所评论（Story 1.7 · AC7）。
  ///
  /// 🔒 「是不是本人」由**服务端**校验（403）——客户端的 `mine` 只决定画不画这个入口。
  Future<void> deleteComment(int commentId) async {
    await dio.delete<void>('${ApiPaths.placeComments}/$commentId');
  }

  /// 为场所补充照片（Story 1.9 · AC1/AC3）。
  ///
  /// 🔴 **谁都能补**，不只是标记人 —— 场所是共享的地点条目（服务端也不做那个判断）。
  ///
  /// ⚠️ 传进来的是**已经上传到公开桶**的 URL（走既有 `MediaUploadUseCase`）。
  /// 补充的照片落挂起、过审才对他人可见 —— 所以调用方拿到成功后要提示
  /// 「审核中」而不是「已发布」。
  Future<void> contributePhotos(String token, List<String> photoUrls) async {
    await dio.post<void>(
      '${ApiPaths.places}/$token/photos',
      data: {'photoUrls': photoUrls},
    );
  }

  /// 删除**自己传的**那张照片（Story 1.9）。
  ///
  /// 🔒 「是不是本人」由服务端校验（403）—— 标记人也不能删别人补的照片。
  Future<void> deletePhoto(int photoId) async {
    await dio.delete<void>('${ApiPaths.placePhotos}/$photoId');
  }

  /// 举报一个场所（Story 1.5 · AC5）。
  ///
  /// 复用**既有五类原因**的线格式（`ReportReason.wire`）—— 抽屉文案一字不改，
  /// 取值域也必须是同一套。后端写工单 PENDING 进运营队列，不自动下架；重复举报幂等。
  Future<void> reportPlace(String token, String reasonWire) async {
    await dio.post<void>(
      '${ApiPaths.places}/$token/reports',
      data: {'reasonType': reasonWire},
    );
  }
}

final placeRepositoryProvider =
    Provider<PlaceRepository>((ref) => PlaceRepository(dio: ref.read(dioProvider)));

/// 列表的族键：一对可空坐标（都为 null = 按最新）+ 筛选条件（Story 1.11 · AC10）。
///
/// 🔴 用 **record** 而不是自定义类：record 天生结构相等，family 的缓存/去重直接就对了；
/// 换成普通类就得手写 `==`/`hashCode`，漏一个就会每次重建都当成新族键、无限重拉
/// （同 `shop_repository.dart` 的 `ShopProductsQuery`）。
/// ⚠️ `filter` 字段是 [PlaceListFilter]（自带无序集合的结构相等）—— **别换成裸 Set/List**，
/// 那两者的 `==` 是身份相等，会让 record 的结构相等失效。
typedef PlaceListQuery = ({double? lat, double? lng, PlaceListFilter filter});

/// 按最新（无坐标、无筛选）的族键常量 —— 省得各处重复写字面量。
const PlaceListQuery placeListRecentQuery =
    (lat: null, lng: null, filter: PlaceListFilter.none);

/// 族键里坐标保留的小数位（3 位 ≈ 110 m）。
///
/// 🔴 **不用原始坐标当族键**：GPS 每次定点都在米级抖动，原始值当键等于每次抖动都产生一个
/// **全新的、没有缓存的 family provider** —— 页面会被打回 loading、整屏列表换成转圈，
/// 正是 F13 要避免的。按 ~110 m 归一之后，站着不动就命中同一个键。
///
/// 距离显示本来也只到百米级（`850 m` / `1,2 km`），这点精度损失看不出来；
/// 顺带少往服务端送几位精度。
const int _queryCoordinatePrecision = 3;

/// 从一对坐标 + 筛选构造族键（null 坐标 → 按最新；无筛选时即 [placeListRecentQuery]）。
PlaceListQuery placeListQueryFor(double? lat, double? lng,
    {PlaceListFilter filter = PlaceListFilter.none}) {
  if (lat == null || lng == null) return (lat: null, lng: null, filter: filter);
  return (lat: _round(lat), lng: _round(lng), filter: filter);
}

double _round(double v) {
  final f = 1000; // 10^_queryCoordinatePrecision
  assert(_queryCoordinatePrecision == 3);
  return (v * f).roundToDouble() / f;
}

/// 场所列表，按坐标 + 筛选分族（改筛选 = 新请求；同一组筛选命中缓存，AC10）。
///
/// `autoDispose`：场所列表是从首页入口推进来的一次性页面，退出后没必要留着；
/// 而且坐标每次定位都可能微变 —— 不自动回收的话这些一次性族键会一直挂着。
final placeListProvider = FutureProvider.autoDispose
    .family<PlaceListResult, PlaceListQuery>((ref, q) async {
  return ref
      .read(placeRepositoryProvider)
      .fetchPlaces(lat: q.lat, lng: q.lng, filter: q.filter);
});

/// 场所详情（按 token + 可选坐标分族）。
///
/// `autoDispose`：详情是 push 进来的一次性页面。
typedef PlaceDetailQuery = ({String token, double? lat, double? lng});

/// 从 token + 一对坐标构造详情族键。
///
/// 🔴 **坐标必须与列表页同一套归一规则**（[_queryCoordinatePrecision]，~110 m）：
/// 用原始坐标当键的话，GPS 每次米级抖动都会造出一个**全新的、没有缓存的 family provider** ——
/// 已经渲染好的详情会被整屏转圈顶掉，再发一次请求（F13 要避免的正是这个）。
/// 顺带也少往服务端送几位精度（详情与列表的精度口径因此一致）。
PlaceDetailQuery placeDetailQueryFor(String token, double? lat, double? lng) {
  if (lat == null || lng == null) return (token: token, lat: null, lng: null);
  return (token: token, lat: _round(lat), lng: _round(lng));
}

final placeDetailProvider = FutureProvider.autoDispose
    .family<PlaceDetail, PlaceDetailQuery>((ref, q) async {
  return ref.read(placeRepositoryProvider).fetchDetail(q.token, lat: q.lat, lng: q.lng);
});
