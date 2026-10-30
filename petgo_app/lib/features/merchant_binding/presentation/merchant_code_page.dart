import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/keyboard_safe_area.dart';
import '../data/merchant_binding_repository.dart';

/// 「Kode toko」门店邀请码页（V1.4.1 商家平台对接 FR-7；定稿 `tailtopia-design/v1.4.1/门店邀请码`）。
///
/// 一页四态：读取中 / 读取失败（D1/D2）→ 未绑定填码（B1–B8）→ 绑定成功（C2）→ 已绑定只读（C1）。
/// 🔴 一个账号只能绑一家店（同一家店可被多人绑），绑后不能更换 —— 本页没有任何更换 / 解绑入口。
/// 门店名本平台读不到（契约只开放邀请码），已绑定只显示码与绑定日期。
class MerchantCodePage extends ConsumerStatefulWidget {
  const MerchantCodePage({super.key});

  static const String routePath = '/me/merchant-code';

  @override
  ConsumerState<MerchantCodePage> createState() => _MerchantCodePageState();
}

class _MerchantCodePageState extends ConsumerState<MerchantCodePage> {
  final TextEditingController _controller = TextEditingController();

  /// 本页刚绑成功的结果（C2）。非空即显示成功页，返回直接回设置页、不回填码。
  MerchantBinding? _justBound;
  MerchantBindError? _error;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _controller.addListener(() {
      // 改动输入即清除红字（B5 / B7）；已绑定（B6）锁定输入，不会走到这里。
      if (_error != null && _error != MerchantBindError.alreadyBound) {
        setState(() => _error = null);
      } else {
        setState(() {});
      }
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _onSubmit() async {
    final l10n = AppLocalizations.of(context);
    final code = _controller.text;
    MerchantBinding? result;
    MerchantBindError? failure;
    // 提交放在确认抽屉的 onConfirm 里：抽屉自持提交态（B4）。无论成败都关抽屉，结果回到本页呈现。
    await showConfirmSheet(
      context,
      title: l10n.merchantCodeConfirmTitle,
      message: l10n.merchantCodeConfirmBody(code),
      confirmLabel: l10n.merchantCodeConfirmYes,
      cancelLabel: l10n.commonCancel,
      icon: Icons.storefront_outlined,
      confirmKey: const ValueKey('merchantCodeConfirm'),
      onConfirm: () async {
        setState(() => _submitting = true);
        try {
          result = await ref.read(merchantBindingRepositoryProvider).bind(code);
        } on MerchantBindException catch (e) {
          failure = e.error;
        } catch (_) {
          failure = MerchantBindError.network;
        }
        return true;
      },
    );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (result != null) {
      ref.invalidate(merchantBindingProvider); // 设置页那一行右值跟着变
      setState(() => _justBound = result);
      return;
    }
    switch (failure) {
      case null:
        return; // 取消
      case MerchantBindError.network:
        showAppToast(context, l10n.merchantCodeSubmitFailed);
      case final MerchantBindError e:
        setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final Widget body;
    final just = _justBound;
    if (just != null) {
      body = _SuccessView(code: just.inviteCode ?? _controller.text, onDone: _close);
    } else {
      body = ref.watch(merchantBindingProvider).when(
            loading: () => const Center(
              child: SizedBox(
                key: ValueKey('merchantCodeLoading'),
                width: 28,
                height: 28,
                child: CircularProgressIndicator(
                    strokeWidth: 3, color: AppColors.mint, backgroundColor: AppColors.violet100),
              ),
            ),
            error: (_, _) => _LoadFailed(onRetry: () => ref.invalidate(merchantBindingProvider)),
            data: (b) => b.bound ? _BoundView(binding: b) : _form(l10n),
          );
    }
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        leading: just != null
            ? const SizedBox.shrink()
            : IconButton(
                key: const ValueKey('merchantCodeBack'),
                icon: const Icon(Icons.chevron_left_rounded, size: 28),
                tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                onPressed: _close,
              ),
        title: just != null ? null : Text(l10n.merchantCodeTitle),
      ),
      body: SafeArea(top: false, child: body),
    );
  }

  void _close() => context.canPop() ? context.pop() : context.go('/me/settings');

  Widget _form(AppLocalizations l10n) {
    final locked = _error == MerchantBindError.alreadyBound;
    final full = _controller.text.length == 6;
    final canSubmit = full && !locked && !_submitting;
    final borderColor = _error != null && !locked
        ? AppColors.popRed
        : (locked ? AppColors.line : AppColors.mint);
    return KeyboardSafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.merchantCodeLead,
                    style: const TextStyle(fontSize: 14, height: 1.6, color: AppColors.ink2)),
                const SizedBox(height: 18),
                Text(l10n.merchantCodeFieldLabel,
                    style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.8,
                        color: AppColors.muted)),
                const SizedBox(height: 8),
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: locked ? const Color(0xFFFAFAFA) : AppColors.card,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: borderColor, width: 1.6),
                  ),
                  child: TextField(
                    key: const ValueKey('merchantCodeInput'),
                    controller: _controller,
                    enabled: !locked && !_submitting,
                    autofocus: true,
                    keyboardType: TextInputType.number,
                    // 只收数字、最多 6 位：粘贴「000 035」这类带空格的码会被滤成 000035。
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(6),
                    ],
                    style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 6,
                        color: locked ? AppColors.muted : AppColors.ink,
                        fontFeatures: const [FontFeature.tabularFigures()]),
                    decoration: const InputDecoration(
                      hintText: '000000',
                      hintStyle: TextStyle(
                          color: AppColors.muted, fontWeight: FontWeight.w500, letterSpacing: 6),
                      border: InputBorder.none,
                      contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 16),
                    ),
                  ),
                ),
                if (_error != null) _errorLine(l10n, _error!),
                if (!locked) _Note(text: l10n.merchantCodeOneStoreNote),
              ],
            ),
          ),
          const Spacer(),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
            child: SizedBox(
              height: 54,
              child: FilledButton(
                key: const ValueKey('merchantCodeSubmit'),
                onPressed: canSubmit ? _onSubmit : null,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  disabledBackgroundColor: AppColors.line,
                  disabledForegroundColor: AppColors.muted,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(l10n.merchantCodeSubmit,
                    style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _errorLine(AppLocalizations l10n, MerchantBindError e) {
    final text = switch (e) {
      MerchantBindError.invalidCode => l10n.merchantCodeInvalid,
      MerchantBindError.alreadyBound => l10n.merchantCodeAlreadyBound,
      MerchantBindError.rateLimited => l10n.rateLimited,
      MerchantBindError.network => l10n.merchantCodeSubmitFailed,
    };
    return Padding(
      key: const ValueKey('merchantCodeError'),
      padding: const EdgeInsets.fromLTRB(2, 8, 2, 0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 1.5),
            child: Icon(Icons.error_outline_rounded, size: 16, color: AppColors.popRed),
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text.rich(TextSpan(
              text: text,
              style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.popRed),
              children: [
                if (e == MerchantBindError.alreadyBound)
                  WidgetSpan(
                    alignment: PlaceholderAlignment.baseline,
                    baseline: TextBaseline.alphabetic,
                    child: GestureDetector(
                      key: const ValueKey('merchantCodeViewBound'),
                      onTap: () {
                        // 换成只读页（C1）：重新拉一次绑定状态。
                        setState(() => _error = null);
                        ref.invalidate(merchantBindingProvider);
                      },
                      child: Padding(
                        padding: const EdgeInsets.only(left: 4),
                        child: Text(l10n.merchantCodeView,
                            style: const TextStyle(
                                fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.mint)),
                      ),
                    ),
                  ),
              ],
            )),
          ),
        ],
      ),
    );
  }
}

