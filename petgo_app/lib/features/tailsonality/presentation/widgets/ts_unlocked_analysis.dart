import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/content/ts_readings.dart';
import '../../domain/content/ts_roles.dart';
import '../../domain/content/ts_text.dart';
import 'ts_rich_text.dart';

/// 已解锁态付费区（V1.3.2 Story 3.2 · AC5 · A9）。顺序固定：角色专属深读 → 四段维度 → 能量段（内容设计 §5.1）。
///
/// 文案全取 2-2 的 Dart 内容表（随包固定，`content_version = 1`），按 4 字母 / 单字母 / 能量寻址。
/// 每段维度前标该轴两极字母，本极高亮。
class TsUnlockedAnalysis extends StatelessWidget {
  const TsUnlockedAnalysis({
    super.key,
    required this.typeCode,
    required this.letters,
    required this.energy,
    required this.petName,
  });

  final String typeCode;
  final String letters;
  final String energy;
  final String petName;

  /// 四轴两极，顺序与 `letters` 各位一致（社交 E/I · 探索 N/S · 情绪 T/F · 驱动 J/P）。
  static const List<(String, String)> axes = [('E', 'I'), ('N', 'S'), ('T', 'F'), ('J', 'P')];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    String fill(TsText? t) => t == null ? '' : tsFillPet(t.of(locale), petName);
    const bodyStyle = TextStyle(fontSize: 14, height: 1.55, color: AppColors.ink);
    const titleStyle = TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: AppColors.ink);
    final chars = letters.split('');
    return Container(
      key: const ValueKey('tsUnlockedAnalysis'),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Column(
            key: const ValueKey('tsUnlocked_deepRead'),
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.tailsonalityLockedDeepRead(typeCode), style: titleStyle),
              const SizedBox(height: 8),
              TsRichText(fill(kTsRoles[letters]?.deepRead), style: bodyStyle),
            ],
          ),
          const SizedBox(height: 20),
          Column(
            key: const ValueKey('tsUnlocked_dimensions'),
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.tailsonalityLockedDimensions, style: titleStyle),
              for (var i = 0; i < chars.length && i < axes.length; i++) ...[
                const SizedBox(height: 12),
                _Poles(axis: axes[i], active: chars[i]),
                const SizedBox(height: 4),
                TsRichText(fill(kTsDimensionReadings[chars[i]]),
                    key: ValueKey('tsUnlockedDim_${chars[i]}'), style: bodyStyle),
              ],
            ],
          ),
          const SizedBox(height: 20),
          Column(
            key: const ValueKey('tsUnlocked_energy'),
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Text(l10n.tailsonalityEnergyTitle, style: titleStyle),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: AppColors.mintTint,
                      borderRadius: BorderRadius.circular(9999),
                    ),
                    child: Text(energy == 'H' ? l10n.tailsonalityEnergyHigh : l10n.tailsonalityEnergyLow,
                        key: const ValueKey('tsUnlockedEnergyChip'),
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.mint)),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              TsRichText(fill(kTsEnergy[energy]?.line), style: bodyStyle),
            ],
          ),
        ],
      ),
    );
  }
}

class _Poles extends StatelessWidget {
  const _Poles({required this.axis, required this.active});

  final (String, String) axis;
  final String active;

  @override
  Widget build(BuildContext context) {
    Widget pole(String c) {
      final on = c == active;
      return Text(c,
          key: ValueKey(on ? 'tsPoleActive_$c' : 'tsPole_$c'),
          style: TextStyle(
            fontSize: 13,
            fontWeight: on ? FontWeight.w800 : FontWeight.w500,
            color: on ? AppColors.mint : AppColors.muted,
          ));
    }

    return Row(children: [
      pole(axis.$1),
      const Text(' / ', style: TextStyle(fontSize: 13, color: AppColors.muted)),
      pole(axis.$2),
    ]);
  }
}
