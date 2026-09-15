import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 推荐位上的一只别人家的宠物（V1.3.0 batch-b1 Story 4.1 · AC4）。
///
/// 🔴 **两个图片字段是两个不同来源**（UI 稿 UX-DR15 专门点过）：
/// - [coverImageUrl] = 该宠物**最近一张公开照片**（帖子配图）→ 卡片大图；
/// - [avatarUrl] = **宠物档案自身**的头像 → 左下角小圆头像。
///
/// 做成同一张图重复摆放是明显 bug。[avatarUrl] 恒非空（「有头像」是入池门槛），
/// [coverImageUrl] 可空（公开记录全是纯文字的宠物照样在池子里）。
class RecommendedPet {
  const RecommendedPet({
    required this.petId,
    required this.name,
    required this.avatarUrl,
    required this.petType,
    required this.companionDays,
    this.birthday,
    this.coverImageUrl,
  });

  /// 点击落点用它 —— 复用 Story 2.3 的**站内**访客入口 `/pets/{petId}`，不新建通道（AC5）。
  final int petId;
  final String name;

  /// 宠物档案头像（小圆头像）。
  final String avatarUrl;
  final String petType;

  /// 陪伴天数（「一起 238 天」）—— 后端与 H5 名片同一个算法算好下发。
  final int companionDays;

  /// 生日；年龄文案由客户端那份 `pet_age.dart` 算（全 App 唯一出口，本地化也在那儿）。
  final DateTime? birthday;

  /// 最近一张公开照片（卡片大图）。可空 → 客户端按占位渲染。
  final String? coverImageUrl;

  factory RecommendedPet.fromJson(Map<String, dynamic> json) => RecommendedPet(
        petId: json['petId'] as int,
        name: (json['name'] ?? '') as String,
        avatarUrl: (json['avatarUrl'] ?? '') as String,
        petType: (json['petType'] ?? '') as String,
        companionDays: (json['companionDays'] ?? 0) as int,
        birthday: json['birthday'] == null
            ? null
            : DateTime.tryParse(json['birthday'] as String),
        coverImageUrl: json['coverImageUrl'] as String?,
      );
}

/// 一页推荐宠物（V1.3.0 batch-b1 Story 4.3 · AC3 起带游标）。
///
/// 🔴 [nextCursor] 是服务端给的 base64url 串，**原样回传，不要解析、不要自己拼**。
///
/// 🔴 判「到底了」**只看 [hasMore]**，不看 `items.isEmpty`：一页里的宠物全被服务端
/// 过滤掉（拉黑 / 注销 / 没头像）时会回**空 items + 有游标 + hasMore=true** ——
/// 把空 items 当作到底的表现是「列表在第二页莫名其妙断掉，而后面明明还有」。
class RecommendedPetPage {
  const RecommendedPetPage({required this.items, required this.hasMore, this.nextCursor});

  final List<RecommendedPet> items;
  final String? nextCursor;
  final bool hasMore;

  static const RecommendedPetPage empty =
      RecommendedPetPage(items: <RecommendedPet>[], hasMore: false);

  factory RecommendedPetPage.fromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    final items = raw is! List
        ? const <RecommendedPet>[]
        : raw
            .whereType<Map>()
            .map((e) => RecommendedPet.fromJson(Map<String, dynamic>.from(e)))
            .toList(growable: false);
    final cursor = json['nextCursor']?.toString();
    return RecommendedPetPage(
      items: items,
      nextCursor: (cursor == null || cursor.isEmpty) ? null : cursor,
      hasMore: json['hasMore'] == true,
    );
  }

  /// 追加下一页（游标分页的累积，同 `PlaceCommentPage.append`）。
  RecommendedPetPage append(RecommendedPetPage next) => RecommendedPetPage(
        items: [...items, ...next.items],
        nextCursor: next.nextCursor,
        hasMore: next.hasMore,
      );
}

abstract class PetRecommendationRepository {
  /// 取推荐池的一页。🔒 需登录（游客态不展示该区 —— story Dev Notes「游客态不动」）。
  ///
  /// [cursor] 为空 = 第一页。
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor});
}

class DioPetRecommendationRepository implements PetRecommendationRepository {
  DioPetRecommendationRepository(this.dio);

  final Dio dio;

  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.mePetRecommendations,
      queryParameters: {
        'limit': ?limit,
        // 空串不是游标 —— 传上去会被服务端当坏游标（虽然它会宽容地当第一页处理）。
        if (cursor != null && cursor.isNotEmpty) 'cursor': cursor,
      },
    );
    return RecommendedPetPage.fromJson(resp.data ?? const <String, dynamic>{});
  }
}

final Provider<PetRecommendationRepository> petRecommendationRepositoryProvider =
    Provider<PetRecommendationRepository>(
        (ref) => DioPetRecommendationRepository(ref.read(dioProvider)));

/// 推荐池（Story 4.1 的 Diary 未建档态、4.2 的「声明未养宠 / 计划养宠」态用它）。
///
/// ⚠️ 关掉自动重试：取不到时那一整片推荐区**整块不渲染**（它是锦上添花，
/// 不是这一屏的主体 —— 主体是「+ 建档」那两个操作）。让它在后台反复重试
/// 只会让页面在「有一片网格 ↔ 没有」之间自己横跳。
///
/// 🔴 **必须 autoDispose**（code-review 2026-09-15）：AC3 的「拉黑 / 封号 / 注销不入池」
/// 是**服务端每次取数时**算的，而 Riverpod 3 的 legacy `FutureProvider` 默认常驻 ——
/// 常驻的表现是那几条过滤只在本进程**第一次**取数时生效：在 Feed 里拉黑某人之后回到
/// Diary，他家的宠物卡还在，点进去撞 403。
/// ⚠️ autoDispose 只覆盖「离开这一屏再回来」；**拉黑发生在别的屏、而这一屏还活着**那一路
/// 由 `onAuthorHidden` 里的 invalidate 兜住（那是全 App 拉黑收尾的唯一出口）。
/// ⚠️ 这两处推荐位**只要第一页**（一屏铺满就够，没有「加载更多」），所以只取 items。
/// 全屏集合页的累积翻页归 `PetRecommendationListController`（Story 4.3）——
/// 那种状态不能放在 `FutureProvider` 里：翻到第三页时 invalidate 会把人弹回顶部。
final petRecommendationsProvider = FutureProvider.autoDispose<List<RecommendedPet>>(
  (ref) async =>
      (await ref.read(petRecommendationRepositoryProvider).recommendations()).items,
  retry: (_, _) => null,
);
