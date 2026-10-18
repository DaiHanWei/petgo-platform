import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme/colors.dart';
import '../../features/profile/domain/id_card.dart';
import '../../l10n/app_localizations.dart';
import 'price_load_retry.dart';

/// 印尼千分位（点分隔）：`5000` → `5.000`。付费抽屉与购买按钮共用，展示口径只有一份。
String formatIdrAmount(int v) {
  final d = v.toString();
  final b = StringBuffer();
  for (var i = 0; i < d.length; i++) {
    if (i > 0 && (d.length - i) % 3 == 0) b.write('.');
    b.write(d[i]);
  }
  return b.toString();
}

/// 一次性付费的「选渠道」抽屉主体（KTP 高清图 Story 6.3 抽出；V1.3.2 Story 3.2 起 Tailsonality / 护照 / 登机牌复用）。
///
/// 放在 `showModalBottomSheet` 里，确认时 `pop(HdPayChannel)`，下滑关闭 → null。
/// PawCoin 余额够则默认选；不够 → PawCoin 行置灰 + 充值链接跳 `/me/pawcoin/recharge`。
/// 价格由调用方传入（后端实时下发、无本地兜底）：失败显示重试，未取到一律禁确认。
///
/// V1.4.0 gift Story 1.3（AD-13）加了 4 个**可选**参数（[qrisDisabledReason] / [qrisDisabledHint] /
/// [onConfirm] / [onTopUp]）。🔴 **都不传时像素 / 行为 / key 与改动前一致**（5 个既有调用方零改动）。
class PayChannelPicker extends StatefulWidget {
  const PayChannelPicker({
    super.key,
    required this.header,
    required this.title,
    required this.body,
    required this.price,
    required this.onRetryPrice,
    required this.balance,
    required this.confirmLabel,
    this.confirmKey = const ValueKey('hdPayConfirm'),
    this.retryKey = const ValueKey('hdPriceRetry'),
    this.qrisDisabledReason,
    this.qrisDisabledHint,
    this.onConfirm,
    this.onTopUp,
  });

  final Widget header;
  final String title;
  final String body;
  final AsyncValue<int> price;
  final VoidCallback onRetryPrice;
  final int balance;

  /// 确认按钮文案；入参为当前价（未取到为 null）。
  final String Function(int? price) confirmLabel;
  final Key confirmKey;
  final Key retryKey;

  /// 非空 → QRIS 行禁用（半透明、灰图标、无单选圈），副标题换成这句（送礼 C2「Minimal Rp10.000」）。
  final String? qrisDisabledReason;

  /// 非空 → QRIS 行下方一行小字提示（送礼 C2「Isi PawCoin biar …」）。
  final String? qrisDisabledHint;

  /// 非空 → 点确认**不立刻 pop**：按钮内转圈、渠道行与按钮禁点（送礼 C5 / S4）；
  /// 返回 true → `pop(渠道)`，false → 恢复按钮、抽屉保留可重试（C8）。
  final Future<bool> Function(HdPayChannel channel)? onConfirm;

  /// 非空 → 余额不足时「先充值」改为 `pop()` 后回调它（由调用方 await 充值页再重开抽屉，S3）；
  /// 为 null 时沿用 `pop(); push('/me/pawcoin/recharge')`。
  final VoidCallback? onTopUp;

  @override
  State<PayChannelPicker> createState() => _PayChannelPickerState();
}

class _PayChannelPickerState extends State<PayChannelPicker> {
  /// 用户显式点选的渠道；null=按余额够不够自动定（价格加载完成后才有意义）。
  HdPayChannel? _picked;

  /// [PayChannelPicker.onConfirm] 进行中（C5）：按钮转圈、渠道行与按钮禁点、不可返回关闭。
  bool _busy = false;

