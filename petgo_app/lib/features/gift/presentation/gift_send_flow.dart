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
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../growth/domain/growth_popup_trigger.dart';
import '../../growth/presentation/level_page.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../../profile/domain/id_card.dart';
import '../data/gift_repository.dart';
import '../domain/gift_catalog.dart';
import '../domain/gift_send_result.dart';
import 'gift_catalog_page.dart';
import 'gift_panel_sheet.dart' show GiftSelection;
import 'gift_wrap_page.dart';
import 'gift_send_ceremony.dart';
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
/// 状态机（V1.5.0 gift-pack Story 2-2 起）：选礼物页 → 包装页（祝福语 + 数量 + 付款方式；充值往返内容保留）
/// →（返回 → 选礼物页）→ 成功 / 结束。旧底部面板与 `PayChannelPicker` 不再用于送礼。
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

/// 测试注入：送出动画区域替身（不渲染 Rive）。null = 真 Rive。
@visibleForTesting
GiftSendRiveBuilder? giftSendRiveBuilderOverride;

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
  const _Delivered(this.channel, this.selection, this.senderExp);
  final HdPayChannel channel;
  final GiftSelection selection;

  /// 响应里的送礼人 EXP（D-7，2-3 送达页显示）。
  final int senderExp;
}

class _QrisPending extends _PayOutcome {
  const _QrisPending(this.result, this.selection);
  final GiftSendResult result;
  final GiftSelection selection;
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
    while (true) {
      if (!context.mounted) return;
      // V1.5.0 gift-pack Story 2-1 / 2-2：选礼物整页 → 包装页（替代旧的底部面板 + PayChannelPicker）。
      final res = await Navigator.of(context).push<GiftCatalogResult>(MaterialPageRoute(
        builder: (_) => GiftCatalogPage(recipient: recipient, onCatalogShown: _reportPanelOpened),
      ));
      if (res == null) return; // 关掉选礼物页
      if (res is GiftCatalogCannotSend) {
        _toast(l10n.giftCannotSend);
        return;
      }
      if (res is GiftCatalogOpenLevelPage) {
        if (context.mounted) unawaited(context.push(LevelPage.location));
        return;
      }
      final picked = res as GiftCatalogPicked;
      if (!context.mounted) return;
      final outcome = await _wrap(picked.catalog, picked.item);
      switch (outcome) {
        case null:
          continue; // 包装页返回 → 回选礼物页
        case _Delivered(:final channel, :final selection, :final senderExp):
          await _celebrate(selection, channel, senderExp: senderExp);
          return;
        case _QrisPending(:final result, :final selection):
          await _qris(selection, result);
          return;
        case _Terminal(:final message):
          _toast(message);
          return;
        case _Unavailable():
          _toast(l10n.giftUnavailable);
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

  /// 包装页（C1 ~ C9）：一次或多次「Kirim」，直到送达 / QRIS 出码 / 终止，或用户返回（null）。
  Future<_PayOutcome?> _wrap(GiftCatalog catalog, GiftItem item) async {
    // requestId：针对某一渠道第一次确认时生成；同渠道重试复用，换渠道新生成（AC4.2）。
    HdPayChannel? idChannel;
    String? requestId;
    var lowAmountReported = false;

    Future<GiftWrapSubmitResult> submit(GiftWrapRequest req) async {
      final sel = GiftSelection(catalog: catalog, item: item, quantity: req.quantity);
      final channel = req.channel;
      if (sel.total < catalog.qrisMinAmount && !lowAmountReported) {
        lowAmountReported = true;
        Analytics.capture('gift_qris_low_amount_shown', {'total_amount': sel.total});
      }
      if (requestId == null || idChannel != channel) {
        requestId = newGiftRequestId();
        idChannel = channel;
      }
      try {
        final r = await repo.send(
          petId: catalog.recipient.petId,
          giftToken: item.giftToken,
          quantity: req.quantity,
          channel: channel.wire,
          requestId: requestId!,
          source: source,
          postId: sourcePostId,
          message: req.message,
        );
        if (r.delivered) {
          return GiftWrapDone(_Delivered(channel, sel, r.senderExp));
        }
        final payload = r.payload;
        if (channel == HdPayChannel.qris && payload != null && payload.isNotEmpty && r.orderToken != null) {
          return GiftWrapDone(_QrisPending(r, sel));
        }
        if (r.status == GiftOrderStatus.failed || r.status == GiftOrderStatus.expired) {
          // 这笔单已关：同 requestId 重放只会拿回这笔无码的单 → 下次确认换新 requestId（OCR #4）。
          requestId = null;
        }
        _toast(l10n.idCardHdPurchaseError, top: true);
        return const GiftWrapStay();
      } on DioException catch (e) {
        final slug = ProblemDetail.fromDioException(e)?.typeSlug;
        switch (slug) {
          case 'gift-message-blocked':
            // 422 在任何写库之前：订单未建，换祝福语后用新 requestId（旧键不会重放出这单）。
            requestId = null;
            return const GiftWrapMessageBlocked();
          case 'pawcoin-insufficient':
            _toast(l10n.idCardHdInsufficientBalance, top: true);
            if (context.mounted) ref.invalidate(pawCoinProvider);
            return const GiftWrapStay();
          case 'gift-pet-gone' || 'gift-own-pet' || 'gift-blocked':
            return GiftWrapDone(_Terminal(l10n.giftCannotSend));
          case 'gift-locked':
            return GiftWrapDone(_Terminal(l10n.giftLockedError));
          case 'gift-unavailable':
            return const GiftWrapDone(_Unavailable());
          default:
            // 网络 / 5xx / rate-limited / gift-qris-below-min：停留本页可重试（C8）。
            _toast(l10n.idCardHdPurchaseError, top: true);
            return const GiftWrapStay();
        }
      } catch (_) {
        _toast(l10n.idCardHdPurchaseError, top: true);
        return const GiftWrapStay();
      }
    }

    Future<void> topUp() async {
      // S3：await 充值页 → 刷新余额（礼物 / 数量 / 祝福语保留在包装页的 State 里）。
      if (!context.mounted) return;
      await context.push(_rechargeRoute);
      if (!context.mounted) return;
      ref.invalidate(pawCoinProvider);
    }

    if (!context.mounted) return null;
    final popped = await Navigator.of(context).push<Object>(MaterialPageRoute(
      builder: (_) => GiftWrapPage(catalog: catalog, item: item, onSubmit: submit, onTopUp: topUp),
    ));
    return popped is _PayOutcome ? popped : null;
  }

  /// C6 / C7：QRIS 二维码 + 轮询单笔订单状态。
  Future<void> _qris(GiftSelection sel, GiftSendResult r) async {
    if (!context.mounted) return;
    final orderToken = r.orderToken!;
    final paid = await showQrPaymentSheet(
      context,
      payload: r.payload!,
      orderRef: r.paymentRef,
      // V1.4.1 QRIS 支付页：金额取本次支付单（后端权威），缺省退选中总价。
      amountIdr: r.paymentAmount ?? sel.total,
      expiresAt: r.paymentExpiresAt,
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
  Future<void> _celebrate(GiftSelection sel, HdPayChannel channel, {int senderExp = 0}) async {
    // V1.5.0 gift-pack Story 2-3：送出动画（E1 / E2）→ 送达页（E3）取代旧飞行动效 + Toast。
    // EXP：PawCoin 送达取接口响应 senderExp；QRIS 到账取目录 senderExp × 数量（D-7）。
    final exp = channel == HdPayChannel.qris ? sel.item.senderExp * sel.quantity : senderExp;
    final ceremony = context.mounted
        ? Navigator.of(context).push<void>(MaterialPageRoute(
            builder: (_) => GiftSendCeremonyPage(
              petName: sel.catalog.recipient.name,
              petAvatarUrl: sel.catalog.recipient.avatarUrl,
              giftIconUrl: sel.item.iconUrl,
              senderExp: exp,
              riveBuilder: giftSendRiveBuilderOverride,
            ),
          ))
        : null;
    // 送达即刷新余额 / 回调入口页（入口在送达页下面先刷新，用户「Lanjut」回去时已是新数据）/ 埋点。
    if (context.mounted) ref.invalidate(pawCoinProvider);
    onDelivered?.call();
    Analytics.capture('gift_payment_succeeded', {
      'gift_id': sel.item.giftToken,
      'quantity': sel.quantity,
      'total_amount': sel.total,
      'channel': channel.wire,
    });
    if (ceremony != null) await ceremony;
    // L2 第二轮 P1-2：送礼返成长值后可能升级 → 回到入口页再拉一次庆祝（不叠在送达页上）。
    growthPopupTrigger.afterAction();
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
