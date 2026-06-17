import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/vet_online_status.dart';

/// 兽医端共享深色顶栏（原型 V- 系列 `#2B2540`）。供工作台首页/待接单/案例/对话/我的复用。
///
/// 两种模式：
/// - dashboard：传 [greetingName] → 渲染时段问候 + 医生名 + 在线开关。
/// - 普通：传 [title] → 渲染标题（可选在线开关）。
class VetTopBar extends ConsumerWidget {
  const VetTopBar({super.key, this.greetingName, this.title, this.showOnlineToggle = false});

  /// dashboard 模式医生名（非空即问候模式，优先于 [title]）。
  final String? greetingName;

  /// 普通模式标题。
  final String? title;

  /// 是否显示在线开关（接 [vetOnlineStatusProvider]）。
  final bool showOnlineToggle;

  String _greeting(AppLocalizations l10n) {
    final h = DateTime.now().hour;
    if (h < 11) return l10n.greetingMorning;
    if (h < 15) return l10n.greetingAfternoon;
    if (h < 19) return l10n.greetingEvening;
    return l10n.greetingNight;
  }

  Future<void> _toggle(BuildContext context, WidgetRef ref, bool next) async {
    final l10n = AppLocalizations.of(context);
    try {
      await ref.read(vetOnlineStatusProvider.notifier).toggle(next);
    } catch (_) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(l10n.vetStatusUpdateFailed)));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final online = ref.watch(vetOnlineStatusProvider);
    final isGreeting = greetingName != null;

    return Container(
      width: double.infinity,
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.xl,
            AppSpacing.md,
            AppSpacing.xl,
            AppSpacing.lg,
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (isGreeting) ...[
                      Text(
                        _greeting(l10n),
                        style: AppTypography.caption.copyWith(color: AppColors.vetOnAccent.withValues(alpha: 0.7)),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        greetingName!,
                        key: const ValueKey('vetTopBarName'),
                        style: AppTypography.headline.copyWith(color: AppColors.vetOnAccent),
                      ),
                    ] else
                      Text(
                        title ?? '',
                        style: AppTypography.title.copyWith(color: AppColors.vetOnAccent),
                      ),
                  ],
                ),
              ),
              if (showOnlineToggle)
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      online ? l10n.vetOnlineLabel : l10n.vetOfflineLabel,
                      style: AppTypography.caption.copyWith(
                        color: online ? AppColors.vetPrimary : AppColors.vetOnAccent.withValues(alpha: 0.7),
                      ),
                    ),
                    Switch(
                      key: const ValueKey('vetTopBarOnlineSwitch'),
                      value: online,
                      activeThumbColor: AppColors.vetPrimary,
                      onChanged: (v) => _toggle(context, ref, v),
                    ),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}
