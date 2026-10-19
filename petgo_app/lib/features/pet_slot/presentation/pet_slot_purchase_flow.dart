import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../keepsake/domain/keepsake_purchase_result.dart';
import '../../keepsake/presentation/keepsake_pay_flow.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/domain/id_card.dart';
import '../data/pet_slot_repository.dart';
import '../domain/default_renew_selection.dart';
import '../domain/pet_slot_purchase.dart';

/// 名额流程里 `QrPaymentAborted.category` 的第三种取值：钱已到账、名额交人工（ORPHAN_PAID / OVERPAID）。
const String kPetSlotAbortPaidNeedsSupport = 'PAID_NEEDS_SUPPORT';

/// 名额下单的幂等键存放处（code review #1：防重复扣款 / 重复出码）。
///
/// 键按「同一次购买意图」= 种类 + 数量（或所选名额 token）+ 渠道 记住，**跨重试沿用**：
/// PawCoin 扣款已提交但响应超时 → 用户重试 → 后端按同键重放、不再扣；QRIS 关掉面板再付 → 同键复用原意图。
/// 只在终态（PAID / EXPIRED / FAILED / ORPHAN_PAID / OVERPAID）或 4xx 业务拒绝后清掉；换数量 / 换名额 / 换渠道 = 新键。
/// 键是随机串、不含用户信息；后端按账号隔离幂等键。
class PetSlotAttemptStore {
  final Map<String, String> _keys = {};

  String keyFor(String attempt) => _keys.putIfAbsent(attempt, newPetSlotIdempotencyKey);

  void clear(String attempt) => _keys.remove(attempt);

  @visibleForTesting
  String? peek(String attempt) => _keys[attempt];
}

final Provider<PetSlotAttemptStore> petSlotAttemptStoreProvider =
    Provider<PetSlotAttemptStore>((ref) => PetSlotAttemptStore());

/// 租用 / 续租的结局（调用方据此刷新页面；提示已由本流程给出）。
enum PetSlotFlowOutcome { succeeded, notCompleted }

/// 价格展示「Rp5.000」（`formatIdrAmount`，全项目一份口径）。
String petSlotPrice(int idr) => 'Rp${formatIdrAmount(idr)}';

/// D5 租用：复用 [runKeepsakePurchase] + 带数量步进器的 [PayChannelPicker]（D5 拍板，不另起抽屉）。
///
/// [initialQuantity]：默认 1；只读后点「续租」而无生效中月租时 = `shortBy`（P-5）。上限 = `rentable`。
Future<PetSlotFlowOutcome> runPetSlotRent(
  BuildContext context,
  WidgetRef ref,
  PetSlotOverview overview, {
  int initialQuantity = 1,
}) {
  final max = overview.rentable < 1 ? 1 : overview.rentable;
  var quantity = initialQuantity.clamp(1, max);
  return _runPetSlotPurchase(
    context,
    ref,
    overview,
    isRenewal: false,
    count: () => quantity,
    attempt: (channel) => 'rent:$quantity:${channel.wire}',
    sheet: (l10n, balance) => PayChannelPicker(
      key: const ValueKey('petSlotRentSheet'),
      header: PetSlotSheetHeader(overview: overview),
      title: l10n.petSlotRentSheetTitle,
      body: l10n.petSlotRentSheetNote(overview.days),
      price: AsyncData(overview.unitPrice),
      onRetryPrice: () {},
      balance: balance,
      confirmLabel: (p) => l10n.keepsakePayConfirm(p == null ? '…' : formatIdrAmount(p)),
      confirmKey: const ValueKey('petSlotRentConfirm'),
      quantity: PayQuantityConfig(
        label: l10n.petSlotQuantityLabel,
        max: max,
        initial: quantity,
        lineLabel: (n) => l10n.petSlotQuantityTotal(n, petSlotPrice(overview.unitPrice)),
        onChanged: (n) => quantity = n,
      ),
    ),
    start: (repo, channel, key) =>
        repo.rent(quantity: quantity, channel: channel, idempotencyKey: key),
    successMessage: (l10n, date) => l10n.petSlotRentSuccess(quantity, date),
  );
}

