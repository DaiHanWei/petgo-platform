import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/qr_payment_sheet.dart';
import '../../../pawcoin/presentation/pawcoin_controller.dart';
import '../../data/id_card_repository.dart';
import '../../domain/id_card.dart';
import '../../domain/id_card_style.dart';
import 'hd_paywall_sheet.dart';
import 'ktp_unlock_analytics.dart';

/// 付费抽屉的入口（V1.3.4 ktp AD-10）。`wire` 即埋点 `entry` 属性值。
enum IdCardPaywallEntry {
  create(KtpUnlockAnalytics.entryCreate),
  detail(KtpUnlockAnalytics.entryDetail),
  listBadge(KtpUnlockAnalytics.entryListBadge);

  const IdCardPaywallEntry(this.wire);

  final String wire;
}

/// 一次付费流程的结局，调用方据此决定落点（函数本身不 pop / push / 导出）。
enum IdCardHdFlowResult {
  /// 付费抽屉未选支付方式就关闭。
  dismissed,

  /// PawCoin 当场成功，或 QRIS 轮询到账。
  unlocked,

  /// QRIS 二维码页关闭时未到账，或后端既未解锁也无二维码（pending 文案分支）。
  notCompleted,

  /// 购买请求失败（余额不足 / 网络 / 其他）。
  failed,
}

/// HD 付费的**唯一**实现（V1.3.4 ktp AD-10）：打开付费抽屉 → 付费 → 失效缓存 → toast → 埋点。
///
/// 三个入口（建卡后自动弹 `create`、详情页按钮 `detail`、Kartuku 列表「Buka HD」标签 `listBadge`）共用，
/// 合并了原建卡页 / 详情页两份逻辑之并（PawCoin 成功也失效余额、QRIS 到账也 toast）。
/// **落点不在这里**：按返回值由调用方处理（列表留在原地；详情导出 HD、建卡跳新卡详情归 Story 1.8）。
///
/// [ref] 为调用方的 WidgetRef（API 约定保留）；内部一律用由 [context] 取到的 ProviderContainer，调用页中途卸载也安全。
///
/// 埋点：`entry != create` 时先报 `id_card_buka_hd_tapped`；抽屉未选渠道关闭报 `id_card_paywall_dismissed`（T-8，
/// 选了 QRIS 后关二维码未付不报）；`ktp_unlock_*` 三个事件带 `entry` 与卡种 / 配色。成功只由服务端报，App 不报。
Future<IdCardHdFlowResult> openIdCardHdPaywall(
    BuildContext context, WidgetRef ref, IdCard card, {required IdCardPaywallEntry entry}) async {
  // 用流程开始时的 ProviderContainer 做失效 / 刷新：付费请求在途时调用页被退出，WidgetRef 已失效，
  // 但已成功的付款仍要失效缓存，且不能被 catch 成「购买失败」。
  final container = ProviderScope.containerOf(context, listen: false);
  final style = IdCardStyle.fromCard(card);
  final cardType = style.cardType;
  final cardVariant = style.cardVariant;
  if (entry != IdCardPaywallEntry.create) {
    Analytics.capture('id_card_buka_hd_tapped', {'card_type': cardType, 'entry': entry.wire});
  }

  int balance = 0;
  try {
    balance = (await container.read(pawCoinProvider.future)).balance;
  } catch (_) {
    balance = 0;
  }
  if (!context.mounted) return IdCardHdFlowResult.dismissed;

  // 弹窗期间保活定价 provider（autoDispose）：弹窗关掉后「开始付款」埋点还要读价格。
  final priceSub = container.listen(idCardHdPriceProvider, (_, _) {});
  KtpUnlockAnalytics.paywallShown(
      entry: entry.wire, priceIdr: priceSub.read().value, cardType: cardType, cardVariant: cardVariant);
  final channel = await showModalBottomSheet<HdPayChannel>(
    context: context,
    backgroundColor: AppColors.card,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
    builder: (_) => HdPaywallSheet(
      petName: card.name,
      serialId: card.serialId,
      cardNo: card.cardNo,
      avatarUrl: card.avatarUrl,
      balance: balance,
    ),
  );
  final priceIdr = priceSub.read().value;
  priceSub.close();
  if (channel == null) {
    Analytics.capture('id_card_paywall_dismissed', {'card_type': cardType, 'entry': entry.wire});
    return IdCardHdFlowResult.dismissed;
  }
  // 选了渠道但调用页已卸载：没发起购买，不是「未选渠道关闭」。
  if (!context.mounted) return IdCardHdFlowResult.notCompleted;

  KtpUnlockAnalytics.started(
      entry: entry.wire, method: channel, priceIdr: priceIdr, cardType: cardType, cardVariant: cardVariant);
  final l10n = AppLocalizations.of(context);
  final messenger = ScaffoldMessenger.of(context);
  void toast(String msg) => messenger.showSnackBar(SnackBar(content: Text(msg)));

  void invalidateUnlocked() {
    container.invalidate(idCardDetailProvider(card.id));
    container.invalidate(idCardListProvider);
    // bug 20260806 同类收口：PawCoin 即时扣款成功后失效余额缓存，防他处读到旧余额。
    container.invalidate(pawCoinProvider);
  }

  try {
    final res = await container.read(idCardRepositoryProvider).purchaseHdForCard(card.id, channel);
    if (res.unlocked) {
      invalidateUnlocked();
      toast(l10n.idCardHdUnlockedToast);
      return IdCardHdFlowResult.unlocked;
    }
    if (res.payload?.isNotEmpty ?? false) {
      if (!context.mounted) return IdCardHdFlowResult.notCompleted;
      final bool paid = await showQrPaymentSheet(
        context,
        payload: res.payload!,
        orderRef: res.paymentRef,
        pollPaid: () async {
          final c = await container.refresh(idCardDetailProvider(card.id).future);
          return c.hdUnlocked;
        },
      );
      if (paid) {
        // 投放归因：QRIS 现金到账（PawCoin 当场成交不计，钱在充值时已计）。
        Analytics.capturePurchase(amountIdr: priceIdr, purpose: 'ID_HD', orderRef: res.paymentRef);
        invalidateUnlocked();
        toast(l10n.idCardHdUnlockedToast);
        return IdCardHdFlowResult.unlocked;
      }
      // T-21：关掉二维码页未到账 → 只刷新列表，不提示。
      container.invalidate(idCardListProvider);
      return IdCardHdFlowResult.notCompleted;
    }
    toast(l10n.idCardHdQrisPending);
    return IdCardHdFlowResult.notCompleted;
  } on DioException catch (e) {
    KtpUnlockAnalytics.failedFromError(
        entry: entry.wire, method: channel, error: e, priceIdr: priceIdr, cardType: cardType, cardVariant: cardVariant);
    toast(e.response?.statusCode == 409 ? l10n.idCardHdInsufficientBalance : l10n.idCardHdPurchaseError);
    return IdCardHdFlowResult.failed;
  } catch (_) {
    toast(l10n.idCardHdPurchaseError);
    return IdCardHdFlowResult.failed;
  }
}
