import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../place/presentation/widgets/place_stamp_view.dart';
import '../domain/pet_passport.dart';

/// 护照内页的「一页」（V1.3.2 Story 1.3：由 1.2 的 B2 单章页抽出，B2 与 B5「所在内页局部」共用一个版心）。
///
/// - 常规（B2）：章面 128 + 场所名 +「{首次日期} · {n} kunjungan」；×N 仅 N≥2；[onTapStamp] 非空时章本体可点
///   （热区 ≥44×44，按压 scale 0.96）。
/// - [compact]（B5）：同一张脸等比缩小成缩略页 +「Halaman {i}」，不可点。设计稿到位后只改这一处。
class PassportPageFace extends StatelessWidget {
  const PassportPageFace({
    super.key,
    required this.stamp,
    required this.pageIndex,
    this.compact = false,
    this.onTapStamp,
  });

  final PassportStamp stamp;

  /// 该章在 `stamps` 列表里的下标（页码 = pageIndex + 1）。
  final int pageIndex;
  final bool compact;
  final VoidCallback? onTapStamp;

  @override
  Widget build(BuildContext context) {
    if (!compact) return _face(context);
    final l10n = AppLocalizations.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          key: const ValueKey('passportPageFaceCompact'),
          width: 120,
          child: AspectRatio(
            aspectRatio: 3 / 4,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppColors.cream2,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.lineViolet),
              ),
              // 同一张脸等比缩小（不是另一套资产）。
              child: FittedBox(
                fit: BoxFit.contain,
                child: SizedBox(width: 300, height: 400, child: _face(context)),
              ),
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(l10n.passportPageLabel(pageIndex + 1),
            style: const TextStyle(
                fontSize: 12, color: AppColors.ink2, fontFeatures: [FontFeature.tabularFigures()])),
      ],
    );
  }

  Widget _face(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final date = stamp.firstVisitDate == null ? '' : formatDayMonthYear(context, stamp.firstVisitDate!);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _PressableStamp(
            onTap: compact ? null : onTapStamp,
            child: SizedBox(
              width: 150,
              height: 140,
              child: Stack(
                clipBehavior: Clip.none,
                alignment: Alignment.center,
                children: [
                  PlaceStampView(placeType: stamp.placeType, imageUrl: stamp.stampImageUrl, size: 128),
                  if (stamp.visitCount >= 2)
                    Positioned(right: 0, top: 0, child: PassportVisitBadge(count: stamp.visitCount)),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Text(stamp.placeName,
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 6),
          Text(l10n.passportStampMeta(date, stamp.visitCount),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        ],
      ),
    );
  }
}

/// 重复到访角标「×N」（N≥2 才由调用方挂出；纯数字 + 符号，不进 ARB）。
class PassportVisitBadge extends StatelessWidget {
  const PassportVisitBadge({super.key, required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('passportVisitBadge'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: AppColors.popRed, borderRadius: BorderRadius.circular(12)),
      child: Text('×$count',
          style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              fontFeatures: [FontFeature.tabularFigures()])),
    );
  }
}

/// 章本体的按压反馈（`AnimatedScale` 0.96，项目无公共 press 组件）。[onTap] 为 null → 不可点。
class _PressableStamp extends StatefulWidget {
  const _PressableStamp({required this.child, this.onTap});

  final Widget child;
  final VoidCallback? onTap;

  @override
  State<_PressableStamp> createState() => _PressableStampState();
}

class _PressableStampState extends State<_PressableStamp> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    if (widget.onTap == null) return widget.child;
    return GestureDetector(
      key: const ValueKey('passportStampTap'),
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) => setState(() => _pressed = true),
      onTapCancel: () => setState(() => _pressed = false),
      onTapUp: (_) => setState(() => _pressed = false),
      onTap: widget.onTap,
      child: AnimatedScale(
        scale: _pressed ? 0.96 : 1,
        duration: const Duration(milliseconds: 90),
        child: widget.child,
      ),
    );
  }
}
