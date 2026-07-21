import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/qr_payment_sheet.dart';
import '../../../pawcoin/presentation/pawcoin_controller.dart';
import '../../data/triage_repository.dart';
import '../../domain/triage_unlock_controller.dart';
import 'triage_paywall.dart';

/// 解锁方式面板（Story 2.4 · 0718 ref21 改版）。并行读免费额度 + PawCoin 余额，
/// 用户**选中一种**方式再点底部「Bayar」确认（不再点即走）。返回所选 [UnlockMethod]（取消=null）。
/// 免费额度耗尽（remaining==0）时不显免费方式；PawCoin 余额不足显「Isi saldo dulu →」跳充值。
Future<UnlockMethod?> showUnlockMethodSheet(
  BuildContext context,
  WidgetRef ref, {
  required int priceIdr,
}) async {
  // bug 20260721-322：await pawCoinProvider.future 拿加载完成的真实余额。
  // 旧写法 `.value?.balance ?? 0` 在用户未进过钱包页（provider 仍 AsyncLoading）时恒读到 0，
  // 误判余额不足显「Top up first」。与 id_card_page 的 `await ...future` 正例一致。
  int balance;
  try {
    balance = (await ref.read(pawCoinProvider.future)).balance;
  } catch (_) {
    balance = 0; // 余额拉取失败 → 按不足降级（仍可免费/现金）
  }
  FreeQuotaView? quota;
  try {
    quota = await ref.read(triageRepositoryProvider).fetchFreeQuota();
  } catch (_) {
    quota = null; // 拉取失败 → 免费方式按不可用降级（仍可 PawCoin/现金）
  }
  final int remaining = quota?.remaining ?? 0;
  final bool freeAvailable = remaining > 0;
  final bool pawcoinEnough = balance >= priceIdr;
  if (!context.mounted) return null;

  return showModalBottomSheet<UnlockMethod>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (BuildContext ctx) {
      final l10n = AppLocalizations.of(ctx);
      // 默认选中：有免费额度选免费，否则选 QRIS（恒可用）。
      UnlockMethod selected = freeAvailable
          ? UnlockMethod.freeQuota
          : UnlockMethod.qris;
      return StatefulBuilder(
        builder: (ctx, setSheet) {
          String payLabel() => selected == UnlockMethod.freeQuota
              ? l10n.triageUnlockOpenFull
              : l10n.triageUnlockPay(_methodName(selected, l10n));
          return SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  Text(
                    l10n.triageUnlockSheetTitle,
                    style: const TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    l10n.triageUnlockSheetBody,
                    style: const TextStyle(
                      fontSize: 13,
                      height: 1.5,
                      color: AppColors.ink2,
                    ),
                  ),
                  const SizedBox(height: 18),
                  if (freeAvailable) ...[
                    _MethodTile(
                      keyValue: 'unlockMethodFreeQuota',
                      icon: Icons.card_giftcard,
                      title: l10n.triageUnlockMethodFree,
                      subtitle: l10n.triageUnlockFreeRemaining(remaining),
                      selected: selected == UnlockMethod.freeQuota,
                      onTap: () =>
                          setSheet(() => selected = UnlockMethod.freeQuota),
                    ),
                    const SizedBox(height: 10),
                  ],
                  _MethodTile(
                    keyValue: 'unlockMethodQris',
                    icon: Icons.qr_code_2,
                    title: l10n.triageUnlockMethodQris,
                    subtitle: formatIdr(priceIdr),
                    selected: selected == UnlockMethod.qris,
                    onTap: () => setSheet(() => selected = UnlockMethod.qris),
                  ),
                  const SizedBox(height: 10),
                  _MethodTile(
                    keyValue: 'unlockMethodPawcoin',
                    icon: Icons.savings_outlined,
                    title: l10n.triageUnlockMethodPawcoin,
                    subtitle: l10n.triageUnlockPawcoinBalance(
                      formatKoin(balance),
                    ),
                    selected: pawcoinEnough && selected == UnlockMethod.pawcoin,
                    enabled: pawcoinEnough,
                    // 余额不足：不可选，右侧「Isi saldo dulu →」跳充值页。
                    trailingAction: pawcoinEnough
                        ? null
                        : l10n.triageUnlockTopupFirst,
                    onTap: pawcoinEnough
                        ? () => setSheet(() => selected = UnlockMethod.pawcoin)
                        : () {
                            Navigator.of(ctx).pop();
                            context.push('/me/pawcoin/recharge');
                          },
                  ),
                  const SizedBox(height: 18),
                  FilledButton(
                    key: const ValueKey('unlockConfirm'),
                    onPressed: () => Navigator.of(ctx).pop(selected),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.mint,
                      foregroundColor: AppColors.onAccent,
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    child: Text(
                      payLabel(),
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      );
    },
  );
}

/// AI 解锁完整流程（两入口复用：结果页 CTA / paywall）：选方式 → 发起解锁 → 现金(QRIS)则弹二维码面板
/// 轮询到账（`pollTriage` 至 `locked==false` → `markUnlocked`）。取消=纯关闭（pending 可复用重复支付）。
Future<void> runAiUnlockFlow(
  BuildContext context,
  WidgetRef ref,
  int triageId,
) async {
  final UnlockMethod? method = await showUnlockMethodSheet(
    context,
    ref,
    priceIdr: kAiUnlockPriceIdr,
  );
  if (method == null || !context.mounted) return;
  final TriageUnlockController notifier = ref.read(
    triageUnlockControllerProvider.notifier,
  );
  await notifier.unlock(triageId, method);
  if (!context.mounted) return;
  final TriageUnlockState st = ref.read(triageUnlockControllerProvider);
  if (st.phase == UnlockPhase.waitingPayment &&
      (st.payload?.isNotEmpty ?? false)) {
    await showQrPaymentSheet(
      context,
      payload: st.payload!,
      orderRef: st.payment?.token, // bug 326：支付号（客服对账）
      pollPaid: () async {
        final TriageResult r = await ref
            .read(triageRepositoryProvider)
            .pollTriage(triageId);
        if (r.locked == false) {
          notifier.markUnlocked(triageId, r);
          return true;
        }
        return false;
      },
    );
  }
}

String _methodName(UnlockMethod m, AppLocalizations l10n) => switch (m) {
  UnlockMethod.freeQuota => l10n.triageUnlockMethodFree,
  UnlockMethod.pawcoin => l10n.triageUnlockMethodPawcoin,
  UnlockMethod.qris => l10n.triageUnlockMethodQris,
};

/// 支付方式卡（ref21）：色块圆角图标 + 标题 + 副行 + 选中紫描边；不可选态右侧显充值链接。
class _MethodTile extends StatelessWidget {
  const _MethodTile({
    required this.keyValue,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
    this.enabled = true,
    this.trailingAction,
  });

  final String keyValue;
  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final bool enabled;
  final String? trailingAction;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final Color border = selected ? AppColors.mint : AppColors.line;
    return InkWell(
      key: ValueKey(keyValue),
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint2 : AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: border, width: selected ? 1.6 : 1.2),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: enabled ? AppColors.mintTint : AppColors.cream2,
                borderRadius: BorderRadius.circular(11),
              ),
              child: Icon(
                icon,
                size: 20,
                color: enabled ? AppColors.mint : AppColors.muted,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      color: enabled ? AppColors.ink : AppColors.textTertiary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppColors.muted,
                    ),
                  ),
                ],
              ),
            ),
            if (trailingAction != null) ...[
              const SizedBox(width: 8),
              Text(
                trailingAction!,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: AppColors.mint,
                ),
              ),
            ] else if (selected)
              const Icon(Icons.check_circle, size: 22, color: AppColors.mint)
            else
              Icon(
                Icons.radio_button_unchecked,
                size: 22,
                color: AppColors.line,
              ),
          ],
        ),
      ),
    );
  }
}
