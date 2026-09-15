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

abstract class PetRecommendationRepository {
  /// 取推荐池。🔒 需登录（游客态不展示该区 —— story Dev Notes「游客态不动」）。
  Future<List<RecommendedPet>> recommendations({int? limit});
}

class DioPetRecommendationRepository implements PetRecommendationRepository {
  DioPetRecommendationRepository(this.dio);

  final Dio dio;

  @override
  Future<List<RecommendedPet>> recommendations({int? limit}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.mePetRecommendations,
      queryParameters: limit == null ? null : {'limit': limit},
    );
    final items = resp.data?['items'] as List<dynamic>? ?? const <dynamic>[];
    return items
        .map((e) => RecommendedPet.fromJson((e as Map).cast<String, dynamic>()))
        .toList(growable: false);
  }
}

final Provider<PetRecommendationRepository> petRecommendationRepositoryProvider =
    Provider<PetRecommendationRepository>(
        (ref) => DioPetRecommendationRepository(ref.read(dioProvider)));

/// 推荐池（Story 4.1 · AC6 的 Diary 未建档态用它）。
///
/// ⚠️ 关掉自动重试：取不到时那一整片推荐区**整块不渲染**（它是锦上添花，
/// 不是这一屏的主体 —— 主体是「+ 建档」那两个操作）。让它在后台反复重试
/// 只会让页面在「有一片网格 ↔ 没有」之间自己横跳。
final FutureProvider<List<RecommendedPet>> petRecommendationsProvider =
    FutureProvider<List<RecommendedPet>>(
  (ref) => ref.read(petRecommendationRepositoryProvider).recommendations(),
  retry: (_, _) => null,
);
