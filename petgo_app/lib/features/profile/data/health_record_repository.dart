import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/health_list_item.dart';

/// 健康记录数据层（Story 7.1/7.2）。混排列表 GET + 结构化记录 CRUD。抽象便于测试注入 fake。
abstract class HealthRecordRepository {
  /// 混排列表：结构化记录（可编辑）+ 问诊存档（只读）按 event_date 倒序。无档案 → 空/抛。
  Future<List<HealthListItem>> list();

  Future<void> create(HealthRecordDraft draft);

  Future<void> update(int id, HealthRecordDraft draft);

  Future<void> delete(int id);
}

class DioHealthRecordRepository implements HealthRecordRepository {
  DioHealthRecordRepository(this.dio);

  final Dio dio;

  @override
  Future<List<HealthListItem>> list() async {
    final res = await dio.get<List<dynamic>>(ApiPaths.healthRecords);
    return (res.data ?? const [])
        .map((e) => HealthListItem.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<void> create(HealthRecordDraft draft) async {
    await dio.post<Map<String, dynamic>>(ApiPaths.healthRecords, data: draft.toJson());
  }

  @override
  Future<void> update(int id, HealthRecordDraft draft) async {
    await dio.patch<Map<String, dynamic>>('${ApiPaths.healthRecords}/$id', data: draft.toJson());
  }

  @override
  Future<void> delete(int id) async {
    await dio.delete<void>('${ApiPaths.healthRecords}/$id');
  }
}

final Provider<HealthRecordRepository> healthRecordRepositoryProvider =
    Provider<HealthRecordRepository>((ref) => DioHealthRecordRepository(ref.read(dioProvider)));

/// 混排健康列表（Story 7.2）。
final FutureProvider<List<HealthListItem>> healthListProvider =
    FutureProvider<List<HealthListItem>>((ref) => ref.read(healthRecordRepositoryProvider).list());
