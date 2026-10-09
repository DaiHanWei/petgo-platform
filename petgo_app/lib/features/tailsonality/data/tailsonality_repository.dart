import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../keepsake/domain/keepsake_purchase_result.dart';
import '../../profile/domain/id_card.dart';
import '../domain/tailsonality_result.dart';

/// Tailsonality 数据层（V1.3.2 Story 2.1）。
///
/// 提交是**唯一写入口**：答题中途不落任何进度（C-5），只有 18 题全答完才调 [submit]。
/// 请求体只放 18 个题号键（`Q1`..`Q15`、`P1`..`P3` → 原始选项序号 0..3）；题套由服务端按物种选，不传。
/// 错误以 [DioException] 抛给页面；无宠物档案服务端回 404，答案不合法回 422。
class TailsonalityRepository {
  TailsonalityRepository({required this.dio});

  final Dio dio;

  Future<TailsonalityResult> submit(Map<String, int> answers) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.petTailsonalityResults, data: answers);
    return TailsonalityResult.fromJson(resp.data!);
  }

  /// 本人当前宠物的全部结果（新 → 旧）。
  Future<List<TailsonalityResult>> fetchResults() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petTailsonalityResults);
    final raw = resp.data?['items'];
    return raw is List
        ? raw.map((e) => TailsonalityResult.fromJson((e as Map).cast<String, dynamic>())).toList()
        : const [];
  }

  Future<TailsonalityResult> fetchResult(String token) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petTailsonalityResult(token));
    return TailsonalityResult.fromJson(resp.data!);
  }

  /// 一次性解锁（Story 3.2）：已解锁 409 `keepsake-already-unlocked`、余额不足 409 `pawcoin-insufficient`。
  Future<KeepsakePurchaseResult> unlock(String token, HdPayChannel channel) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.tailsonalityResultUnlock(token),
        data: {'channel': channel.wire});
    return KeepsakePurchaseResult.fromJson(resp.data ?? const {});
  }

  /// 配型单独解锁（2026-10-09）：完整解读已解锁 / 已买过配型 409 `keepsake-already-unlocked`。
  Future<KeepsakePurchaseResult> unlockMatch(String token, HdPayChannel channel) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.tailsonalityResultMatchUnlock(token),
        data: {'channel': channel.wire});
    return KeepsakePurchaseResult.fromJson(resp.data ?? const {});
  }

  /// 佩戴某个已解锁结果（Story 3.3）；未解锁 422 `tailsonality-badge-locked`。
  Future<void> equipBadge(String resultToken) async {
    await dio.put<void>(ApiPaths.tailsonalityBadge, data: {'resultToken': resultToken});
  }

  /// 卸下（D-16）：幂等。
  Future<void> unequipBadge() async {
    await dio.delete<void>(ApiPaths.tailsonalityBadge);
  }
}

final tailsonalityRepositoryProvider =
    Provider<TailsonalityRepository>((ref) => TailsonalityRepository(dio: ref.read(dioProvider)));
