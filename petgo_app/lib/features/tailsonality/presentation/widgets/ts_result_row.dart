import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../domain/content/ts_roles.dart';
import '../../domain/tailsonality_result.dart';

/// 结果列表的一行（V1.3.2 Story 2.6 · AC2 · UX-DR11）：代号（字母展开）+ 角色名 + 测试日期 + 解锁状态。
///
/// 整行可点（未解锁也可点进结果页——置灰的是状态标签，不是整行）。[trailing] 留给 Story 3.3 的佩戴切换。
class TsResultRow extends StatefulWidget {
  const TsResultRow({super.key, required this.result, this.onTap, this.trailing});

  final TailsonalityResult result;
  final VoidCallback? onTap;
  final Widget? trailing;

  @override
  State<TsResultRow> createState() => _TsResultRowState();
}

class _TsResultRowState extends State<TsResultRow> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = widget.result;
    final row = Container(
      constraints: const BoxConstraints(minHeight: 64),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  r.typeCode.split('').join(' '),
                  key: const ValueKey('tsRowCode'),
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                    color: AppColors.ink,
                    fontFeatures: [FontFeature.tabularFigures()],
                  ),
                ),
                const SizedBox(height: 2),
                Text(kTsRoles[r.letters]?.name ?? '',
                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.mint)),
                const SizedBox(height: 4),
                Row(
                  children: [
                    Text(formatDayMonthYear(context, r.createdAt.toLocal()),
                        key: const ValueKey('tsRowDate'),
                        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                    if (!r.unlocked) ...[
                      const SizedBox(width: 8),
                      Text(l10n.tailsonalityResultLocked,
                          key: const ValueKey('tsRowLocked'),
                          style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ],
                  ],
                ),
              ],
            ),
          ),
          ?widget.trailing,
          if (widget.onTap != null && widget.trailing == null) const Icon(Icons.chevron_right, color: AppColors.muted),
        ],
      ),
    );
    if (widget.onTap == null) return row;
    return Listener(
      onPointerDown: (_) => setState(() => _pressed = true),
      onPointerUp: (_) => setState(() => _pressed = false),
      onPointerCancel: (_) => setState(() => _pressed = false),
      child: AnimatedScale(
        scale: _pressed ? 0.96 : 1,
        duration: const Duration(milliseconds: 90),
        child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: widget.onTap, child: row),
      ),
    );
  }
}
