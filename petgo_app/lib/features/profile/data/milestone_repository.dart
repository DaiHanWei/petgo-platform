import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/milestone.dart';

/// 里程碑数据层（Story 8.2 · FR-42）。GET 列表/进度（L/M/S 分区 + 完成状态）。
/// 打卡 API（8.4）后续叠加于此接口。
abstract class MilestoneRepository {
  Future<MilestoneList> getMilestones();
}

class DioMilestoneRepository implements MilestoneRepository {
  DioMilestoneRepository(this.dio);

  final Dio dio;

  @override
  Future<MilestoneList> getMilestones() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petProfileMilestones);
    return MilestoneList.fromJson(resp.data!);
  }
}

final Provider<MilestoneRepository> milestoneRepositoryProvider =
    Provider<MilestoneRepository>((ref) => DioMilestoneRepository(ref.read(dioProvider)));

/// 里程碑列表（AsyncValue）。完成后/打卡后失效刷新。
final FutureProvider<MilestoneList> milestoneListProvider = FutureProvider<MilestoneList>(
  (ref) => ref.read(milestoneRepositoryProvider).getMilestones(),
);
