import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/growth_models.dart';
import '../domain/growth_popups.dart';

/// Tailrent Level 数据层（V1.4.0 ① Story 1.5）：等级页首屏 + EXP 明细分页。JWT / 续期由 `AuthInterceptor` 处理。
class GrowthRepository {
  GrowthRepository({required this.dio});

  final Dio dio;

  /// 选头像框（Story 3.3）：`frame ∈ NONE / BASIC / UPGRADED / ANIMATED`。成功返回新的四档；
  /// 任何失败（409 `frame-locked`、网络、5xx、响应解析失败）都抛，调用方统一按「切换失败」处理。
  Future<GrowthFrames> setFrame(String frame) async {
    final resp = await dio.put<Map<String, dynamic>>(ApiPaths.meGrowthFrame, data: {'frame': frame});
    final frames = GrowthFrames.fromJson(resp.data);
    if (frames == null) throw const FormatException('bad frames response');
    return frames;
  }

  Future<GrowthSummary> fetchPage() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meGrowth);
    return GrowthSummary.fromJson(resp.data!);
  }

  /// 待弹的升级庆祝 / 存量汇总（Story 2.3）。照运营弹窗仓储的姿态：**失败即空、绝不抛**（5s 超时 / 非 2xx / 解析失败 → null）。
  Future<GrowthPopups?> fetchPopups() async {
    try {
      final resp = await dio
          .get<Map<String, dynamic>>(ApiPaths.growthPopups)
          .timeout(popupTimeout);
      return GrowthPopups.fromJson(resp.data);
    } catch (_) {
      return null;
    }
  }

  /// 确认升级庆祝（带弹出时看到的 toLevel，防弹出期间又升级被吞）。失败静默、不重试。
  Future<void> ackCelebration(int toLevel) async {
    try {
      await dio
          .post<void>(ApiPaths.growthPopupAck('celebration'), queryParameters: {'toLevel': toLevel})
          .timeout(popupTimeout);
    } catch (_) {}
  }

  /// 确认存量汇总。失败静默、不重试。
  Future<void> ackBackfill() async {
    try {
      await dio.post<void>(ApiPaths.growthPopupAck('backfill')).timeout(popupTimeout);
    } catch (_) {}
  }

  static const Duration popupTimeout = Duration(seconds: 5);

  Future<GrowthLedgerPage> fetchLedger({String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.meGrowthLedger,
      queryParameters: {'cursor': ?cursor},
    );
    return GrowthLedgerPage.fromJson(resp.data!);
  }
}

final growthRepositoryProvider =
    Provider<GrowthRepository>((ref) => GrowthRepository(dio: ref.read(dioProvider)));

/// 等级页首屏。autoDispose：只在等级页内使用、离开即回收，无需登记 `resetUserScopedCaches`。
final growthPageProvider = FutureProvider.autoDispose<GrowthSummary>(
  (ref) => ref.read(growthRepositoryProvider).fetchPage(),
);
