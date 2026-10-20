import 'dart:async';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../growth/domain/growth_popup_trigger.dart';
import '../../growth/presentation/level_page.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../../profile/domain/id_card.dart';
import '../data/gift_repository.dart';
import '../domain/gift_catalog.dart';
import '../domain/gift_send_result.dart';
import 'gift_panel_sheet.dart';
import 'gift_send_animation.dart';
import 'gift_style.dart';

/// 送礼入口 `source`（与后端 `GiftSource` 小写值一致；`gift_page` 由 Story 2.2 的礼物页传）。
const String giftSourceDiaryTab = 'diary_tab';
const String giftSourceContentDetail = 'content_detail';
const String giftSourceGiftPage = 'gift_page';

/// 充值页路由（与 `PayChannelPicker` 默认跳转、`vet_timed_pay_page._topUp` 同一条）。
const String _rechargeRoute = '/me/pawcoin/recharge';

/// 支付用途代号（与后端 `PaymentPurpose.GIFT` 同名；QRIS 现金到账时投放归因用）。
const String _purchasePurpose = 'GIFT';

/// 送礼全流程（V1.4.0 gift Story 1.3 · AC3.4 ~ 3.6、AC4、AC5；AD-13）。
///
/// 状态机：面板 → 选渠道 →（充值 → 选渠道）→（关闭 → 面板）→ 成功 / 结束。
/// 🔴 **任何时刻只有一层抽屉**（S1）：面板 `pop` 之后才弹选渠道，选渠道关掉才重开面板。
///
/// [flightTargetKey]：送出动效的落点（访客视图 = 宠物头像，详情页 = 礼物区）；取不到 → 屏幕中上。
/// [onDelivered]：送达后回调（访客视图用来失效统计，供 Story 2.2 的「Hadiah」格接上）。
/// [sourcePostId]：详情页入口的帖子 id，只作发起接口的来源记录（后端不校验宠物与帖子的关联，gift Story 4.1 · AC3.3）。
/// [isMultiPetPost]：详情页入口的帖子关联 ≥ 2 只宠物（`gift_panel_opened.is_multi_pet_post`，AC4.4）。
///
/// 连点防重由入口按钮负责（流程进行中按钮不响应），本函数不设全局锁。
Future<void> runGiftSendFlow({
  required BuildContext context,
  required WidgetRef ref,
  required GiftRecipientRef recipient,
  required String source,
  GlobalKey? flightTargetKey,
  VoidCallback? onDelivered,
  int? sourcePostId,
  bool isMultiPetPost = false,
}) async {
  // 目录在整条流程里保活：面板关掉去选渠道、再回来时不重新加载（礼物与数量也照旧）。
  final keepAlive = ref.listenManual(giftCatalogProvider(recipient), (_, _) {});
  try {
    await _GiftSendFlow(
      context: context,
      ref: ref,
      recipient: recipient,
      source: source,
      flightTargetKey: flightTargetKey,
      onDelivered: onDelivered,
      sourcePostId: sourcePostId,
      isMultiPetPost: isMultiPetPost,
    ).run();
  } finally {
    try {
      keepAlive.close();
    } catch (_) {
      /* 页面已拆、容器已释放 → 订阅随之失效 */
    }
  }
}