  Future<void> _confirm(HdPayChannel channel) async {
    final onConfirm = widget.onConfirm;
    if (onConfirm == null) {
      Navigator.of(context).pop(channel);
      return;
    }
    if (_busy) return;
    setState(() => _busy = true);
    bool ok = false;
    try {
      ok = await onConfirm(channel);
    } catch (_) {
      ok = false;
    }
    if (!mounted) return;
    if (ok) {
      Navigator.of(context).pop(channel);
    } else {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final priceAsync = widget.price;
    final int? priceIdr = priceAsync.value;
    final enoughCoin = priceIdr != null && widget.balance >= priceIdr;
    final qrisDisabled = widget.qrisDisabledReason != null;
    // 默认：余额够 → PawCoin，否则 QRIS；QRIS 被禁用且余额不足 → **无选中**、确认置灰（送礼 C4）。
    final HdPayChannel? selected = _picked ??
        (enoughCoin ? HdPayChannel.pawcoin : (qrisDisabled ? null : HdPayChannel.qris));
    final Widget sheet = SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 6, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration:
                    BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            widget.header,
            const SizedBox(height: 16),
            Text(widget.title,
                style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text(widget.body,
                style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.5)),
            const SizedBox(height: 18),
            if (priceAsync.hasError) ...[
              Center(
                child: PriceLoadRetry(key: widget.retryKey, onRetry: widget.onRetryPrice),
              ),
              const SizedBox(height: 10),
            ],
            _PayMethodTile(
              icon: Icons.savings_outlined,
              title: 'PawCoin',
              subtitle: priceIdr == null
                  ? l10n.idCardHdPawcoinSub(formatIdrAmount(widget.balance), '…')
                  : l10n.idCardHdPawcoinSub(formatIdrAmount(widget.balance), formatIdrAmount(priceIdr)),
              selected: enoughCoin && selected == HdPayChannel.pawcoin,
              enabled: enoughCoin,
              trailingAction: priceIdr == null || enoughCoin ? null : l10n.triageUnlockTopupFirst,
              onTap: _busy
                  ? null
                  : enoughCoin
                      ? () => setState(() => _picked = HdPayChannel.pawcoin)
                      : priceIdr == null
                          ? () {}
                          : widget.onTopUp != null
                              ? () {
                                  Navigator.of(context).pop();
                                  widget.onTopUp!();
                                }
                              : () {
                                  Navigator.of(context).pop();
                                  context.push('/me/pawcoin/recharge');
                                },
            ),
            const SizedBox(height: 10),
            if (qrisDisabled)
              Opacity(
                key: const ValueKey('payQrisDisabled'),
                opacity: 0.5,
                child: _PayMethodTile(
                  icon: Icons.qr_code_2,
                  title: 'QRIS',
                  subtitle: widget.qrisDisabledReason!,
                  selected: false,
                  enabled: false,
                  hideIndicator: true,
                  onTap: null,
                ),
              )
            else
              _PayMethodTile(
                icon: Icons.qr_code_2,
                title: 'QRIS',
                subtitle: priceIdr == null ? '…' : 'Rp${formatIdrAmount(priceIdr)}',
                selected: selected == HdPayChannel.qris,
                onTap: _busy ? null : () => setState(() => _picked = HdPayChannel.qris),
              ),
            if (widget.qrisDisabledHint != null) ...[
              const SizedBox(height: 6),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text(widget.qrisDisabledHint!,
                    key: const ValueKey('payQrisDisabledHint'),
                    style: const TextStyle(fontSize: 11.5, color: AppColors.muted)),
              ),
            ],
            const SizedBox(height: 18),
            FilledButton(
              key: widget.confirmKey,
              // 价格未取到（加载中/失败）禁付款：用户必须先看到本次费用（417 同类无兜底）。
              // 无选中（送礼 C4）同样禁；扣款中（C5）按钮保持紫底、点击无效。
              onPressed: priceIdr == null || selected == null
                  ? null
                  : _busy
                      ? () {}
                      : () => _confirm(selected),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: _busy
                  ? const SizedBox(
                      key: ValueKey('payConfirmBusy'),
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : Text(widget.confirmLabel(priceIdr),
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
    // 扣款中不许返回键 / 点遮罩 / 下滑关掉（结果未知时关掉会让用户以为没扣）。不传 onConfirm 时不包，树结构不变。
    // PopScope 只挡返回键与遮罩（maybePop）；BottomSheet 的下滑关闭直接 Navigator.pop、不经 PopScope，
    // 所以扣款中再用内层竖向拖拽手势把拖动吃掉（内层识别器先于 BottomSheet 的赢得手势竞技场）。
    if (widget.onConfirm == null) return sheet;
    return PopScope(
      canPop: !_busy,
      child: GestureDetector(
        behavior: _busy ? HitTestBehavior.opaque : null,
        onVerticalDragStart: _busy ? (_) {} : null,
        onVerticalDragUpdate: _busy ? (_) {} : null,
        onVerticalDragEnd: _busy ? (_) {} : null,
        child: sheet,
      ),
    );
  }
}

class _PayMethodTile extends StatelessWidget {
  const _PayMethodTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
    this.enabled = true,
    this.trailingAction,
    this.hideIndicator = false,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final bool enabled;
  final String? trailingAction;

  /// true → 右侧不画单选圈（QRIS 被禁用时，C2）。
  final bool hideIndicator;

  /// null = 不可点（扣款中 / QRIS 被禁用）。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint2 : AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
              color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.6 : 1.2),
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
              child: Icon(icon, size: 20, color: enabled ? AppColors.mint : AppColors.muted),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title,
                      style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: enabled ? AppColors.ink : AppColors.textTertiary)),
                  const SizedBox(height: 2),
                  Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ),
            ),
            if (trailingAction != null) ...[
              const SizedBox(width: 8),
              Text(trailingAction!,
                  style: const TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint)),
            ] else if (hideIndicator)
              const SizedBox.shrink()
            else if (selected)
              const Icon(Icons.check_circle, size: 22, color: AppColors.mint)
            else
              Icon(Icons.radio_button_unchecked, size: 22, color: AppColors.line),
          ],
        ),
      ),
    );
  }
}