class _Note extends StatelessWidget {
  const _Note({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(2, 14, 2, 0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 2),
            child: Icon(Icons.lock_outline_rounded, size: 16, color: AppColors.muted),
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text(text,
                style: const TextStyle(fontSize: 12.5, height: 1.6, color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}

/// D2 读取失败。
class _LoadFailed extends StatelessWidget {
  const _LoadFailed({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.wifi_off_rounded, size: 44, color: AppColors.muted),
            const SizedBox(height: 12),
            Text(l10n.detailNetworkError,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, height: 1.6, color: AppColors.ink2)),
            const SizedBox(height: 16),
            FilledButton(
              key: const ValueKey('merchantCodeRetry'),
              onPressed: onRetry,
              style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
              child: Text(l10n.commonRetry),
            ),
          ],
        ),
      ),
    );
  }
}

/// C2 绑定成功。
class _SuccessView extends StatelessWidget {
  const _SuccessView({required this.code, required this.onDone});

  final String code;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 96,
                    height: 96,
                    decoration:
                        const BoxDecoration(color: AppColors.violet100, shape: BoxShape.circle),
                    alignment: Alignment.center,
                    child: Container(
                      width: 64,
                      height: 64,
                      decoration:
                          const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
                      child: const Icon(Icons.check_rounded, size: 40, color: AppColors.onAccent),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Text(l10n.merchantCodeSuccessTitle,
                      key: const ValueKey('merchantCodeSuccess'),
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
                  const SizedBox(height: 6),
                  Text(l10n.merchantCodeSuccessCaption,
                      style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
                  const SizedBox(height: 18),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
                    decoration: BoxDecoration(
                      color: AppColors.mintTint,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: AppColors.lineViolet),
                    ),
                    child: _CodeText(code: code, fontSize: 26),
                  ),
                ],
              ),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
          child: SizedBox(
            height: 54,
            child: FilledButton(
              key: const ValueKey('merchantCodeDone'),
              onPressed: onDone,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.merchantCodeDone,
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
            ),
          ),
        ),
      ],
    );
  }
}

