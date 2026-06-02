import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 他人迷你主页投影（对应后端 `MiniProfileResponse`）。V1 仅 nickname/avatar/postCount。
class MiniProfile {
  const MiniProfile({
    required this.postCount,
    required this.isDeactivated,
    this.nickname,
    this.avatarUrl,
  });

  final int postCount;
  final bool isDeactivated;
  final String? nickname;
  final String? avatarUrl;

  factory MiniProfile.fromJson(Map<String, dynamic> json) => MiniProfile(
        postCount: (json['postCount'] ?? 0) as int,
        isDeactivated: (json['isDeactivated'] ?? false) as bool,
        nickname: json['nickname'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
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
