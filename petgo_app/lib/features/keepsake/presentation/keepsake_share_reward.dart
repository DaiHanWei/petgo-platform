import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';

/// 分享成功后试着领奖（V1.3.2 Story 4.5；做法照年龄卡 `_claimReward`）。四类卡预览页的 `onShared` 共用。
///
/// 🛡 **失败一律当作没发**：分享本身已经成功，绝不因为领奖这一步报错给用户。
/// 🛡 发了才提示「+N PawCoin」，没发**静默** —— 不告知原因（告知会诱导「攒着别分享」或「月初集中刷满」）。
///
/// [context] 用预览页自己的路由 context（提示出在当前这一屏）；[ref] 用于刷新 PawCoin 余额缓存。
Future<void> claimKeepsakeShareReward(BuildContext context, WidgetRef ref, Future<int> Function() report) async {
  int coins = 0;
  try {
    coins = await report();
  } catch (_) {
    coins = 0;
  }
  if (coins <= 0) return;
  try {
    // 余额变了：失效 PawCoin 缓存，免得 Toko / 商品详情页仍显示旧余额。
    ref.invalidate(pawCoinProvider);
  } catch (_) {
    // 发起分享的页面已卸载（ref 不可用）：余额下次进入时自会重取。
  }
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text(AppLocalizations.of(context).keepsakeShareRewardToast(coins))),
  );
}
