import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 护照卡 / 登机牌卡分享奖励上报（V1.3.2 Story 4.5）。
///
/// 🔴 **只在系统分享面板回调成功之后调**（取消不调）。
/// 🔴 请求体只有卡类型：登机牌整体一个类型（不带场所 token —— 按张计会让「打卡数 = 奖励数」）。
class PassportShareRewardRepository {
  PassportShareRewardRepository(this._ref);

  final Ref _ref;

  static const String page = 'PAGE';
  static const String boarding = 'BOARDING';

  /// @return 真的发了多少枚；0 = 没发（**不区分原因**）。非 num → 0。
  Future<int> reportShareForReward(String cardType) async {
    final res = await _ref
        .read(dioProvider)
        .post<Map<String, dynamic>>(ApiPaths.mePassportShareRewards, data: {'cardType': cardType});
    final coins = res.data?['coins'];
    return coins is num ? coins.toInt() : 0;
  }
}

final passportShareRewardRepositoryProvider =
    Provider<PassportShareRewardRepository>(PassportShareRewardRepository.new);
