import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../../shared/widgets/pay_method_cards.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../../profile/domain/id_card.dart' show HdPayChannel;
import '../domain/gift_catalog.dart';
import 'gift_panel_sheet.dart' show giftMaxQuantity, giftMinQuantity;
import 'gift_send_ceremony.dart' show kGiftBoxClosedAsset;
import 'gift_style.dart';

/// 祝福语上限（Unicode 码点，D-6；与服务端 `codePointCount` 同口径）。
const int giftMessageMaxRunes = 500;

/// 包装页一次「Kirim」交给送礼编排的参数。
class GiftWrapRequest {
  const GiftWrapRequest({required this.quantity, required this.channel, this.message});

  final int quantity;
  final HdPayChannel channel;

  /// trim 后为空 → null（不传）。
  final String? message;
}

/// 编排处理一次「Kirim」后告诉包装页怎么办。
sealed class GiftWrapSubmitResult {
  const GiftWrapSubmitResult();
}

/// 失败（编排已 Toast）：停留本页、内容保留（C8）。
class GiftWrapStay extends GiftWrapSubmitResult {
  const GiftWrapStay();
}

/// 祝福语命中敏感词（422 `gift-message-blocked`）：不扣款、输入框变红 + 提示（C3）。
class GiftWrapMessageBlocked extends GiftWrapSubmitResult {
  const GiftWrapMessageBlocked();
}

/// 结束本页：以 [outcome] 为 pop 值交回编排。
class GiftWrapDone extends GiftWrapSubmitResult {
  const GiftWrapDone(this.outcome);
  final Object outcome;
}

/// 按 runes 截断的输入限制（防粘贴超限）。
class RuneLimitFormatter extends TextInputFormatter {
  const RuneLimitFormatter(this.max);
  final int max;

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final runes = newValue.text.runes;
    if (runes.length <= max) return newValue;
    // 输入法组字中不截（截了会打乱组字区）；照 LengthLimitingTextInputFormatter：已满再输入 → 保持原值
    // （不吞掉末尾、不跳光标，code review #5）。
    if (newValue.composing.isValid) return newValue;
    if (oldValue.text.runes.length >= max) return oldValue;
    final cut = String.fromCharCodes(runes.take(max));
    return TextEditingValue(text: cut, selection: TextSelection.collapsed(offset: cut.length));
  }
}

/// 送礼包装页（V1.5.0 gift-pack Story 2-2 · UI 稿 C1 ~ C9）：礼物区 + 祝福语 + 支付区（收起 / 展开）。
///
/// 支付区不走 `PayChannelPicker`：两张卡用共享 [PayMethodCards]（D-13）。送出调用、幂等、QRIS 由编排
/// （`gift_send_flow.dart`）经 [onSubmit] 完成；充值往返经 [onTopUp]（回来时礼物 / 数量 / 祝福语保留）。
class GiftWrapPage extends ConsumerStatefulWidget {
  const GiftWrapPage({
    super.key,
    required this.catalog,
    required this.item,
    required this.onSubmit,
    required this.onTopUp,
    this.initialQuantity = 1,
  });

  final GiftCatalog catalog;
  final GiftItem item;
  final Future<GiftWrapSubmitResult> Function(GiftWrapRequest request) onSubmit;
  final Future<void> Function() onTopUp;
  final int initialQuantity;

  @override
  ConsumerState<GiftWrapPage> createState() => _GiftWrapPageState();
}

class _GiftWrapPageState extends ConsumerState<GiftWrapPage> {
  final _message = TextEditingController();
  late int _qty;
  bool? _expanded;
  PayMethod? _choice;
  bool _busy = false;
  bool _blocked = false;
  bool _wasShort = false;
  bool _lowReported = false;
  String _lastText = '';

  @override
  void initState() {
    super.initState();
    _qty = widget.initialQuantity.clamp(giftMinQuantity, giftMaxQuantity);
    _message.addListener(() {
      // 只有文字真变了才刷新计数 / 解除红态（光标移动不算，code review #6）
      if (_message.text == _lastText) return;
      _lastText = _message.text;
      setState(() => _blocked = false);
    });
  }

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  int get _total => widget.item.price * _qty;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final r = widget.catalog.recipient;
    final balanceAsync = ref.watch(pawCoinProvider);
    // 余额加载中：不当 0 处理（不自动展开、不默认直接付款、主按钮不可点，code review #2）
    final balanceLoading = !balanceAsync.hasValue;
    final balance = balanceAsync.value?.balance ?? 0;
    final total = _total;
    final short = !balanceLoading && balance < total;
    final lowAmount = total < widget.catalog.qrisMinAmount;
    // 有效选择：用户选的仍可用就用；否则默认（够 → PawCoin；不够 → 门槛以上直接付款，以下无可选）。
    PayMethod? effective = _choice;
    if (effective == PayMethod.pawcoin && short) effective = null;
    if (effective == PayMethod.direct && lowAmount) effective = null;
    effective ??= short ? (lowAmount ? null : PayMethod.direct) : PayMethod.pawcoin;
    if (balanceLoading && effective == PayMethod.pawcoin) effective = null;
    // 由够变不够（如加了数量）→ 重新自动展开（code review #7）
    if (short && !_wasShort) _expanded = null;
    _wasShort = short;
    final expanded = _expanded ?? short;
    if (lowAmount && expanded && !_lowReported) {
      // 低于 QRIS 门槛提示真正露出时才报（code review #3）
      _lowReported = true;
      Analytics.capture('gift_qris_low_amount_shown', {'total_amount': total});
    }
    final exp = widget.item.senderExp * _qty;

