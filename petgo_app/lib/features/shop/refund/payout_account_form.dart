/// 线下打款收款账户表单（v1.4.2 shop Story 9.2 AC3.5：自 `RefundMethodPageV2` 抽出，行为不变）。
///
/// 渠道三选一胶囊 + 「Nama pemilik rekening」+「Nomor rekening」+ 提交后不可改说明。
/// 校验照线上：失焦即校验、错框红描边 + 框下红字；提交前 [PayoutAccountFormController.validateAll] 全量校验。
///
/// 🔒 账号 / 户名是 PII：本组件与控制器**不记日志、不埋点、不落本地**；页面 dispose 时由持有者释放控制器。
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/shop_return.dart';
import '../presentation/widgets/shop_controls.dart';

/// 表单状态：渠道、两个输入框与逐字段错误。
class PayoutAccountFormController extends ChangeNotifier {
  PayoutAccountFormController([this._channel = PayoutChannel.bca]);

  static const String holderId = 'holder';
  static const String accountId = 'account';

  PayoutChannel _channel;
  final TextEditingController holderController = TextEditingController();
  final TextEditingController accountController = TextEditingController();

  /// 逐字段错误（null = 通过）。
  ///
  /// 🔴 这是钱出去的那一步，校验必须贴着字段（2026-08-27 补，原在 `RefundMethodPageV2`）。
  final Map<String, String?> _errors = {};

  PayoutChannel get channel => _channel;
  String get account => accountController.text.trim();
  String get holder => holderController.text.trim();

  String? errorOf(String id) => _errors[id];

  set channel(PayoutChannel c) {
    if (c == _channel) return;
    _channel = c;
    notifyListeners();
  }

  /// 单字段校验。返回 null 表示通过。
  ///
  /// ⚠️ 位数范围与 `refundAccountNumberHint`（"10–13 digits"）保持一致 —— 提示和校验
  /// 说的必须是同一件事，否则用户照着提示填却被拦下。OVO / GoPay 同口径（待财务确认手机号钱包位数）。
  String? errorFor(AppLocalizations l10n, String id) {
    switch (id) {
      case holderId:
        return holder.isEmpty ? l10n.refundAccountHolderRequired : null;
      case accountId:
        final v = account;
        if (v.isEmpty) return l10n.refundMethodAccountRequired;
        if (!RegExp(r'^\d{10,13}$').hasMatch(v)) return l10n.refundAccountNumberInvalid;
        return null;
    }
    return null;
  }

  void revalidate(AppLocalizations l10n, String id) {
    final e = errorFor(l10n, id);
    if (_errors[id] == e) return;
    _errors[id] = e;
    notifyListeners();
  }

  /// 提交前全量校验。任一不过即把全部错误一次性显示出来。
  bool validateAll(AppLocalizations l10n) {
    final next = <String, String?>{
      for (final id in const [holderId, accountId]) id: errorFor(l10n, id),
    };
    _errors
      ..clear()
      ..addAll(next);
    notifyListeners();
    return next.values.every((e) => e == null);
  }

  /// 服务端拒绝后把错误挂到对应字段（9.2 AC4.3：422 → 账号框下）。
  void setError(String id, String? message) {
    _errors[id] = message;
    notifyListeners();
  }

  @override
  void dispose() {
    holderController.dispose();
    accountController.dispose();
    super.dispose();
  }
}

/// 收款账号 / 户名的 UTF-8 字节上限，与后端 `ShopOrderRefundService.PAYOUT_FIELD_MAX_BYTES`（150，
/// 加密列容量）一致：超出的输入直接拒收，不等到提交被 422 打回。
const int kPayoutFieldMaxBytes = 150;

/// 按 UTF-8 字节数限长（户名可能含多字节字符，按字符数限会放过超长串）。超限 → 保留旧值。
class Utf8ByteLimitFormatter extends TextInputFormatter {
  const Utf8ByteLimitFormatter(this.maxBytes);

