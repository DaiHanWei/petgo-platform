import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// Tailsonality 卡分享奖励上报（V1.3.2 Story 4.5）。
///
/// 🔴 **只在系统分享面板回调成功之后调**（取消不调 ——「取消不发币」在客户端成立）。
/// 🔴 请求体只有卡类型：去重 = 宠物 × 卡类型，不带结果 token、不带水印态、不带卡面内容。
class TailsonalityShareRewardRepository {
  TailsonalityShareRewardRepository(this._ref);

  final Ref _ref;

  static const String result = 'RESULT';
  static const String match = 'MATCH';

  /// @return 真的发了多少枚；0 = 没发（**不区分原因**）。非 num → 0。
  Future<int> reportShareForReward(String cardType) async {
    final res = await _ref
        .read(dioProvider)
        .post<Map<String, dynamic>>(ApiPaths.meTailsonalityShareRewards, data: {'cardType': cardType});
    final coins = res.data?['coins'];
    return coins is num ? coins.toInt() : 0;
  }
}

final tailsonalityShareRewardRepositoryProvider =
    Provider<TailsonalityShareRewardRepository>(TailsonalityShareRewardRepository.new);
