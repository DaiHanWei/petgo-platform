import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/triage_unlock_controller.dart';
import 'unlock_method_sheet.dart';

/// 千分位格式化印尼盾（Story 2.4）：10000 → `Rp10.000`。避免引 intl locale 分歧，手工插点。
String formatIdr(int amount) {
  final digits = amount.toString();
  final buf = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) buf.write('.');
    buf.write(digits[i]);
  }
  return 'Rp$buf';
}

/// 详建锁定态 paywall（Story 2.4）。替代「居家护理建议」卡内容：占位遮罩（**非模糊真文字**——
/// 后端锁定时 advice 本为 null，用骨架占位防截图还原）+ 解锁 CTA。
///
/// 无障碍：装饰性占位骨架 [ExcludeSemantics] 对读屏隐藏；整卡一条 [Semantics] 提示「详建已锁定，可解锁」，
/// CTA 按钮自带可读标签。红色永不锁——本 widget 仅在 `result.isDetailLocked`（非红）时被结果页渲染。
class TriagePaywall extends ConsumerWidget {
  const TriagePaywall({super.key, required this.triageId});

  final int triageId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final price = formatIdr(kAiUnlockPriceIdr);

    // 额度/余额不足错误 → SnackBar 友好提示（不显 ProblemDetail 原文）。
    ref.listen<TriageUnlockState>(triageUnlockControllerProvider, (prev, next) {
      if (next.phase == UnlockPhase.error && next.triageId == triageId) {
        final msg = switch (next.errorKind) {
          UnlockErrorKind.quotaExhausted => l10n.triageUnlockQuotaExhausted,
          UnlockErrorKind.insufficientBalance => l10n.triageUnlockInsufficientBalance,
          _ => l10n.triageUnlockFailed,
        };
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(SnackBar(content: Text(msg)));
      }
    });

    final state = ref.watch(triageUnlockControllerProvider);
    final busy = state.phase == UnlockPhase.submitting && state.triageId == triageId;

    return Semantics(
      label: l10n.triageUnlockLockedSemantics,
      container: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          // 装饰性占位骨架（读屏隐藏）。
          ExcludeSemantics(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  _skeletonLine(widthFactor: 0.9),
                  _skeletonLine(widthFactor: 0.75),
                  _skeletonLine(widthFactor: 0.82),
                ],
              ),
            ),
          ),
          Row(
            children: <Widget>[
              const Icon(Icons.lock_outline, size: 18, color: AppColors.muted),
              const SizedBox(width: 6),
              Expanded(
                child: Text(l10n.triageUnlockLockedTitle,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ),
            ],
          ),
          const SizedBox(height: 12),
          FilledButton(
            key: const ValueKey('triageUnlockCta'),
            onPressed: busy
                ? null
                : () async {
                    final method = await showUnlockMethodSheet(context, ref,
                        priceIdr: kAiUnlockPriceIdr);
                    if (method == null) return;
                    await ref
                        .read(triageUnlockControllerProvider.notifier)
                        .unlock(triageId, method);
                  },
            style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
            child: busy
                ? const SizedBox(
                    height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(l10n.triageUnlockCta(price)),
          ),
        ],
      ),
    );
  }

  Widget _skeletonLine({required double widthFactor}) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: FractionallySizedBox(
          alignment: Alignment.centerLeft,
          widthFactor: widthFactor,
          child: Container(
            height: 11,
            decoration: BoxDecoration(
              color: AppColors.line,
              borderRadius: BorderRadius.circular(6),
            ),
          ),
        ),
      );
}
