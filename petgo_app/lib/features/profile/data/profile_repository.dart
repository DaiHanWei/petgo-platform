import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/pet_profile.dart';

/// 宠物档案数据层（Story 2.2 · F2）。创建 + 当前用户档案查询。抽象便于测试注入 fake。
abstract class ProfileRepository {
  /// 创建档案。[idempotencyKey] 经 `Idempotency-Key` 头去重（防并发双开窗）。
  /// [petType]（F6，CAT/DOG/OTHER）与 [name]/[birthday] 必填（服务端权威校验）。
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    /// 🔒 体重（kg），Story 6.1。**选填 —— 建档流程可跳过**：
    /// 设为必填会挡住既有建档转化，而建档转化在漏斗上比推荐精度重要得多。
    double? weightKg,
    String? neuterStatus,

    /// 性别（bug 20260827）。**选填** —— 与体重同一理由：建档转化比字段完整度重要。
    /// 此前只有编辑接口能填，于是身份证卡上「性别」永远是空。
    String? sex,
    String? idempotencyKey,
  });

  /// 当前用户档案；不存在返回 null（后端 404 归一）。
  Future<PetProfile?> getMyProfile();

  /// 编辑档案（Story 2.8，部分更新 PATCH）。cardToken 不变。
  ///
  /// ⚠️ 全字段「传 null = 不改动」，**没有清空语义**（V1.1.6 Story 1.1 沿用该口径新增 [sex]）。
  /// Story 6.1 追加 [weightKg] / [neuterStatus]。
  /// 🔒 体重是 PII 邻近的健康数据 —— **禁止写入日志与埋点属性**（NFR-5）。
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
  });

  /// 删除当前用户档案（bug 20260702-237 / 决策 F18）。后端级联删派生数据 + 名片失效 + 清理个人图，
  /// 保留 UGC；petStatus 不变（删后落空档案态可重建/切换）。成功 204。
  Future<void> deleteMyProfile();
}

class DioProfileRepository implements ProfileRepository {
  DioProfileRepository(this.dio);

  final Dio dio;

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    String? sex,
    String? idempotencyKey,
  }) async {
    final data = <String, dynamic>{
      'petType': petType,
      'name': name,
      'birthday': _isoDate(birthday),
    };
    if (avatarUrl != null) data['avatarUrl'] = avatarUrl;
    if (breed != null) data['breed'] = breed;
    if (intro != null) data['intro'] = intro;
    // Story 6.1：体重【可跳过】—— 建档流程不设必填，设必填会挡住建档转化
    if (weightKg != null) data['weightKg'] = weightKg;
    if (neuterStatus != null) data['neuterStatus'] = neuterStatus;
    if (sex != null) data['sex'] = sex;
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.petProfiles,
      data: data,
      options: idempotencyKey == null
          ? null
          : Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
    );
    return PetProfile.fromJson(resp.data!);
  }

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
  }) async {
    final data = <String, dynamic>{};
    if (name != null) data['name'] = name;
    if (avatarUrl != null) data['avatarUrl'] = avatarUrl;
    if (breed != null) data['breed'] = breed;
    if (birthday != null) data['birthday'] = _isoDate(birthday);
    if (sex != null) data['sex'] = sex;
    if (intro != null) data['intro'] = intro;
    if (weightKg != null) data['weightKg'] = weightKg;
    if (neuterStatus != null) data['neuterStatus'] = neuterStatus;
    final resp = await dio.patch<Map<String, dynamic>>(ApiPaths.petProfileMe, data: data);
    return PetProfile.fromJson(resp.data!);
  }

  @override
  Future<void> deleteMyProfile() async {
    await dio.delete<void>(ApiPaths.petProfileMe);
  }

  @override
  Future<PetProfile?> getMyProfile() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petProfileMe);
      return PetProfile.fromJson(resp.data!);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  /// 生日只取日期（yyyy-MM-dd），与后端 `LocalDate` 对齐。
  static String _isoDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}

final Provider<ProfileRepository> profileRepositoryProvider =
    Provider<ProfileRepository>((ref) => DioProfileRepository(ref.read(dioProvider)));

/// 当前用户档案（AsyncValue）。支撑「已有档案直达」守卫。
final FutureProvider<PetProfile?> petProfileProvider = FutureProvider<PetProfile?>(
  (ref) => ref.read(profileRepositoryProvider).getMyProfile(),
);
