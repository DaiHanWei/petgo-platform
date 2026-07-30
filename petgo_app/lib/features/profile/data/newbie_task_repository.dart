import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/newbie_tasks.dart';

/// 新手任务数据层（Story 7.3 · FR-47）。GET 6 任务进度 + Lulus Pemula 解锁态。
abstract class NewbieTaskRepository {
  Future<NewbieTasks> getNewbieTasks();
}

class DioNewbieTaskRepository implements NewbieTaskRepository {
  DioNewbieTaskRepository(this.dio);

  final Dio dio;

  @override
  Future<NewbieTasks> getNewbieTasks() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meNewbieTasks);
    return NewbieTasks.fromJson(resp.data!);
  }
}

final Provider<NewbieTaskRepository> newbieTaskRepositoryProvider =
    Provider<NewbieTaskRepository>((ref) => DioNewbieTaskRepository(ref.read(dioProvider)));

/// 新手任务进度（AsyncValue）。里程碑完成/打卡后随里程碑列表一并失效刷新。
final FutureProvider<NewbieTasks> newbieTasksProvider = FutureProvider<NewbieTasks>(
  (ref) => ref.read(newbieTaskRepositoryProvider).getNewbieTasks(),
);