    final species = switch (r.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final sub = [?species, if (r.breed != null && r.breed!.isNotEmpty) r.breed!].join(' · ');

    return PopScope(
      canPop: !_busy,
      child: Scaffold(
        backgroundColor: GiftStyle.tint,
        appBar: AppBar(
          backgroundColor: GiftStyle.tint,
          surfaceTintColor: Colors.transparent,
          elevation: 0,
          titleSpacing: 0,
          title: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Text(l10n.giftWrapTitle(r.name),
                key: const ValueKey('giftWrapTitle'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
            if (sub.isNotEmpty) Text(sub, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ]),
        ),
        body: AbsorbPointer(
          absorbing: _busy,
          child: Column(children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                children: [
                  _giftArea(l10n, locale, exp),
                  const SizedBox(height: 16),
                  _messageField(l10n, r.name),
                ],
              ),
            ),
            _payArea(l10n, balance, total, short, lowAmount, effective, expanded, balanceLoading),
          ]),
        ),
      ),
    );
  }

  Widget _giftArea(AppLocalizations l10n, Locale locale, int exp) => Container(
        key: const ValueKey('giftWrapGift'),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18)),
        child: Row(children: [
          // 默认礼盒（不可选，D-14 `kGiftBoxClosedAsset`；正式图同名覆盖）
          Image.asset(kGiftBoxClosedAsset,
              key: const ValueKey('giftWrapBox'),
              width: 56,
              height: 56,
              errorBuilder: (_, _, _) => const Icon(Icons.card_giftcard, color: Color(0xFFE11D48), size: 30)),
          const SizedBox(width: 10),
          giftIcon(widget.item.iconUrl, size: 40),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(widget.item.nameFor(locale),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
              if (exp > 0)
                Text(l10n.giftWrapExpHint(exp),
                    key: const ValueKey('giftWrapExp'),
                    style: const TextStyle(fontSize: 12, color: AppColors.mint, fontWeight: FontWeight.w600)),
            ]),
          ),
        ]),
      );

  Widget _messageField(AppLocalizations l10n, String petName) {
    final len = _message.text.runes.length;
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: _blocked ? AppColors.danger : AppColors.line, width: _blocked ? 1.5 : 1),
    );
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(l10n.giftMessageLabel,
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
      const SizedBox(height: 8),
      Stack(children: [
        TextField(
          key: const ValueKey('giftWrapMessage'),
          controller: _message,
          minLines: 4,
          maxLines: 6,
          inputFormatters: const [RuneLimitFormatter(giftMessageMaxRunes)],
          decoration: InputDecoration(
            hintText: l10n.giftMessageHint(petName),
            filled: true,
            fillColor: Colors.white,
            contentPadding: const EdgeInsets.fromLTRB(14, 12, 14, 28),
            enabledBorder: border,
            focusedBorder: border.copyWith(
                borderSide: BorderSide(color: _blocked ? AppColors.danger : AppColors.mint, width: 1.5)),
          ),
        ),
        Positioned(
          right: 12,
          bottom: 8,
          child: Text('$len/$giftMessageMaxRunes',
              key: const ValueKey('giftWrapMessageCount'),
              style: const TextStyle(fontSize: 11, color: AppColors.muted)),
        ),
      ]),
      if (_blocked)
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(l10n.giftMessageBlocked,
              key: const ValueKey('giftWrapMessageBlocked'),
              style: const TextStyle(fontSize: 12, color: AppColors.danger)),
        ),
    ]);
  }

  Widget _payArea(AppLocalizations l10n, int balance, int total, bool short, bool lowAmount, PayMethod? effective,
      bool expanded, bool balanceLoading) {
    final amount = formatIdrAmount(total);
    final String label;
    if (balanceLoading && effective == null) {
      label = l10n.giftSendCoins(amount);
    } else if (effective == PayMethod.pawcoin) {
      label = l10n.giftSendCoins(amount);
    } else if (effective == PayMethod.direct) {
      label = l10n.giftSendConfirm(amount);
    } else {
      label = l10n.giftCoinsNotEnough(amount);
    }
    final toggle = InkWell(
      key: const ValueKey('giftWrapPayToggle'),
      onTap: _busy ? null : () => setState(() => _expanded = !expanded),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(children: [
          Text(l10n.payMethodChoose,
              style: TextStyle(
                  fontSize: 13,
                  fontWeight: expanded ? FontWeight.w700 : FontWeight.w500,
                  color: _busy ? AppColors.muted : AppColors.ink)),
          const Spacer(),
          Icon(expanded ? Icons.keyboard_arrow_down : Icons.keyboard_arrow_up, color: AppColors.muted),
        ]),
      ),
    );
    return Container(
      key: ValueKey('giftWrapPay-${expanded ? 'expanded' : 'collapsed'}'),
      padding: EdgeInsets.fromLTRB(16, 12, 16, 12 + MediaQuery.paddingOf(context).bottom),
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (!expanded)
          Row(children: [
            Text(effective == PayMethod.direct ? l10n.payMethodDirect : l10n.giftPayWithCoins,
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const Spacer(),
            Text('${l10n.pawcoinBalanceLabel}: ${formatIdrAmount(balance)}',
                key: const ValueKey('giftWrapBalance'),
                style: const TextStyle(
                    fontSize: 12, color: AppColors.muted, fontFeatures: [FontFeature.tabularFigures()])),
          ]),
        const SizedBox(height: 8),
        _quantity(l10n),
        if (expanded) ...[
          toggle,
          PayMethodCards(
            coinAmount: total,
            cashAmount: total,
            balance: balance,
            selected: effective,
            onSelect: (m) => setState(() => _choice = m),
            onTopUp: _topUp,
            directDisabledReason:
                lowAmount ? l10n.giftQrisMinimum(formatIdrAmount(widget.catalog.qrisMinAmount)) : null,
          ),
          if (lowAmount)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(l10n.giftQrisMinimumHint,
                  key: const ValueKey('giftWrapQrisHint'),
                  style: const TextStyle(fontSize: 11.5, color: AppColors.muted)),
            ),
          const SizedBox(height: 8),
          Text(l10n.giftNoRefundNote, style: const TextStyle(fontSize: 11, color: AppColors.muted)),
          const SizedBox(height: 10),
          _sendButton(label, effective),
        ] else ...[
          const SizedBox(height: 10),
          _sendButton(label, effective),
          const SizedBox(height: 6),
          Text(l10n.giftNoRefundNote,
              textAlign: TextAlign.center, style: const TextStyle(fontSize: 11, color: AppColors.muted)),
          toggle,
        ],
      ]),
    );
  }

  Widget _quantity(AppLocalizations l10n) {
    Widget chip(int n) => Padding(
          padding: const EdgeInsets.only(right: 6),
          child: ChoiceChip(
            key: ValueKey('giftWrapQty-$n'),
            label: Text(l10n.giftQuantityChip(n)),
            selected: _qty == n,
            showCheckmark: false,
            selectedColor: AppColors.mintTint,
            onSelected: (_) => setState(() => _qty = n),
          ),
        );
    return Row(children: [
      chip(1),
      chip(5),
      chip(10),
      const Spacer(),
      IconButton(
        key: const ValueKey('giftWrapQtyMinus'),
        onPressed: _qty > giftMinQuantity ? () => setState(() => _qty--) : null,
        icon: const Icon(Icons.remove_circle_outline),
      ),
      Text('$_qty',
          key: const ValueKey('giftWrapQty'),
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
      IconButton(
        key: const ValueKey('giftWrapQtyPlus'),
        onPressed: _qty < giftMaxQuantity ? () => setState(() => _qty++) : null,
        icon: const Icon(Icons.add_circle_outline),
      ),
    ]);
  }

  Widget _sendButton(String label, PayMethod? method) => SizedBox(
        height: 48,
        child: FilledButton(
          key: const ValueKey('giftWrapSend'),
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.mint,
            disabledBackgroundColor: AppColors.line,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
          ),
          onPressed: method == null || _busy ? null : () => _send(method),
          child: _busy
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white))
              : Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        ),
      );

  Future<void> _topUp() async {
    await widget.onTopUp();
    // 回来后：余额够则自动选回 PawCoin（清掉用户之前的选择，按默认规则重算）。
    if (mounted) setState(() => _choice = null);
  }

  Future<void> _send(PayMethod method) async {
    final text = _message.text.trim();
    setState(() => _busy = true);
    final res = await widget.onSubmit(GiftWrapRequest(
      quantity: _qty,
      channel: method == PayMethod.pawcoin ? HdPayChannel.pawcoin : HdPayChannel.qris,
      message: text.isEmpty ? null : text,
    ));
    if (!mounted) return;
    switch (res) {
      case GiftWrapStay():
        setState(() => _busy = false);
      case GiftWrapMessageBlocked():
        setState(() {
          _busy = false;
          _blocked = true;
        });
      case GiftWrapDone(:final outcome):
        Navigator.of(context).pop(outcome);
    }
  }
}