/// D11 续租：同一 [PayChannelPicker] 骨架，数量步进器换成名额勾选列表（[PetSlotRenewItems]）。
Future<PetSlotFlowOutcome> runPetSlotRenew(
  BuildContext context,
  WidgetRef ref,
  PetSlotOverview overview,
) {
  final selection = PetSlotRenewSelection.defaultFor(overview);
  return _runPetSlotPurchase(
    context,
    ref,
    overview,
    isRenewal: true,
    count: () => selection.tokens.length,
    attempt: (channel) => 'renew:${(selection.tokens.toList()..sort()).join(',')}:${channel.wire}',
    sheet: (l10n, balance) => PetSlotRenewPaySheet(
      overview: overview,
      balance: balance,
      selection: selection,
    ),
    start: (repo, channel, key) => repo.renew(
        rentalTokens: List<String>.of(selection.tokens), channel: channel, idempotencyKey: key),
    successMessage: (l10n, date) => l10n.petSlotRenewSuccess(date),
  );
}

Future<PetSlotFlowOutcome> _runPetSlotPurchase(
  BuildContext context,
  WidgetRef ref,
  PetSlotOverview overview, {
  required bool isRenewal,
  required int Function() count,
  required String Function(HdPayChannel channel) attempt,
  required Widget Function(AppLocalizations l10n, int balance) sheet,
  required Future<KeepsakePurchaseResult> Function(
          PetSlotRepository repo, HdPayChannel channel, String idempotencyKey)
      start,
  required String Function(AppLocalizations l10n, String date) successMessage,
}) async {
  final repo = ref.read(petSlotRepositoryProvider);
  final store = ref.read(petSlotAttemptStoreProvider);
  final l10n = AppLocalizations.of(context);
  final messenger = ScaffoldMessenger.maybeOf(context);
  void snack(String msg) => messenger?.showSnackBar(SnackBar(content: Text(msg)));
  String? attemptId;
  String? idempotencyKey;
  String? purchaseToken;
  String channelWire = '';
  PetSlotPurchaseStatus? paidStatus;
  String? abortCategory;

  Map<String, Object> props() => {
        'count': count(),
        'is_renewal': isRenewal,
        'channel': channelWire,
      };

  final outcome = await runKeepsakePurchase(
    context: context,
    ref: ref,
    sheet: (balance) => sheet(l10n, balance),
    onChannelConfirmed: (channel) {
      // 同一次购买意图（种类 + 数量 / 名额 + 渠道）跨重试沿用同一个幂等键（code review #1）。
      attemptId = attempt(channel);
      idempotencyKey = store.keyFor(attemptId!);
      channelWire = channel.wire.toLowerCase();
      Analytics.capture('pet_slot_rent_started', props());
    },
    start: (channel) async {
      final id = attemptId ?? attempt(channel);
      try {
        final res = await start(repo, channel, idempotencyKey ?? store.keyFor(id));
        purchaseToken = res.purchaseToken;
        return res;
      } on DioException catch (e) {
        // 4xx 业务拒绝（409 名额已变 / 422 等）= 这次意图已结束 → 下次换新键；
        // 网络错误 / 超时 / 5xx 结果未知 → 保留键，重试由后端按同键重放（不会二次扣款 / 二次出码）。
        final status = e.response?.statusCode;
        if (status != null && status >= 400 && status < 500 && status != 408 && status != 429) {
          store.clear(id);
        }
        rethrow;
      }
    },
    pollPaid: () async {
      final token = purchaseToken;
      if (token == null) return false;
      final st = await repo.purchaseStatus(token);
      switch (st.status) {
        case PetSlotPurchaseStatus.paid:
          paidStatus = st;
          return true;
        case PetSlotPurchaseStatus.expired:
          throw const QrPaymentAborted('EXPIRED');
        case PetSlotPurchaseStatus.failed:
          throw const QrPaymentAborted('FAILED');
        case PetSlotPurchaseStatus.orphanPaid:
        case PetSlotPurchaseStatus.overpaid:
          throw const QrPaymentAborted(kPetSlotAbortPaidNeedsSupport);
        default:
          return false;
      }
    },
    purchasePurpose: 'PET_SLOT_RENT',
    cashPriceIdr: () => overview.unitPrice * count(),
    onQrAborted: (abort) => abortCategory = abort.category,
    onProblem: isRenewal
        ? null
        : (pd) {
            // code review #3：可租数还扣了仍在付款窗内的待付单，页面上的 rentable 可能偏大 →
            // 用 409 扩展成员 `rentable` 给出具体提示，而不是「支付失败」。
            if (pd.typeSlug != kPetSlotRentUnavailableSlug) return false;
            final rentable = (pd.extensions['rentable'] as num?)?.toInt() ?? 0;
            snack(rentable > 0 ? l10n.petSlotRentOnlyN(rentable) : l10n.petSlotRentPendingPayment);
            return true;
          },
  );

  final id = attemptId;
  // 终态（成交 / 过期 / 失败 / 已到账交人工）→ 这次意图结束，清掉幂等键；
  // 关抽屉 / 关面板未付 / 网络错误 → 保留，重试沿用。
  if (id != null && (outcome == KeepsakeFlowOutcome.unlocked || abortCategory != null)) store.clear(id);

  // 不论成败都刷新：名额可能已在别处变化（409 `pet-slot-rent-unavailable` / `pet-slot-renew-invalid`），
  // 宠物状态也可能因发放恢复而变。页面已销毁时（ref 失效）不刷新——下次进页会重取（code review #6）。
  if (context.mounted) {
    ref.invalidate(petSlotOverviewProvider);
    ref.invalidate(petListProvider);
  }

  if (outcome != KeepsakeFlowOutcome.unlocked) {
    switch (abortCategory) {
      case null:
        break; // 关抽屉 / 关面板 / 失败（失败 SnackBar 已由 runKeepsakePurchase 给出）
      case 'EXPIRED':
        snack(l10n.paymentExpiredRetry); // D10b：只在名额流程补提示
      case kPetSlotAbortPaidNeedsSupport:
        snack(l10n.petSlotPaymentProcessing);
      default:
        snack(l10n.idCardHdPurchaseError); // D10
    }
    return PetSlotFlowOutcome.notCompleted;
  }

  Analytics.capture('pet_slot_rent_paid', props());
  // PawCoin 当场成交没有轮询结果：再查一次购买单拿到期日；取不到就按「今天 + days」近似（只用于提示）。
  var expiresAt = paidStatus?.expiresAt;
  final token = purchaseToken;
  if (expiresAt == null && token != null) {
    try {
      expiresAt = (await repo.purchaseStatus(token)).expiresAt;
    } catch (_) {
      expiresAt = null;
    }
  }
  expiresAt ??= DateTime.now().add(Duration(days: overview.days));
  final date = context.mounted ? formatDayMonthYear(context, expiresAt.toLocal()) : '';
  snack(successMessage(l10n, date));
  return PetSlotFlowOutcome.succeeded;
}

