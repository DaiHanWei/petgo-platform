import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/login_response.dart';

/// 当前用户资料数据层（GET/PATCH /api/v1/me）。抽象便于测试注入 fake。
abstract class MeRepository {
  Future<UserProfile> getMe();
  Future<UserProfile> updateNickname(String nickname);
  Future<UserProfile> updatePetStatus(String petStatus);
}

class DioMeRepository implements MeRepository {
  DioMeRepository(this.dio);

  final Dio dio;

  @override
  Future<UserProfile> getMe() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.me);
    return UserProfile.fromJson(resp.data!);
  }

  @override
  Future<UserProfile> updateNickname(String nickname) => _patch({'nickname': nickname});

  @override
  Future<UserProfile> updatePetStatus(String petStatus) => _patch({'petStatus': petStatus});

  Future<UserProfile> _patch(Map<String, dynamic> body) async {
    final resp = await dio.patch<Map<String, dynamic>>(ApiPaths.me, data: body);
    return UserProfile.fromJson(resp.data!);
  }
}

final Provider<MeRepository> meRepositoryProvider =
    Provider<MeRepository>((ref) => DioMeRepository(ref.read(dioProvider)));
