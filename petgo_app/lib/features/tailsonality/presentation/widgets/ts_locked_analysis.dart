import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/content/ts_readings.dart';
import '../../domain/content/ts_roles.dart';
import '../../domain/content/ts_text.dart';
import 'ts_rich_text.dart';

/// 锁态区（V1.3.2 Story 2.4 · AC5 · PRD §3.2）：**标题明文可见、正文遮罩**，不是「空白 + 一个按钮」。
///
/// 三段顺序与解锁后一致：角色专属深读 → 四段维度 → 能量段（内容设计 §5.1）。
/// 正文取该段真实内容的前 2 行做模糊 + 底部渐隐，外包 [ExcludeSemantics] + [IgnorePointer]：
/// 读屏不念付费内容、不可选中复制。本 epic 不放购买按钮，[footer] 插槽留给 Story 3.2。
class TsLockedAnalysis extends StatelessWidget {
  const TsLockedAnalysis({
    super.key,
    required this.typeCode,
    required this.letters,
    required this.energy,
    required this.petName,
    this.footer,
  });

  final String typeCode;
  final String letters;
  final String energy;
  final String petName;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    String fill(TsText? t) => t == null ? '' : tsFillPet(t.of(locale), petName);
    final energyInfo = kTsEnergy[energy];
    final dims = letters.split('').map((c) => fill(kTsDimensionReadings[c])).join(' ');
    final sections = [
      (key: 'deepRead', title: l10n.tailsonalityLockedDeepRead(typeCode), body: fill(kTsRoles[letters]?.deepRead)),
      (key: 'dimensions', title: l10n.tailsonalityLockedDimensions, body: dims),
      (
        key: 'energy',
        title: l10n.tailsonalityLockedEnergy(energyInfo == null ? energy : energyInfo.label.of(locale)),
        body: fill(energyInfo?.line),
      ),
    ];
    return Container(
      key: const ValueKey('tsLockedAnalysis'),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Icon(Icons.lock_outline, size: 18, color: AppColors.mint),
              const SizedBox(width: 6),
              Text(l10n.tailsonalityLockedTitle,
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.ink)),
            ],
          ),
          const SizedBox(height: 12),
          for (final s in sections) ...[
            Text(s.title,
                key: ValueKey('tsLockedTitle_${s.key}'),
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 6),
            _BlurredBody(key: ValueKey('tsLockedBody_${s.key}'), text: s.body),
            const SizedBox(height: 14),
          ],
          ?footer,
        ],
      ),
    );
  }
}

class _BlurredBody extends StatelessWidget {
  const _BlurredBody({super.key, required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: IgnorePointer(
        child: ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) => const LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFF000000), Color(0x33000000)],
          ).createShader(rect),
          child: ImageFiltered(
            imageFilter: ImageFilter.blur(sigmaX: 6, sigmaY: 6),
            child: TsRichText(
              text,
              maxLines: 2,
              style: const TextStyle(fontSize: 13.5, height: 1.5, color: AppColors.ink2),
            ),
          ),
        ),
      ),
    );
  }
}