/// 抽屉头卡（D-20 / D-21）：图标 +「Slot hewan」+「{price} / slot · {days} hari」。
class PetSlotSheetHeader extends StatelessWidget {
  const PetSlotSheetHeader({super.key, required this.overview});

  final PetSlotOverview overview;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 稿 `.goods`：紫色渐变横幅（135°）+ 白色爪印 30 + 白字标题 / 85% 白字单价。
    return Container(
      key: const ValueKey('petSlotSheetBanner'),
      padding: const EdgeInsets.all(14),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint700],
        ),
        borderRadius: BorderRadius.all(Radius.circular(16)),
      ),
      child: Row(
        children: [
          const Icon(Icons.pets, size: 30, color: AppColors.onAccent),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.petSlotItemName,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
                const SizedBox(height: 2),
                Text(l10n.petSlotUnitPrice(petSlotPrice(overview.unitPrice), overview.days),
                    style: TextStyle(fontSize: 12, color: AppColors.onAccent.withValues(alpha: 0.85))),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 续租勾选状态（抽屉与流程共用一份，流程发起时读它）。
class PetSlotRenewSelection {
  PetSlotRenewSelection(this.max, Iterable<String> initial) : tokens = <String>{...initial};

  /// 默认勾选（multi-pet 4.4 · AC3，见 [defaultRenewSelection]）：只勾正常宠物超出永久名额（默认 + 等级）的那几个，
  /// 最多 `renewableMax` 个、从最早到期的开始勾——升级拿到永久名额后下次续租默认少勾一个。
  factory PetSlotRenewSelection.defaultFor(PetSlotOverview o) => PetSlotRenewSelection(
      o.renewableMax,
      defaultRenewSelection(
        rentals: o.rentals,
        renewableMax: o.renewableMax,
        defaultSlots: o.defaultSlots,
        levelSlots: o.levelSlots,
        petCount: o.petCount,
        readOnlyCount: o.readOnlyCount,
      ));

  /// 最多可勾几个（后端 `renewableMax`）。
  final int max;
  final Set<String> tokens;

  bool canCheck(String token) => tokens.contains(token) || tokens.length < max;

  void toggle(String token) {
    if (tokens.contains(token)) {
      tokens.remove(token);
    } else if (tokens.length < max) {
      tokens.add(token);
    }
  }
}

/// D11 续租抽屉：[PayChannelPicker] + 名额勾选列表（每行「Slot sewa {n}」+「{原到期} → {新到期}」+ 复选框）。
class PetSlotRenewPaySheet extends StatefulWidget {
  const PetSlotRenewPaySheet({
    super.key,
    required this.overview,
    required this.balance,
    required this.selection,
  });

  final PetSlotOverview overview;
  final int balance;
  final PetSlotRenewSelection selection;

  @override
  State<PetSlotRenewPaySheet> createState() => _PetSlotRenewPaySheetState();
}

class _PetSlotRenewPaySheetState extends State<PetSlotRenewPaySheet> {
  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final o = widget.overview;
    final sel = widget.selection;
    final n = sel.tokens.length;
    return PayChannelPicker(
      key: const ValueKey('petSlotRenewSheet'),
      // 稿 D11：无商品头卡，标题「Perpanjang slot」直接起（L2 第二轮）。
      header: null,
      title: l10n.petSlotRenewSheetTitle,
      body: l10n.petSlotRenewSheetNote(o.days),
      price: AsyncData(o.unitPrice),
      onRetryPrice: () {},
      balance: widget.balance,
      confirmLabel: (p) => l10n.keepsakePayConfirm(p == null ? '…' : formatIdrAmount(p)),
      confirmKey: const ValueKey('petSlotRenewConfirm'),
      itemsTotal: n * o.unitPrice,
      itemsLineLabel: l10n.petSlotQuantityTotal(n, petSlotPrice(o.unitPrice)),
      itemsArea: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final r in o.rentals)
            _RenewRow(
              rental: r,
              days: o.days,
              checked: sel.tokens.contains(r.token),
              enabled: sel.canCheck(r.token),
              onTap: () => setState(() => sel.toggle(r.token)),
            ),
        ],
      ),
    );
  }
}

