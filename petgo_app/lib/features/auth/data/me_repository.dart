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
  Future<UserProfile> updateAvatar(String avatarUrl);

  /// 一次保存昵称 + 一句话签名（bug 20260721-327，编辑资料抽屉用）。
  Future<UserProfile> updateProfile({String? nickname, String? signature});

  /// 保存手机号（V1.1.6 Story 7.1 · FR-70）。
  ///
  /// 🔴 **传空串 = 清空（撤回）**，不是"不改" —— 后端按这个约定分流。
  /// 若传 null 就变成"这次不动手机号"，撤回权会静默落空。
  Future<UserProfile> updatePhone(String phone);
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

  @override
  Future<UserProfile> updateAvatar(String avatarUrl) => _patch({'avatarUrl': avatarUrl});

  @override
  Future<UserProfile> updateProfile({String? nickname, String? signature}) {
    final body = <String, dynamic>{};
    if (nickname != null) body['nickname'] = nickname;
    if (signature != null) body['signature'] = signature;
    return _patch(body);
  }

  @override
  Future<UserProfile> updatePhone(String phone) => _patch({'phone': phone});

  Future<UserProfile> _patch(Map<String, dynamic> body) async {
    final resp = await dio.patch<Map<String, dynamic>>(ApiPaths.me, data: body);
    return UserProfile.fromJson(resp.data!);
  }
}

final Provider<MeRepository> meRepositoryProvider =
    Provider<MeRepository>((ref) => DioMeRepository(ref.read(dioProvider)));
