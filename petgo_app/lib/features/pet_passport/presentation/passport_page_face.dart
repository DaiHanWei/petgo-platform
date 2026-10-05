import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../place/presentation/widgets/place_stamp_view.dart';
import '../domain/pet_passport.dart';
import 'passport_layout.dart';

/// 护照内页的「一页」（V1.3.2 Story 1.3：由 1.2 的 B2 单章页抽出，B2 与 B5「所在内页局部」共用一个版心）。
///
/// 2026-10-06 按设计稿 `stamp-place` Artboard 2 重做：在护照本内容区（828×1110 画布）里自上而下
/// 「首次到访日期（浅棕）/ {n} Kunjungan（粗）/ 大章 390 + 次数框 ×N（N≥2）/ 定位图标 / 场所名（两行）」。
/// - 常规（B2）：[onTapStamp] 非空时章本体可点（按压 scale 0.96）。
/// - [compact]（B5）：同一张脸等比缩小成缩略页 +「Halaman {i}」，不可点。
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
    final r = kPassportBookContentRect;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          key: const ValueKey('passportPageFaceCompact'),
          width: 120,
          child: AspectRatio(
            aspectRatio: r.width / r.height,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: PassportInk.paper,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: PassportInk.cellBorder),
              ),
              // 同一张脸等比缩小（不是另一套资产）。
              child: _face(context),
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(l10n.passportPageLabel(pageIndex + 1),
            style: const TextStyle(
                fontSize: 12, color: PassportInk.footer, fontFeatures: [FontFeature.tabularFigures()])),
      ],
    );
  }

  Widget _face(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = kPassportBookContentRect;
    final date = stamp.firstVisitDate == null ? null : formatDayMonthYear(context, stamp.firstVisitDate!);
    return FittedBox(
      fit: BoxFit.contain,
      child: SizedBox(
        width: r.width,
        height: r.height,
        child: Stack(
          children: [
            if (date != null)
              Positioned(
                left: 0,
                right: 0,
                top: 172,
                child: Text(date,
                    key: const ValueKey('passportStampDate'),
                    textAlign: TextAlign.center,
                    style: passportRubik(37, FontWeight.w400, PassportInk.light)),
              ),
            Positioned(
              left: 0,
              right: 0,
              top: 225,
              child: Text(l10n.passportVisitCount(stamp.visitCount),
                  key: const ValueKey('passportStampVisits'),
                  textAlign: TextAlign.center,
                  style: passportRubik(40, FontWeight.w700, PassportInk.dark)),
            ),
            Positioned(
              left: 219,
              top: 311,
              width: 390,
              height: 390,
              child: _PressableStamp(
                onTap: compact ? null : onTapStamp,
                child: PlaceStampView(placeType: stamp.placeType, imageUrl: stamp.stampImageUrl, size: 390),
              ),
            ),
            if (stamp.visitCount >= 2)
              Positioned(left: 516, top: 597, child: PassportVisitBadge(count: stamp.visitCount, size: 136)),
            Positioned(
              left: 381,
              top: 769,
              width: 66,
              height: 75,
              child: Image.asset('assets/passport_book/location.png', fit: BoxFit.contain),
            ),
            Positioned(
              left: 94,
              width: 640,
              top: 864,
              child: Text(stamp.placeName,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: passportRubik(40, FontWeight.w500, PassportInk.dark, height: 1.25)),
            ),
          ],
        ),
      ),
    );
  }
}

/// 纵览（B2b）的章 cell：章面 + 场所名（V1.3.2 Story 4.3 从纵览网格抽出，护照分享卡的章格复用它）。
///
/// 章面走 [PlaceStampView]：专属章 → 包内默认章 → 占位；**原色、不着色、不圆形裁切**（AD-5 / D-10）。
/// 场所名按设计稿用 Rubik Medium 深棕、最多两行。[labelFontSize] 缺省 11（纵览页原值）；分享卡按画布坐标传更大的字号。
class PassportStampCell extends StatelessWidget {
  const PassportStampCell({super.key, required this.stamp, required this.stampSize, this.labelFontSize = 11});

  final PassportStamp stamp;
  final double stampSize;
  final double labelFontSize;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        PlaceStampView(placeType: stamp.placeType, imageUrl: stamp.stampImageUrl, size: stampSize),
        SizedBox(height: labelFontSize * 4 / 11),
        Text(stamp.placeName,
            maxLines: 2,
            textAlign: TextAlign.center,
            overflow: TextOverflow.ellipsis,
            style: passportRubik(labelFontSize, FontWeight.w500, PassportInk.dark, height: 1.16)),
      ],
    );
  }
}

/// 重复到访角标「xN」（N≥2 才由调用方挂出；纯数字 + 符号，不进 ARB）。
/// 设计稿：紫色圆 + 白虚线边（`count_frame.png`）+ 白色粗体字；[size] 为圆的边长（画布坐标）。
class PassportVisitBadge extends StatelessWidget {
  const PassportVisitBadge({super.key, required this.count, this.size = 50});

  final int count;
  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: const ValueKey('passportVisitBadge'),
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          Image.asset('assets/passport_book/count_frame.png', width: size, height: size),
          Padding(
            padding: EdgeInsets.all(size * 0.14),
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Text('x$count',
                  style: passportRubik(size * 0.42, FontWeight.w700, Colors.white)
                      .copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
            ),
          ),
        ],
      ),
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
