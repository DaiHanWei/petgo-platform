import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/tailsonality_providers.dart';
import '../domain/content/ts_roles.dart';

/// Tailsonality 结果页（V1.3.2 Story 2.3 · AC6 最小骨架）：按 token 取结果、显示完整代号 + 角色名。
///
/// Story 2.4 在本文件上做完整页面（免费态、锁态区、重测等）。
class TailsonalityResultPage extends ConsumerWidget {
  const TailsonalityResultPage({super.key, required this.token});

  final String token;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(tailsonalityResultProvider(token));
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(backgroundColor: AppColors.cream, title: Text(l10n.tailsonalityTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: TextButton(
            key: const ValueKey('tsResultRetry'),
            onPressed: () => ref.invalidate(tailsonalityResultProvider(token)),
            child: Text(l10n.commonRetry),
          ),
        ),
        data: (r) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(r.typeCode,
                  key: const ValueKey('tsResultCode'),
                  style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w900, color: AppColors.ink)),
              const SizedBox(height: 6),
              Text(kTsRoles[r.letters]?.name ?? '',
                  key: const ValueKey('tsResultRoleName'),
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.mint)),
            ],
          ),
        ),
      ),
    );
  }
}
