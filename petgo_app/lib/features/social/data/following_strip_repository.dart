import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../growth/domain/growth_badge.dart';

/// 关注头像排的一个人（V1.3.4 following 流）。
class FollowingStripUser {
  const FollowingStripUser({
    required this.userId,
    required this.userToken,
    this.nickname,
    this.avatarUrl,
    this.badge,
  });

  final int userId;
  final String userToken;
  final String? nickname;
  final String? avatarUrl;

  /// 等级角标 + 头像框（V1.4.0 ① Story 3.2）；注销 / 停用 / 字段缺失为 null（不显示）。
  final GrowthBadge? badge;

  factory FollowingStripUser.fromJson(Map<String, dynamic> json) => FollowingStripUser(
        userId: (json['userId'] as num).toInt(),
        userToken: json['userToken'] as String,
        nickname: json['nickname'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        badge: GrowthBadge.fromFlat(json, prefix: ''),
      );
}

/// `GET /api/v1/me/following-strip`（最多 20 人，后端已排好序）。
abstract class FollowingStripRepository {
  Future<List<FollowingStripUser>> fetch();
}

class DioFollowingStripRepository implements FollowingStripRepository {
  DioFollowingStripRepository(this.dio);

  final Dio dio;

  @override
  Future<List<FollowingStripUser>> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meFollowingStrip);
    return ((resp.data?['items'] as List?) ?? const [])
        .map((e) => FollowingStripUser.fromJson((e as Map).cast<String, dynamic>()))
        .toList(growable: false);
  }
}

final Provider<FollowingStripRepository> followingStripRepositoryProvider =
    Provider<FollowingStripRepository>((ref) => DioFollowingStripRepository(ref.read(dioProvider)));

/// 关注头像排数据。autoDispose；`retry: null` 关掉自动重试（失败由 UI 静默隐藏头像排）。
final followingStripProvider = FutureProvider.autoDispose<List<FollowingStripUser>>(
  (ref) => ref.read(followingStripRepositoryProvider).fetch(),
  retry: (_, _) => null,
);