/// C1 已绑定只读：只显示邀请码与绑定日期（WIB）。门店停用后照常显示。
class _BoundView extends StatelessWidget {
  const _BoundView({required this.binding});

  final MerchantBinding binding;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final at = binding.boundAt;
    return ListView(
      key: const ValueKey('merchantCodeBound'),
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 28),
      children: [
        Container(
          padding: const EdgeInsets.fromLTRB(16, 22, 16, 18),
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(20),
            boxShadow: const [
              BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 3), blurRadius: 12),
            ],
          ),
          child: Column(
            children: [
              Text(l10n.merchantCodeFieldLabel,
                  style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.8,
                      color: AppColors.muted)),
              const SizedBox(height: 6),
              _CodeText(code: binding.inviteCode ?? '—', fontSize: 34),
              if (at != null) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                  decoration: BoxDecoration(
                    color: AppColors.mintTint,
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Text(
                      l10n.merchantCodeBoundSince(formatDayMonthYear(
                          context, at.toUtc().add(const Duration(hours: 7)))),
                      style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
                ),
              ],
            ],
          ),
        ),
        _Note(text: l10n.merchantCodeBoundNote),
      ],
    );
  }
}

class _CodeText extends StatelessWidget {
  const _CodeText({required this.code, required this.fontSize});

  final String code;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    // 字距加在字符右侧：左补同样的量让视觉居中（光学对齐）。
    final spacing = fontSize >= 30 ? 8.0 : 6.0;
    return Padding(
      padding: EdgeInsets.only(left: spacing),
      child: Text(code,
          key: const ValueKey('merchantCodeValue'),
          style: TextStyle(
              fontSize: fontSize,
              fontWeight: FontWeight.w800,
              letterSpacing: spacing,
              color: AppColors.ink,
              fontFeatures: const [FontFeature.tabularFigures()])),
    );
  }
}