/// 送礼 `requestId`：16 字节安全随机数的 hex（32 位，后端上限 64；不引 uuid 依赖）。
@visibleForTesting
String newGiftRequestId() {
  final r = Random.secure();
  return List<String>.generate(16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
}

/// 选渠道抽屉一次确认的结局。
sealed class _PayOutcome {
  const _PayOutcome();
}

class _Delivered extends _PayOutcome {
  const _Delivered(this.channel);
  final HdPayChannel channel;
}

class _QrisPending extends _PayOutcome {
  const _QrisPending(this.result);
  final GiftSendResult result;
}

/// 结束流程（C9 / 未解锁），带一句提示。
class _Terminal extends _PayOutcome {
  const _Terminal(this.message);
  final String message;
}

/// 礼物已下线：提示后重开面板并重拉目录。
class _Unavailable extends _PayOutcome {
  const _Unavailable();
}

/// 选渠道这一段走完之后，面板那层怎么办。
enum _AfterPay { reopenPanel, refreshPanel, finished }

class _GiftSendFlow {
  _GiftSendFlow({
    required this.context,
    required this.ref,
    required this.recipient,
    required this.source,
    this.flightTargetKey,
    this.onDelivered,
    this.sourcePostId,
    this.isMultiPetPost = false,
  })  : overlay = Overlay.of(context, rootOverlay: true),
        l10n = AppLocalizations.of(context),
        locale = Localizations.localeOf(context),
        repo = ref.read(giftRepositoryProvider);

  final BuildContext context;
  final WidgetRef ref;
  final GiftRecipientRef recipient;
  final String source;
  final GlobalKey? flightTargetKey;
  final VoidCallback? onDelivered;
  final int? sourcePostId;
  final bool isMultiPetPost;

  /// await 之后 context 可能已失效：Toast 一律挂开流程时取好的根 Overlay。
  final OverlayState overlay;
  final AppLocalizations l10n;
  final Locale locale;
  final GiftRepository repo;

  bool _panelReported = false;

  void _toast(String msg, {bool top = false}) {
    if (overlay.mounted) showAppToastOnOverlay(overlay, msg, top: top);
  }

  Future<void> run() async {
    String? giftToken;
    var quantity = 1;
    while (true) {
      if (!context.mounted) return;
      final res = await showGiftPanelSheet(
        context,
        recipient: recipient,
        initialGiftToken: giftToken,
        initialQuantity: quantity,
        onCatalogShown: _reportPanelOpened,
      );
      if (res == null) return; // 关掉面板
      if (res is GiftPanelCannotSend) {
        _toast(l10n.giftCannotSend);
        return;
      }
      if (res is GiftPanelOpenLevelPage) {
        // B1 / B2 锁区块（gift Story 4.1 · AC7.1）：面板已关，进 ① 等级页；本次送礼结束（返回不重开面板）。
        if (context.mounted) unawaited(context.push(LevelPage.location));
        return;
      }
      final selection = (res as GiftPanelSend).selection;
      giftToken = selection.item.giftToken;
      quantity = selection.quantity;
      switch (await _pay(selection)) {
        case _AfterPay.finished:
          return;
        case _AfterPay.reopenPanel:
          continue;
        case _AfterPay.refreshPanel:
          if (!context.mounted) return;
          ref.invalidate(giftCatalogProvider(recipient));
          continue;
      }
    }
  }

  void _reportPanelOpened(GiftCatalog catalog) {
    if (_panelReported) return;
    _panelReported = true;
    Analytics.capture('gift_panel_opened', {
      'source': source,
      'pet_id': catalog.recipient.petId,
      // gift Story 4.1 · AC4.4：详情页帖子关联 ≥ 2 只（先经 A7 选宠物）为 true；访客视图 / 礼物页恒 false。
      'is_multi_pet_post': isMultiPetPost,
    });
  }

  Future<int> _balance() async {
    try {
      // 限时：余额读失败会被 Riverpod 自动重试，`future` 可能一直不 resolve；读不到按 0（照 keepsake_pay_flow）。
      return (await ref.read(pawCoinProvider.future).timeout(const Duration(seconds: 10))).balance;
    } catch (_) {
      return 0;
    }
  }

  /// 一次「Kirim」之后的选渠道段（含充值回流与扣款中保持打开）。
  Future<_AfterPay> _pay(GiftSelection sel) async {
    final total = sel.total;
    final catalog = sel.catalog;
    final lowAmount = total < catalog.qrisMinAmount;
    final petName = catalog.recipient.name;
    final giftName = sel.item.nameFor(locale);
    // requestId：本次「Kirim」里针对某一渠道第一次确认时生成；同渠道重试复用，换渠道新生成（AC4.2）。
    HdPayChannel? idChannel;
    String? requestId;
    var lowAmountReported = false;

    while (true) {
      if (!context.mounted) return _AfterPay.finished;
      final balance = await _balance();
      if (!context.mounted) return _AfterPay.finished;
      if (lowAmount && !lowAmountReported) {
        lowAmountReported = true;
        Analytics.capture('gift_qris_low_amount_shown', {'total_amount': total});
      }

      _PayOutcome? outcome;
      Future<bool>? inFlight;
      var topUp = false;

      Future<bool> confirm(HdPayChannel channel) async {
        if (requestId == null || idChannel != channel) {
          requestId = newGiftRequestId();
          idChannel = channel;
        }
        try {
          final r = await repo.send(
            petId: catalog.recipient.petId,
            giftToken: sel.item.giftToken,
            quantity: sel.quantity,
            channel: channel.wire,
            requestId: requestId!,
            source: source,
            postId: sourcePostId,
          );
          if (r.delivered) {
            outcome = _Delivered(channel);
            return true;
          }
          final payload = r.payload;
          if (channel == HdPayChannel.qris &&
              payload != null &&
              payload.isNotEmpty &&
              r.orderToken != null) {
            outcome = _QrisPending(r);
            return true;
          }
          if (r.status == GiftOrderStatus.failed || r.status == GiftOrderStatus.expired) {
            // 这笔单已关（如网关出码失败置 FAILED）：同 requestId 重放只会拿回这笔无码的单，
            // 重试恒失败 → 下次确认换新 requestId 重新下单（OCR #4，后端幂等键 GIFT:{sender}:{requestId}）。
            requestId = null;
          }
          _toast(l10n.idCardHdPurchaseError, top: true);
          return false;
        } on DioException catch (e) {
          final slug = ProblemDetail.fromDioException(e)?.typeSlug;
          switch (slug) {
            case 'pawcoin-insufficient':
              _toast(l10n.idCardHdInsufficientBalance, top: true);
              if (context.mounted) ref.invalidate(pawCoinProvider);
              return false;
            case 'gift-pet-gone' || 'gift-own-pet' || 'gift-blocked':
              outcome = _Terminal(l10n.giftCannotSend);
              return true;
            case 'gift-locked':
              outcome = _Terminal(l10n.giftLockedError);
              return true;
            case 'gift-unavailable':
              outcome = const _Unavailable();
              return true;
            default:
              // 网络 / 5xx / rate-limited / gift-qris-below-min：抽屉保留可重试（C8）。
              _toast(l10n.idCardHdPurchaseError, top: true);
              return false;
          }
        } catch (_) {
          _toast(l10n.idCardHdPurchaseError, top: true);
          return false;
        }
      }

      await showModalBottomSheet<HdPayChannel>(
        context: context,
        backgroundColor: AppColors.card,
        isScrollControlled: true,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        // 余额跟着 pawCoinProvider 走：`pawcoin-insufficient` 后 invalidate → 抽屉里的余额 / PawCoin 行 /
        // 默认选中随之刷新（AC4.4「刷新余额」）；刷新中 / 读不到时沿用打开前读到的值（那次读失败已按 0）。
        // key 随余额变 → 选择状态重置，不会留着过期的「已选 PawCoin」再撞一次 409。
        builder: (_) => Consumer(builder: (_, sheetRef, _) {
          final shown = sheetRef.watch(pawCoinProvider).value?.balance ?? balance;
          return PayChannelPicker(
            key: ValueKey('giftPayPicker-$shown'),
            header: GiftPaySummaryCard(
              iconUrl: sel.item.iconUrl,
              giftName: giftName,
              quantity: sel.quantity,
              petName: petName,
            ),
            title: l10n.giftPayTitle,
            body: l10n.giftPayBody,
            price: AsyncValue<int>.data(total),
            onRetryPrice: () {},
            balance: shown,
            confirmLabel: (p) => l10n.keepsakePayConfirm(formatIdrAmount(p ?? total)),
            qrisDisabledReason:
                lowAmount ? l10n.giftQrisMinimum(formatIdrAmount(catalog.qrisMinAmount)) : null,
            qrisDisabledHint: lowAmount ? l10n.giftQrisMinimumHint : null,
            onTopUp: () => topUp = true,
            onConfirm: (channel) => inFlight = confirm(channel),
          );
        }),
      );
      // 抽屉在请求途中被关掉（理论上 PopScope 已挡）：等结果出来再判，不能把已扣的钱当没发生。
      final pending = inFlight;
      if (pending != null) {
        try {
          await pending;
        } catch (_) {}
      }

      if (topUp) {
        // S3：照 vet_timed_pay_page._topUp —— await 充值页 → 刷新余额 → 自动重开选渠道（礼物、数量保留）。
        if (!context.mounted) return _AfterPay.finished;
        await context.push(_rechargeRoute);
        if (!context.mounted) return _AfterPay.finished;
        ref.invalidate(pawCoinProvider);
        continue;
      }

      final o = outcome;
      switch (o) {
        case null:
          return _AfterPay.reopenPanel; // 关掉选渠道 → 回面板（S1）
        case _Delivered(:final channel):
          await _celebrate(sel, channel);
          return _AfterPay.finished;
        case _QrisPending(:final result):
          await _qris(sel, result);
          return _AfterPay.finished;
        case _Terminal(:final message):
          _toast(message);
          return _AfterPay.finished;
        case _Unavailable():
          _toast(l10n.giftUnavailable);
          return _AfterPay.refreshPanel;
      }
    }
  }

  /// C6 / C7：QRIS 二维码 + 轮询单笔订单状态。
  Future<void> _qris(GiftSelection sel, GiftSendResult r) async {
    if (!context.mounted) return;
    final orderToken = r.orderToken!;
    final paid = await showQrPaymentSheet(
      context,
      payload: r.payload!,
      orderRef: r.paymentRef,
      pollPaid: () async {
        final status = await repo.orderStatus(orderToken);
        switch (status) {
          case GiftOrderStatus.delivered:
            return true;
          case GiftOrderStatus.expired:
            throw const QrPaymentAborted('EXPIRED');
          case GiftOrderStatus.failed:
            throw const QrPaymentAborted('FAILED');
          case GiftOrderStatus.undeliveredRefundPending ||
                GiftOrderStatus.undeliveredRefunded ||
                GiftOrderStatus.refundManual:
            throw const QrPaymentAborted('UNDELIVERED');
          case GiftOrderStatus.pending || GiftOrderStatus.unknown:
            return false;
        }
      },
      // S5：过期 / 失败 / 未送达都要说一声；用户自己点「Batalkan」不提示（组件不回调）。
      onAborted: (abort) {
        switch (abort.category) {
          case 'EXPIRED':
            _toast(l10n.paymentExpiredRetry);
          case 'UNDELIVERED':
            _toast(l10n.giftUndelivered);
          default:
            _toast(l10n.idCardHdPurchaseError);
        }
      },
    );
    if (!paid) return;
    // 投放归因：QRIS 现金到账（PawCoin 消费不计——钱在充值时已计过）。
    Analytics.capturePurchase(amountIdr: sel.total, purpose: _purchasePurpose, orderRef: r.paymentRef);
    await _celebrate(sel, HdPayChannel.qris);
  }

  /// D1 → D2：送出动效（尊重「减少动态效果」）→ Toast → 刷新余额 → 埋点。
  Future<void> _celebrate(GiftSelection sel, HdPayChannel channel) async {
    final disable =
        context.mounted ? (MediaQuery.maybeDisableAnimationsOf(context) ?? false) : true;
    await playGiftSendAnimation(
      overlay: overlay,
      iconUrl: sel.item.iconUrl,
      quantity: sel.quantity,
      targetKey: flightTargetKey,
      disableAnimations: disable,
    );
    // P-4：Toast 用礼物名（按语言取 EN / ID 快照），不用 emoji。
    _toast(l10n.giftSentToast(sel.item.nameFor(locale), sel.quantity, sel.catalog.recipient.name));
    if (context.mounted) ref.invalidate(pawCoinProvider);
    onDelivered?.call();
    // L2 第二轮 P1-2：送礼返成长值（送礼人 + 主人）后可能升级 → 当前页立即拉一次庆祝（PawCoin / QRIS 两条路都到这里）。
    growthPopupTrigger.afterAction();
    Analytics.capture('gift_payment_succeeded', {
      'gift_id': sel.item.giftToken,
      'quantity': sel.quantity,
      'total_amount': sel.total,
      'channel': channel.wire,
    });
  }
}

/// 选渠道抽屉顶部的礼物摘要卡（C1 ~ C5 `header`）。
class GiftPaySummaryCard extends StatelessWidget {
  const GiftPaySummaryCard({
    super.key,
    required this.iconUrl,
    required this.giftName,
    required this.quantity,
    required this.petName,
  });

  final String? iconUrl;
  final String giftName;
  final int quantity;
  final String petName;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('giftPaySummary'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint600],
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 48,
            height: 48,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(14)),
            child: giftIcon(iconUrl, size: 34),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.giftSummaryLine(giftName, quantity),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w700, color: Colors.white)),
                const SizedBox(height: 2),
                Opacity(
                  opacity: 0.85,
                  child: Text(l10n.giftPayFor(petName),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12, color: Colors.white)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