class _RenewRow extends StatelessWidget {
  const _RenewRow({
    required this.rental,
    required this.days,
    required this.checked,
    required this.enabled,
    required this.onTap,
  });

  final PetSlotRental rental;
  final int days;
  final bool checked;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final from = formatDayMonth(context, rental.expiresAt.toLocal());
    final to = formatDayMonthYear(context, rental.renewedExpiry(days).toLocal());
    // 稿 D11 `.sheet .crow`：描边卡片（1.5px #F0F0F0、r14、内边距 11/12、行距 8）；勾上 = 紫边 + 浅紫底；
    // 超上限不可勾 = #FAFAFB 底、内容半透明（`.crow.dis`）。左侧 36 圆（cream2 底）放日历图标。
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          key: ValueKey('petSlotRenewRow-${rental.index}'),
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(14),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
            decoration: BoxDecoration(
              color: checked ? AppColors.mintTint2 : (enabled ? AppColors.card : const Color(0xFFFAFAFB)),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: checked ? AppColors.mint : AppColors.line2, width: 1.5),
            ),
            child: Row(
              children: [
                Opacity(
                  opacity: enabled ? 1 : 0.55,
                  child: Container(
                    width: 36,
                    height: 36,
                    alignment: Alignment.center,
                    decoration: const BoxDecoration(color: AppColors.cream2, shape: BoxShape.circle),
                    child: const Icon(Icons.event_outlined, size: 18, color: AppColors.mint),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(l10n.petSlotRentalName(rental.index),
                          style: TextStyle(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: enabled ? AppColors.ink : AppColors.muted)),
                      const SizedBox(height: 1),
                      Text(l10n.petSlotRenewRowRange(from, to),
                          style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                    ],
                  ),
                ),
                Icon(
                  checked ? Icons.check_box : Icons.check_box_outline_blank,
                  key: ValueKey('petSlotRenewCheck-${rental.index}'),
                  color: checked ? AppColors.mint : (enabled ? AppColors.ink2 : AppColors.line),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
