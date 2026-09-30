import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';

/// 「生成中」过渡（V1.3.2 Story 2.3 · AC5.1 · UX-DR5）：中心动效 + 五条维度进度条**逐条点亮**（各约 450ms）。
///
/// 动画只跑一遍（有限时长，不做无限循环）；系统「减少动态效果」开启时静态显示五条已点亮。
class TsGeneratingView extends StatefulWidget {
  const TsGeneratingView({super.key, required this.petName});

  final String petName;

  static const Duration stepDuration = Duration(milliseconds: 450);

  @override
  State<TsGeneratingView> createState() => _TsGeneratingViewState();
}

class _TsGeneratingViewState extends State<TsGeneratingView> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: TsGeneratingView.stepDuration * 5);
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    final reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (reduceMotion) {
      _c.value = 1;
    } else {
      _c.forward();
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final labels = [
      l10n.tailsonalityAxisEI,
      l10n.tailsonalityAxisNS,
      l10n.tailsonalityAxisTF,
      l10n.tailsonalityAxisJP,
      l10n.tailsonalityAxisEnergy,
    ];
    return Center(
      key: const ValueKey('tsGeneratingView'),
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
        child: AnimatedBuilder(
          animation: _c,
          builder: (context, _) {
            final lit = (_c.value * 5).floor().clamp(0, 5);
            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Transform.scale(
                  scale: 1 + 0.08 * (1 - (2 * ((_c.value * 5) % 1) - 1).abs()),
                  child: Container(
                    width: 84,
                    height: 84,
                    decoration: const BoxDecoration(color: AppColors.violet100, shape: BoxShape.circle),
                    child: const Icon(Icons.pets, size: 40, color: AppColors.mint),
                  ),
                ),
                const SizedBox(height: 22),
                Text(l10n.tailsonalityGeneratingTitle,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.ink)),
                const SizedBox(height: 6),
                Text(l10n.tailsonalityGeneratingSub(widget.petName),
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, color: AppColors.textSecondary)),
                const SizedBox(height: 24),
                for (var i = 0; i < labels.length; i++) ...[
                  _AxisBar(key: ValueKey('tsGeneratingAxis_$i'), label: labels[i], lit: i < lit),
                  const SizedBox(height: 10),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}

class _AxisBar extends StatelessWidget {
  const _AxisBar({super.key, required this.label, required this.lit});

  final String label;
  final bool lit;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: label,
      value: lit ? '100%' : '0%',
      child: Row(
        children: [
          Expanded(
            flex: 5,
            child: Text(label,
                style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: lit ? FontWeight.w700 : FontWeight.w500,
                    color: lit ? AppColors.ink : AppColors.muted)),
          ),
          const SizedBox(width: 10),
          Expanded(
            flex: 4,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(99),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 240),
                height: 6,
                color: lit ? AppColors.mint : AppColors.line,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
