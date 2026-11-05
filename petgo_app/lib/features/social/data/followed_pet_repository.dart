import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/followed_pet.dart';

/// 「某用户追更的宠物」列表数据层（V1.4.2 social-topic · Story 1.5 · D-8）。
///
/// ⚠️ 与 `PetFollowRepository`（追更 / 取消）**分开**：读写两件事，同 `FollowListRepository` / `FollowRepository`。
abstract class FollowedPetRepository {
  Future<FollowedPetPage> fetch(String userToken, {String? cursor});
}

class DioFollowedPetRepository implements FollowedPetRepository {
  DioFollowedPetRepository(this.dio);

  final Dio dio;

  @override
  Future<FollowedPetPage> fetch(String userToken, {String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.userPetFollows(userToken),
        queryParameters: {'cursor': ?cursor});
    return FollowedPetPage.fromJson(resp.data ?? const <String, dynamic>{});
  }
}

final Provider<FollowedPetRepository> followedPetRepositoryProvider =
    Provider<FollowedPetRepository>((ref) => DioFollowedPetRepository(ref.read(dioProvider)));
