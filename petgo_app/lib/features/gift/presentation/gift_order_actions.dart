import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'gift_order_detail_sheet.dart';
import 'gift_reveal_page.dart';

/// 礼物页 / Diary 礼物行点卡片的动作入口（V1.5.0 gift-pack Story 2-4）。
///
/// - [openGiftOrders]：主人拆礼物（单个 G1 / 批量 G4，Story 2-5）；返回是否调过 open 接口（调用方据此刷新列表）。
/// - [showGiftOrderDetail]：礼物详情抽屉（H1 / H2，Story 2-6）。
/// - [reportGiftMessage]：举报祝福语（H5 / H6，Story 2-6）。
Future<bool> openGiftOrders(BuildContext context, WidgetRef ref,
    {required int petId, List<String> orderTokens = const [], bool all = false}) async {
  if (!all && orderTokens.isEmpty) return false;
  final opened = await Navigator.of(context).push<bool>(MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => GiftRevealPage(petId: petId, orderTokens: orderTokens, all: all),
  ));
  return opened == true;
}

Future<void> showGiftOrderDetail(BuildContext context, WidgetRef ref, {required String orderToken}) =>
    showGiftOrderDetailSheet(context, orderToken);

Future<void> reportGiftMessage(BuildContext context, WidgetRef ref, {required String orderToken}) async =>
    openGiftMessageReport(context, ref, orderToken);
