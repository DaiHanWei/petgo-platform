import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 账号级里程碑的一项（V1.5.0 onboarding-recall Story 3.2 · OR-10，后端 `AccountMilestoneItemResponse` 镜像）。
class AccountMilestoneEntry {
  const AccountMilestoneEntry({
    required this.flagKey,
    required this.code,
    required this.completed,
    this.completedAt,
    this.pendingCelebration = false,
  });

  /// `FIRST_FOLLOW_USER` / `FIRST_COLLECT_CONTENT` / `LULUS_PEMULA`。
  final String flagKey;

  /// 账号默认宠物物种的编码（无宠物为 CAT 编码；标题 / 徽章按 code 本地化）。
  final String code;
  final bool completed;
  final DateTime? completedAt;

  /// 只对 Lulus Pemula 可能为 true：已解锁、待补弹庆祝（3-3）。
  final bool pendingCelebration;

  factory AccountMilestoneEntry.fromJson(Map<String, dynamic> json) => AccountMilestoneEntry(
        flagKey: (json['flagKey'] ?? '') as String,
        code: (json['code'] ?? '') as String,
        completed: json['completed'] == true,
        completedAt: json['completedAt'] == null ? null : DateTime.tryParse(json['completedAt'] as String),
        pendingCelebration: json['pendingCelebration'] == true,
      );
}

/// `GET /api/v1/me/account-milestones`：固定 3 项 + 计数。
class AccountMilestones {
  const AccountMilestones({required this.items, required this.completedCount, required this.totalCount});

  final List<AccountMilestoneEntry> items;
  final int completedCount;
  final int totalCount;

  factory AccountMilestones.fromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    final items = raw is! List
        ? const <AccountMilestoneEntry>[]
        : raw.whereType<Map>().map((e) => AccountMilestoneEntry.fromJson(Map<String, dynamic>.from(e))).toList();
    return AccountMilestones(
      items: items,
      completedCount: (json['completedCount'] as num?)?.toInt() ?? items.where((i) => i.completed).length,
      totalCount: (json['totalCount'] as num?)?.toInt() ?? items.length,
    );
  }
}

abstract class AccountMilestoneRepository {
  Future<AccountMilestones> fetch();

  /// 账号级庆祝回报（best-effort）：返回置位行数。
  Future<int> reportCelebrated(List<String> codes);
}

class DioAccountMilestoneRepository implements AccountMilestoneRepository {
  DioAccountMilestoneRepository(this.dio);

  final Dio dio;

  @override
  Future<AccountMilestones> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meAccountMilestones);
    return AccountMilestones.fromJson(resp.data ?? const <String, dynamic>{});
  }

  @override
  Future<int> reportCelebrated(List<String> codes) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.meAccountMilestoneCelebrations,
        data: {'codes': codes});
    return (resp.data?['marked'] as num?)?.toInt() ?? 0;
  }
}

final Provider<AccountMilestoneRepository> accountMilestoneRepositoryProvider =
    Provider<AccountMilestoneRepository>((ref) => DioAccountMilestoneRepository(ref.read(dioProvider)));

/// 账号级里程碑（「我的」页入口计数 + 账号级里程碑页）。🔴 用户维度缓存，已登记 `resetUserScopedCaches`。
final FutureProvider<AccountMilestones> accountMilestonesProvider = FutureProvider<AccountMilestones>(
  (ref) => ref.read(accountMilestoneRepositoryProvider).fetch(),
  retry: (_, _) => null,
);
