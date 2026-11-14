import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 礼物页 / Diary 礼物行点卡片的两个动作入口（V1.5.0 gift-pack Story 2-4）。
///
/// - [openGiftOrders]：主人拆礼物（单个 G1 / 批量 G4），由 Story 2-5 实现；返回是否拆开了（调用方据此刷新列表）。
/// - [showGiftOrderDetail]：礼物详情抽屉（H1 / H2），由 Story 2-6 实现。
///
/// 2-5 / 2-6 完成前为空实现。
Future<bool> openGiftOrders(BuildContext context, WidgetRef ref,
    {required int petId, List<String> orderTokens = const [], bool all = false}) async {
  return false;
}

Future<void> showGiftOrderDetail(BuildContext context, WidgetRef ref, {required String orderToken}) async {}
