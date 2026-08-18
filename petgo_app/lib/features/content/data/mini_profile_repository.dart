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
    this.reported = false,
    this.tags = const [],
  });

  final int postCount;
  final bool isDeactivated;
  final String? nickname;
  final String? avatarUrl;

  /// 个性签名（用户自填 ≤60 字）。未设置 / 已注销 → null。
  final String? signature;

  /// 当前用户**是否举报过这个人**（V1.1.4 Story 2.1 AC8）。
  ///
  /// ⚠️ 由服务端的举报隐藏行派生、**不是前端会话态** —— 用户重装 App 也要还看得到「已举报」，
  /// 否则他会重复举报同一个人，而每一次都会真的落一行明细、污染运营看到的「12 人 / 27 次」。
  /// 游客的响应体里**根本没有这个键**（后端用可空布尔 + NON_NULL 省略），故默认 false。
  final bool reported;

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
        reported: (json['reported'] ?? false) as bool,
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
