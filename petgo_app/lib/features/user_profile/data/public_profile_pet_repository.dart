import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 他人公开主页上的**宠物卡片**（对应后端 `PublicProfilePetResponse`）。
/// V1.3.0 batch-b1 Story 2.3 · FR-118.2 · AC3。
///
/// ## 🛡 这里没有、也不该有分享 token
/// B1-D1 否掉了「由主页下发对方宠物的分享链接码」：那是一条**永久公开、可转发到站外**
/// 的链接。**站内可见 ≠ 可对外分发。** 站内入口按 [petId] 走另一条仅登录可用的地址。
///
/// ## ⚠️ 没有 Tailsonality 角色小标
/// FR-117 在批次 B2，本批次这个位置是**天然空状态**，**不做占位设计**（AC3 原文）。
class PublicProfilePet {
  const PublicProfilePet({
    required this.petId,
    required this.name,
    required this.diaryCount,
    this.avatarUrl,
    this.petType,
    this.birthday,
  });

  /// 拿它进站内访客视图（AD-4 Rule 1 明写按 petId 寻址）。
  final int petId;

  final String name;
  final String? avatarUrl;

  /// 物种线格式：CAT / DOG / OTHER。客户端本地化成「Kucing」/「Anjing」。
  final String? petType;

  /// 生日。⚠️ 服务端下发的是**日期**不是算好的「2th 3bln」——
  /// 算好的字符串每过一天就得靠缓存失效才准。
  final DateTime? birthday;

  /// 该宠物的 Diary 条数。与点进去之后统计条上那个数**同一个实现**。
  final int diaryCount;

  factory PublicProfilePet.fromJson(Map<String, dynamic> json) => PublicProfilePet(
        petId: (json['petId'] as num).toInt(),
        name: (json['name'] ?? '') as String,
        avatarUrl: json['avatarUrl'] as String?,
        petType: json['petType'] as String?,
        birthday: DateTime.tryParse((json['birthday'] ?? '') as String? ?? ''),
        diaryCount: (json['diaryCount'] ?? 0) as int,
      );
}

abstract class PublicProfilePetRepository {
  /// @return 没建过档案 / 主人注销或被封 → null（服务端 204）
  Future<PublicProfilePet?> fetch(int userId);
}

class DioPublicProfilePetRepository implements PublicProfilePetRepository {
  DioPublicProfilePetRepository(this.dio);

  final Dio dio;

  @override
  Future<PublicProfilePet?> fetch(int userId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.userPublicPet(userId));
    // 🛡 204 时 dio 给的是 null body —— 「这个人没建过档案」是正常状态，不是错误。
    final data = resp.data;
    return data == null || data.isEmpty ? null : PublicProfilePet.fromJson(data);
  }
}

final Provider<PublicProfilePetRepository> publicProfilePetRepositoryProvider =
    Provider<PublicProfilePetRepository>(
        (ref) => DioPublicProfilePetRepository(ref.read(dioProvider)));

/// 某个用户主页上的宠物卡（family：userId）。
///
/// ⚠️ `isAutoDispose: true` + 关掉自动重试，理由同 `publicProfileProvider`
/// （Riverpod 3 的 family 默认 keep-alive；换账号会串数据，失败态还会自己横跳）。
final publicProfilePetProvider = FutureProvider.family<PublicProfilePet?, int>(
  (ref, userId) => ref.read(publicProfilePetRepositoryProvider).fetch(userId),
  retry: (_, _) => null,
  isAutoDispose: true,
);
