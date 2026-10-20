import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/retry_policy.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/health_list_item.dart';

/// 健康记录数据层（Story 7.1/7.2）。混排列表 GET + 结构化记录 CRUD。抽象便于测试注入 fake。
///
/// V1.4.0 multi-pet（Story 1.5 · AC7）：[petId] 指定哪只宠物（`?petId=`），null = 后端默认宠物；
/// 记录须属于所选宠物，否则后端 404。
abstract class HealthRecordRepository {
  /// 混排列表：结构化记录（可编辑）+ 问诊存档（只读）按 event_date 倒序。无档案 → 空/抛。
  Future<List<HealthListItem>> list({int? petId});

  Future<void> create(HealthRecordDraft draft, {int? petId});

  Future<void> update(int id, HealthRecordDraft draft, {int? petId});

  Future<void> delete(int id, {int? petId});
}

class DioHealthRecordRepository implements HealthRecordRepository {
  DioHealthRecordRepository(this.dio);

  final Dio dio;

  @override
  Future<List<HealthListItem>> list({int? petId}) async {
    final res = await dio.get<List<dynamic>>(ApiPaths.withPet(ApiPaths.healthRecords, petId));
    return (res.data ?? const [])
        .map((e) => HealthListItem.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<void> create(HealthRecordDraft draft, {int? petId}) async {
    await dio.post<Map<String, dynamic>>(ApiPaths.withPet(ApiPaths.healthRecords, petId),
        data: draft.toJson());
  }

  @override
  Future<void> update(int id, HealthRecordDraft draft, {int? petId}) async {
    await dio.patch<Map<String, dynamic>>(ApiPaths.withPet('${ApiPaths.healthRecords}/$id', petId),
        data: draft.toJson());
  }

  @override
  Future<void> delete(int id, {int? petId}) async {
    await dio.delete<void>(ApiPaths.withPet('${ApiPaths.healthRecords}/$id', petId));
  }
}

final Provider<HealthRecordRepository> healthRecordRepositoryProvider =
    Provider<HealthRecordRepository>((ref) => DioHealthRecordRepository(ref.read(dioProvider)));

/// 混排健康列表（Story 7.2）。
///
/// ⚠️ `retry: healthListRetry`：Riverpod 3 默认对抛错的 provider 指数退避重试（最多 10 次、约 15s 才落
/// AsyncError）。无宠物档案时后端返回确定性 404（「尚未创建宠物档案」），重试只会让页面转圈 15 秒才出
/// 「先建档」空态（如从运营通知 APP_PAGE=HEALTH 跳入）。4xx 不重试、立即进错误态；网络错误 / 5xx 仍走默认重试。
///
/// V1.4.0 multi-pet（Story 1.5）：family 键 = petId（`effectivePetIdProvider`）。
final healthListProvider = FutureProvider.family<List<HealthListItem>, int>(
  (ref, petId) => ref.read(healthRecordRepositoryProvider).list(petId: petId),
  retry: healthListRetry,
);

/// [healthListProvider] 的重试策略：见 [retryExcept4xx]（4xx 不重试、其余走默认）。
Duration? healthListRetry(int retryCount, Object error) => retryExcept4xx(retryCount, error);
