import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 年龄卡分享奖励上报（V1.3.0 批次 A · Story 5.3）。
///
/// 🔴 **只在系统分享面板回调成功之后调**。用户取消面板就不调 ——
/// 「取消不发币」这件事是在**客户端**成立的，服务端无从判断。
///
/// 🔴 请求体只有幂等键：**不带卡面内容、不上传图片**（AC6）。卡面上有宠物名、
/// 由生日推算的年龄、头像 URL —— 任何一项进到请求里，都是把一次娱乐分享
/// 变成一次个人数据上报。
class AgeCardRewardRepository {
  AgeCardRewardRepository(this._ref);

  final Ref _ref;

  /// @return 真的发了多少枚；0 = 没发（去重 / 日上限 / 月度额度 / 总开关，**不区分原因**）。
  Future<int> reportShareForReward(String idempotencyKey) async {
    final dio = _ref.read(dioProvider);
    final res = await dio.post<Map<String, dynamic>>(
      ApiPaths.meAgeCardShareRewards,
      data: {'idempotencyKey': idempotencyKey},
    );
    final coins = res.data?['coins'];
    return coins is num ? coins.toInt() : 0;
  }
}

final ageCardRewardRepositoryProvider =
    Provider<AgeCardRewardRepository>(AgeCardRewardRepository.new);
