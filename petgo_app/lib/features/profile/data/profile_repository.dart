import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/pet_profile.dart';
import 'current_pet_provider.dart';

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
  ///
  /// V1.4.0 multi-pet：[petId] 指定哪只（`?petId=`）；null = 后端默认宠物（旧口径）。
  Future<PetProfile?> getMyProfile({int? petId});

  /// 编辑档案（Story 2.8，部分更新 PATCH）。cardToken 不变。
  ///
  /// ⚠️ 全字段「传 null = 不改动」，**没有清空语义**（V1.1.6 Story 1.1 沿用该口径新增 [sex]）。
  /// Story 6.1 追加 [weightKg] / [neuterStatus]。
  /// 🔒 体重是 PII 邻近的健康数据 —— **禁止写入日志与埋点属性**（NFR-5）。
  Future<PetProfile> update({
    int? petId,
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
  });

  /// V1.4.0 multi-pet（Story 1.3）：[petId] 只删这一只；null = 后端默认宠物。
  ///
  /// 删除当前用户档案（bug 20260702-237 / 决策 F18）。后端级联删派生数据 + 名片失效 + 清理个人图，
  /// 保留 UGC；petStatus 不变（删后落空档案态可重建/切换）。成功 204。
  Future<void> deleteMyProfile({int? petId});
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
    int? petId,
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
    final resp = await dio.patch<Map<String, dynamic>>(ApiPaths.withPet(ApiPaths.petProfileMe, petId), data: data);
    return PetProfile.fromJson(resp.data!);
  }

  @override
  Future<void> deleteMyProfile({int? petId}) async {
    await dio.delete<void>(ApiPaths.withPet(ApiPaths.petProfileMe, petId));
  }

  @override
  Future<PetProfile?> getMyProfile({int? petId}) async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.withPet(ApiPaths.petProfileMe, petId));
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

/// **当前宠物**的档案（AsyncValue）—— V1.4.0 multi-pet 起是**派生兼容层**（Story 1.5 · AD-14）。
///
/// 🔴 新代码**不再引用它**：当前宠物一律读 `effectivePetIdProvider`（`current_pet_provider.dart`），
/// 列表读 `petListProvider`。它只为存量 19 个使用文件保持「当前那一只的档案」语义不变。
///
/// 取数：`effectivePetIdProvider` → null（没有宠物）返回 null；否则 `getMyProfile(petId: id)`。
/// ⚠️ 宠物列表取不到（网络 / 接口失败）时**退回旧口径** `getMyProfile()`（后端默认宠物）——
/// 列表失败不该把有宠用户误判成「没有档案」、催他再建一只。
final FutureProvider<PetProfile?> petProfileProvider = FutureProvider<PetProfile?>((ref) async {
  final repo = ref.read(profileRepositoryProvider);
  final int? petId;
  try {
    petId = await ref.watch(effectivePetIdProvider.future);
  } catch (_) {
    return repo.getMyProfile();
  }
  if (petId == null) return null;
  return repo.getMyProfile(petId: petId);
});

/// 按 petId 取**指定宠物**的完整档案（V1.4.0 multi-pet Story 4.2 · AC1.2 / AC3.2，`GET /pet-profiles/me?petId=`）。
///
/// 给「选了某只宠物、要它完整资料」的页面用（KTP 建卡预填：列表项没有品种 / 性别）；不属本人 / 已删 → null。
/// **autoDispose**：没人监听即释放（换账号后不在内存里残留上个账号的宠物资料；下次取就是最新档案）。
/// 命令式调用方取数期间需 `listenManual` 保活（见 KTP 建卡页 `_fetchPetProfile`）。
/// 不自动重试：一次性取数，失败由调用方提示（KTP 换宠物 → toast、草稿不变）。
final petProfileByIdProvider = FutureProvider.autoDispose.family<PetProfile?, int>(
  (ref, petId) => ref.read(profileRepositoryProvider).getMyProfile(petId: petId),
  retry: (_, _) => null,
);
