import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/pet_follow_state.dart';

/// 追更宠物数据层（V1.4.2 social-topic · Story 1.4 · D-8）。
///
/// 路径按 petId（与站内访客路由 `/pets/{petId}` 同一标识）。两个动作服务端都幂等。
/// ⚠️ 错误**不在这里吞**：`DioException` 原样抛给 `PetFollowButton`，由它按 429 / 其他分流 toast 并回滚。
abstract class PetFollowRepository {
  Future<PetFollowResult> follow(int petId);

  Future<PetFollowResult> unfollow(int petId);
}

class DioPetFollowRepository implements PetFollowRepository {
  DioPetFollowRepository(this.dio);

  final Dio dio;

  @override
  Future<PetFollowResult> follow(int petId) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.mePetFollow(petId));
    return PetFollowResult.fromJson(resp.data ?? const <String, dynamic>{});
  }

  @override
  Future<PetFollowResult> unfollow(int petId) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.mePetFollow(petId));
    return PetFollowResult.fromJson(resp.data ?? const <String, dynamic>{});
  }
}

final Provider<PetFollowRepository> petFollowRepositoryProvider =
    Provider<PetFollowRepository>((ref) => DioPetFollowRepository(ref.read(dioProvider)));
