import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/milestone.dart';

/// 里程碑数据层（Story 8.2 · FR-42）。GET 列表/进度（L/M/S 分区 + 完成状态）。
/// 打卡 API（8.4）后续叠加于此接口。
abstract class MilestoneRepository {
  Future<MilestoneList> getMilestones();

  /// 名片分享信号（Story 8.3 · C-S3 自动完成）。App 触发系统分享面板后回报；失败静默（非阻断）。
  Future<void> signalCardShared();

  /// 「已打卡」内容关联选择器候选（Story 8.4）：本人成长日历内容，已关联其它里程碑的 linked=true。
  Future<List<MilestoneCheckinCandidate>> getCheckinCandidates();

  /// 用户打卡（Story 8.4）：把一条成长日历内容关联到该里程碑并完成。返回完成后的项（供庆祝 8.5）。
  Future<MilestoneItem> checkIn(String code, int contentId);

  /// 庆祝回报（V1.3.0 Story 1.5 · FR-111 · AD-A3.1）：把**本次庆祝实际展示覆盖的**
  /// code 列表回传，服务端按列表幂等置位 `celebrated_at`。
  ///
  /// ⚠️ **best-effort**：调用方**异步发、失败静默、不重试到用户可感知**，也**不得**
  /// 让庆祝页或发布流程等它。失败的代价只是下次进列表页再补弹一次，可接受；
  /// 把它改成同步阻塞，反而会让发布流程卡在一个可失败的写上。
  Future<void> reportCelebrations(List<String> codes);

  /// P-35 庆祝对外分享：创建 / 刷新该已完成里程碑的分享，返回不可枚举 shareToken。
  /// [title]/[body] 为客户端已本地化好的庆祝文案，[locale] 仅 id/en，
  /// [collectionLevels] 为「已解锁合集」级别串（每字符 S/M/L，按合集顺序），供 H5 复刻 KOLEKSI 区。
  Future<String> createShare(String code,
      {required String title,
      required String body,
      required String locale,
      required String collectionLevels});
}

class DioMilestoneRepository implements MilestoneRepository {
  DioMilestoneRepository(this.dio);

  final Dio dio;

  @override
  Future<MilestoneList> getMilestones() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petProfileMilestones);
    return MilestoneList.fromJson(resp.data!);
  }

  @override
  Future<void> signalCardShared() async {
    await dio.post<void>(ApiPaths.petProfileCardShares);
  }

  @override
  Future<List<MilestoneCheckinCandidate>> getCheckinCandidates() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petProfileMilestoneCandidates);
    return ((resp.data!['items'] ?? const []) as List)
        .map((e) => MilestoneCheckinCandidate.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<MilestoneItem> checkIn(String code, int contentId) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.petProfileMilestoneCheckIn(code),
      data: {'contentId': contentId},
    );
    return MilestoneItem.fromJson(resp.data!);
  }

  @override
  Future<void> reportCelebrations(List<String> codes) async {
    if (codes.isEmpty) return; // 空列表没有回报的意义，省一次请求
    await dio.post<void>(
      ApiPaths.petProfileMilestoneCelebrations,
      data: {'codes': codes},
    );
  }

  @override
  Future<String> createShare(String code,
      {required String title,
      required String body,
      required String locale,
      required String collectionLevels}) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.petProfileMilestoneShares(code),
      data: {'title': title, 'body': body, 'locale': locale, 'collectionLevels': collectionLevels},
    );
    return resp.data!['shareToken'] as String;
  }
}

final Provider<MilestoneRepository> milestoneRepositoryProvider =
    Provider<MilestoneRepository>((ref) => DioMilestoneRepository(ref.read(dioProvider)));

/// 里程碑列表（AsyncValue）。完成后/打卡后失效刷新。
final FutureProvider<MilestoneList> milestoneListProvider = FutureProvider<MilestoneList>(
  (ref) => ref.read(milestoneRepositoryProvider).getMilestones(),
);

/// 「已打卡」内容关联选择器候选（Story 8.4，autoDispose：打开 picker 时拉取）。
final milestoneCheckinCandidatesProvider =
    FutureProvider.autoDispose<List<MilestoneCheckinCandidate>>(
  (ref) => ref.read(milestoneRepositoryProvider).getCheckinCandidates(),
);
