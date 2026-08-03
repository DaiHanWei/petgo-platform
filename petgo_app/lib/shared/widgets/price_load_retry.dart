import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';

/// 价格拉取失败的行内重试件（bug 20260729-417 起）：「加载失败 · 重试」，点击重拉。
/// 后台可配价一律后端实时下发、**无本地兜底**——取不到价格时本件替代价格文本出现，
/// 且调用方须同时禁用发起/支付按钮。问诊三页与身份证 HD 付费抽屉共用。
class PriceLoadRetry extends StatelessWidget {
  const PriceLoadRetry({super.key, required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return InkWell(
      onTap: onRetry,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(l10n.vetPriceLoadFailed,
                style: const TextStyle(fontSize: 12, color: AppColors.danger)),
            const SizedBox(width: 6),
            const Icon(Icons.refresh, size: 15, color: AppColors.mint),
            const SizedBox(width: 2),
            Text(l10n.vetPriceRetry,
                style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.mint)),
          ],
        ),
      ),
    );
  }
}