  final int maxBytes;

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) =>
      utf8.encode(newValue.text).length > maxBytes ? oldValue : newValue;
}

class PayoutAccountForm extends StatelessWidget {
  const PayoutAccountForm({super.key, required this.controller});

  final PayoutAccountFormController controller;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 6,
            children: [
              for (final c in PayoutChannel.values)
                ShopChip(
                  key: ValueKey('refundChannelV2_${c.name}'),
                  label: payoutChannelLabel(l10n, c),
                  selected: controller.channel == c,
                  onTap: () => controller.channel = c,
                ),
            ],
          ),
          const SizedBox(height: 11),
          _field(l10n, l10n.refundAccountHolderLabel, controller.holderController,
              hint: l10n.refundAccountHolderPlaceholder,
              inputFormatters: const [Utf8ByteLimitFormatter(kPayoutFieldMaxBytes)],
              id: PayoutAccountFormController.holderId),
          const SizedBox(height: 11),
          _field(l10n, l10n.refundAccountNumberLabel, controller.accountController,
              hint: l10n.refundAccountNumberPlaceholder,
              helper: l10n.refundAccountNumberHint,
              keyboardType: TextInputType.number,
              inputFormatters: [
                FilteringTextInputFormatter.digitsOnly,
                const Utf8ByteLimitFormatter(kPayoutFieldMaxBytes),
              ],
              id: PayoutAccountFormController.accountId),
          const SizedBox(height: 7),
          // 🔴 提交后不可改 —— 打错账号的钱找回来要走人工，提前说清。
          Text(l10n.refundReviewIrreversibleHint, style: ShopText.meta),
        ],
      ),
    );
  }

  Widget _field(AppLocalizations l10n, String label, TextEditingController textController,
      {required String id,
      String? hint,
      String? helper,
      TextInputType? keyboardType,
      List<TextInputFormatter>? inputFormatters}) {
    final error = controller.errorOf(id);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: ShopText.meta.copyWith(fontSize: 10, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        // 失焦即校验：不等到提交才告诉用户填错了。
        Focus(
          onFocusChange: (has) {
            if (!has) controller.revalidate(l10n, id);
          },
          child: TextField(
            key: ValueKey('refundField_$id'),
            controller: textController,
            keyboardType: keyboardType,
            inputFormatters: inputFormatters,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
            decoration: InputDecoration(
              hintText: hint,
              // 🔴 与输入值同字号（12）。原先 hint 走 ShopText.body(10.5)，
              //    用户一开始打字框内文字就跳一档。
              hintStyle: const TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w400, color: ShopColors.text4),
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
              border: _border(ShopColors.border),
              // 🔴 错误边框保持红（ShopColors.error）：强调色已改为品牌紫，
              //    错误态若跟着变紫，「这里填错了」就读不出来了。
              enabledBorder: _border(error == null ? ShopColors.border : ShopColors.error),
              focusedBorder: _border(error == null ? ShopColors.purple : ShopColors.error),
            ),
          ),
        ),
        if (error != null) _errorLine(error),
        if (error == null && helper != null)
          Padding(
            padding: const EdgeInsets.only(top: 3),
            child: Text(helper, style: ShopText.meta.copyWith(fontSize: 9.5)),
          ),
      ],
    );
  }

  Widget _errorLine(String text) => Padding(
        padding: const EdgeInsets.only(top: 3),
        child: Text(text,
            key: const ValueKey('refundFieldError'),
            style: ShopText.meta.copyWith(fontSize: 9.5, color: ShopColors.errorText)),
      );

  OutlineInputBorder _border(Color c) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
        borderSide: BorderSide(color: c),
      );
}

/// 渠道胶囊文案（复用线上 key）。
String payoutChannelLabel(AppLocalizations l10n, PayoutChannel c) => switch (c) {
      PayoutChannel.bca => l10n.refundChannelBca,
      PayoutChannel.ovo => l10n.refundChannelOvo,
      PayoutChannel.gopay => l10n.refundChannelGopay,
    };
