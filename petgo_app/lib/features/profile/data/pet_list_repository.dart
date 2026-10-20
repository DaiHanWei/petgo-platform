import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/retry_policy.dart';
import '../../auth/domain/auth_state.dart';

/// 「我的宠物」列表项（V1.4.0 multi-pet Story 1.5 · AD-14）。字段同后端 `PetListItemResponse`
/// （`GET /pet-profiles/me/list`）。
///
/// ⚠️ [name] 只用于界面渲染，**不得写进日志 / 埋点**（§5）。
class PetListItem {
  const PetListItem({
    required this.id,
    required this.name,
    this.avatarUrl,
    this.petType,
    this.breed,
    this.birthday,
    this.createdAt,
    this.status = statusActive,
    this.isDefault = false,
    this.tailsonalityBadge,
    this.diaryCount,
  });

  static const String statusActive = 'ACTIVE';
  static const String statusReadOnly = 'READ_ONLY';

  final int id;
  final String name;
  final String? avatarUrl;
  final String? petType;
  final String? breed;
  final DateTime? birthday;
  final DateTime? createdAt;

  /// 宠物状态：`ACTIVE` / `READ_ONLY`。**未知值按 ACTIVE**（后端将来加状态，老 App 不至于把正常宠物锁掉）。
  final String status;
  final bool isDefault;
  final String? tailsonalityBadge;

  /// 该宠物 Diary 数（与 Diary Tab 统计同一口径）；null = 后端未下发，不画角标。
  final int? diaryCount;

  bool get isReadOnly => status == statusReadOnly;

  factory PetListItem.fromJson(Map<String, dynamic> json) {
    final rawStatus = json['status'] as String?;
    return PetListItem(
      id: (json['id'] as num).toInt(),
      name: json['name'] as String? ?? '',
      avatarUrl: json['avatarUrl'] as String?,
      petType: json['petType'] as String?,
      breed: json['breed'] as String?,
      birthday: _parseDate(json['birthday']),
      createdAt: _parseDate(json['createdAt']),
      status: rawStatus == statusReadOnly ? statusReadOnly : statusActive,
      isDefault: json['isDefault'] as bool? ?? false,
      tailsonalityBadge: json['tailsonalityBadge'] as String?,
      diaryCount: (json['diaryCount'] as num?)?.toInt(),
    );
  }

  static DateTime? _parseDate(Object? raw) => raw is String ? DateTime.tryParse(raw) : null;
}

/// 「我的宠物」列表数据层。抽象便于测试注入 fake。
abstract class PetListRepository {
  /// 本人全部宠物（最早建档在前）；没有宠物 = 空列表。
  Future<List<PetListItem>> fetch();
}

class DioPetListRepository implements PetListRepository {
  DioPetListRepository(this.dio);

  final Dio dio;

  @override
  Future<List<PetListItem>> fetch() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.petProfileList);
    return (resp.data ?? const [])
        .map((e) => PetListItem.fromJson(e as Map<String, dynamic>))
        .toList();
  }
}

final Provider<PetListRepository> petListRepositoryProvider =
    Provider<PetListRepository>((ref) => DioPetListRepository(ref.read(dioProvider)));

/// 本人宠物列表（AD-14「App 状态单一来源」之一）。建档 / 删除 / 换账号后失效。
///
/// `retry: retryExcept4xx`：4xx（未登录 / 无权限）是确定性结果，不走 Riverpod 默认 15 秒重试。
///
/// 🔴 跟随**当前账号**（code review 2026-10-18 #4）：账号一变本 provider 因依赖变化而 reload
/// （`isReloading`，与建档 / 删除后的手动 invalidate = `isRefreshing` 区分得开）。界面据此在换号重取期间
/// 不渲染上个账号的宠物（见 `watchEffectivePetId` / Diary 页）。游客 → 空列表，**不发请求**
/// （游客无令牌，请求会 401 → 弹全局强登录窗）。
final FutureProvider<List<PetListItem>> petListProvider = FutureProvider<List<PetListItem>>(
  (ref) async {
    final account = ref.watch(authControllerProvider
        .select((s) => s.status == AuthStatus.guest ? null : (s.profile?.id ?? -1)));
    if (account == null) return const [];
    return ref.read(petListRepositoryProvider).fetch();
  },
  retry: retryExcept4xx,
);
