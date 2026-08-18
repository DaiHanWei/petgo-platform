import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../auth/domain/user_tag.dart';

/// 他人迷你主页投影（对应后端 `MiniProfileResponse`）。V1 仅 nickname/avatar/signature/postCount。
class MiniProfile {
  const MiniProfile({
    required this.postCount,
    required this.isDeactivated,
    this.nickname,
    this.avatarUrl,
    this.signature,
    this.tags = const [],
  });

  final int postCount;
  final bool isDeactivated;
  final String? nickname;
  final String? avatarUrl;

  /// 个性签名（用户自填 ≤60 字）。未设置 / 已注销 → null。
  final String? signature;

  /// 运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销时为空。
  final List<UserTag> tags;

  /// 是否有可展示的签名（空串与纯空白按「没设置」处理，免得卡片上留一片空白）。
  bool get hasSignature => signature?.trim().isNotEmpty == true;

  factory MiniProfile.fromJson(Map<String, dynamic> json) => MiniProfile(
        postCount: (json['postCount'] ?? 0) as int,
        isDeactivated: (json['isDeactivated'] ?? false) as bool,
        nickname: json['nickname'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        signature: json['signature'] as String?,
        tags: UserTag.listFromJson(json['tags']),
      );
}

/// 迷你主页数据层（Story 3.8）。只读、游客可调。
abstract class MiniProfileRepository {
  Future<MiniProfile> getMiniProfile(int userId);
}

class DioMiniProfileRepository implements MiniProfileRepository {
  DioMiniProfileRepository(this.dio);

  final Dio dio;

  @override
  Future<MiniProfile> getMiniProfile(int userId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.userMiniProfile(userId));
    return MiniProfile.fromJson(resp.data!);
  }
}

final Provider<MiniProfileRepository> miniProfileRepositoryProvider =
    Provider<MiniProfileRepository>((ref) => DioMiniProfileRepository(ref.read(dioProvider)));
