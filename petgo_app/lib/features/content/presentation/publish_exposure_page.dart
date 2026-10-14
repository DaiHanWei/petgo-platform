import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/image_exposure.dart';

/// 露出区域页（V1.3.4 Story 2.3 · FR-125 / AD-8 · UI 稿 A1–A4），取代旧三档裁剪页。
///
/// ## 整图 + 拖白框（UI 决策 2026-10-09 选 A1）
/// 整张图按 contain 显示，白框 = 首页展示窗口，用户**拖的是框**（不是图）——
/// 一眼看到整张图里哪段会露出，与「原图完整保留」的心智一致。
///
/// ## 窗口比例不给选
/// 由越界方向唯一决定：过宽 1.78 / 过竖 0.75（[exposureWindowAspect]），页面不接受任何「档位」入参。
///
/// ## offset 与展示端同一把尺子
/// 白框左（上）边 = `range * offset`；2.2 `feedImageAlignment` 的 `BoxFit.cover + Alignment(2o − 1)`
/// 在展示端截出的正是这一段。两端任何一端改公式，另一端必须同改。
///
/// ## 手势分工
/// 白框内起手的拖动归白框（命中更深的识别器先越过 slop 先胜出）；白框外（压暗区）起手的横向滑动归
/// [PageView] 切图；竖图白框只吃纵向，横向滑动一律切图。无需自定义识别器。
///
/// ## 无状态外泄
/// 不读写任何 provider：进 = 构造参数 [images]，出 = `pop` 结果——
/// 「Lanjut」→ [ExposureResult]（与 [images] 同序等长），✕ / 系统返回 → `null`（调用方丢弃本次选图）。
class PublishExposurePage extends StatefulWidget {
  const PublishExposurePage({super.key, required this.images});

  /// 只会是超界图（`needsCrop == true`）；区间内的图不会进来。**不得为空**（`initState` 断言）。
  final List<ExposureImage> images;

  @override
  State<PublishExposurePage> createState() => _PublishExposurePageState();
}

class _PublishExposurePageState extends State<PublishExposurePage> {
  late final List<double> _offsets =
      List.filled(widget.images.length, kExposureDefaultOffset);
  late final List<bool> _adjusted = List.filled(widget.images.length, false);
  int _current = 0;
  final PageController _pager = PageController();

  bool get _multi => widget.images.length > 1;

  @override
  void initState() {
    super.initState();
    // const 构造函数的初始化列表里不能调 `isNotEmpty`（非常量表达式），断言放这里。
    assert(widget.images.isNotEmpty, 'PublishExposurePage 至少要一张图');
  }

  @override
  void dispose() {
    _pager.dispose();
    super.dispose();
  }

  void _drag(int i, double delta, double range) {
    if (range <= 0) return;
    final next = (_offsets[i] + delta / range).clamp(0.0, 1.0);
    if (next == _offsets[i]) return;
    setState(() {
      _offsets[i] = next;
      _adjusted[i] = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Column(
          children: [
            _topBar(l10n),
            Expanded(
              child: PageView.builder(
                key: const ValueKey('exposurePager'),
                controller: _pager,
                itemCount: widget.images.length,
                onPageChanged: (i) => setState(() => _current = i),
                // UI 稿 A1–A4：图左右各留 16，提示句紧贴图正下方（间距 16），两者作为一组在取景区垂直居中。
                itemBuilder: (context, i) => Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Flexible(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                          child: LayoutBuilder(
                            // 稿 A2：竖图不顶满取景区（约六成高，上下留黑边），封顶 80% 免得贴到标题栏
                            builder: (context, c) => _canvas(i, c.maxWidth, c.maxHeight * 0.8, l10n),
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(24, 16, 24, 16),
                        child: Text(
                          l10n.publishExposureHint,
                          key: const ValueKey('exposureHint'),
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: Color(0xB8FFFFFF), fontSize: 12.5),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            if (_multi) _strip(),
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  AppSpacing.screenEdge, 0, AppSpacing.screenEdge, 14),
              child: SizedBox(
                width: double.infinity,
                height: 46,
                child: FilledButton(
                  key: const ValueKey('exposureNext'),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                  onPressed: () => Navigator.of(context).pop(ExposureResult(
                    offsets: List.unmodifiable(_offsets),
                    adjusted: List.unmodifiable(_adjusted),
                  )),
                  child: Text(
                    l10n.onboardNext,
                    style: const TextStyle(
                        color: Colors.white, fontSize: 14.5, fontWeight: FontWeight.w700),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 顶栏：左 ✕（退出）· 标题 · 右 48 宽槽位（多图页码 / 单图留空）。
  /// 右上角是前进位，本页前进动作在底部，故不放按钮。
  Widget _topBar(AppLocalizations l10n) {
    return SizedBox(
      height: 52,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: Row(
          spacing: 6,
          children: [
            IconButton(
              key: const ValueKey('exposureClose'),
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints.tightFor(width: 44, height: 44),
              icon: const Icon(Icons.close, color: Colors.white),
              onPressed: () {
                // 埋点 E-10：退出（口径同旧裁剪页）。系统返回键不报 —— 事件名是 `_tapped`。
                Analytics.capture('publish_image_crop_exit_tapped', {
                  'original_ratio': double.parse(
                      widget.images[_current].size.ratio.toStringAsFixed(3)),
                });
                Navigator.of(context).pop();
              },
            ),
            Expanded(
              child: Text(
                l10n.publishCropTitle,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700),
              ),
            ),
            SizedBox(
              width: 48,
              child: _multi
                  ? Text(
                      '${_current + 1} / ${widget.images.length}',
                      key: const ValueKey('exposurePageIndicator'),
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        color: Color(0xB3FFFFFF),
                        fontSize: 12,
                        fontFeatures: [FontFeature.tabularFigures()],
                      ),
                    )
                  : null,
            ),
          ],
        ),
      ),
    );
  }

  /// 一页取景：整图 contain + 框外压暗 + 可拖白框。
  Widget _canvas(int i, double maxW, double maxH, AppLocalizations l10n) {
    final image = widget.images[i];
    final ratio = image.size.ratio;
    final wide = exposureIsWide(image.size);
    final aspect = exposureWindowAspect(image.size);

    final dispW = math.min(maxW, maxH * ratio);
    final dispH = dispW / ratio;

    final double frameW;
    final double frameH;
    if (wide) {
      frameH = dispH;
      frameW = math.min(dispH * aspect, dispW);
    } else {
      frameW = dispW;
      frameH = math.min(dispW / aspect, dispH);
    }
    final range = wide ? dispW - frameW : dispH - frameH;
    final left = wide ? range * _offsets[i] : 0.0;
    final top = wide ? 0.0 : range * _offsets[i];

    const dim = Color(0x8C000000);
    return SizedBox(
      width: dispW,
      height: dispH,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(child: Image.memory(image.bytes, fit: BoxFit.fill)),
          // 框外压暗：四块色块，只盖在图片显示范围内。
          Positioned(left: 0, top: 0, width: dispW, height: top, child: const ColoredBox(color: dim)),
          Positioned(
              left: 0,
              top: top + frameH,
              width: dispW,
              height: dispH - top - frameH,
              child: const ColoredBox(color: dim)),
          Positioned(left: 0, top: top, width: left, height: frameH, child: const ColoredBox(color: dim)),
          Positioned(
              left: left + frameW,
              top: top,
              width: dispW - left - frameW,
              height: frameH,
              child: const ColoredBox(color: dim)),
          Positioned(
            left: left,
            top: top,
            width: frameW,
            height: frameH,
            child: GestureDetector(
              key: ValueKey('exposureFrame_$i'),
              behavior: HitTestBehavior.opaque,
              onHorizontalDragUpdate:
                  wide && range > 0 ? (d) => _drag(i, d.delta.dx, range) : null,
              onVerticalDragUpdate:
                  !wide && range > 0 ? (d) => _drag(i, d.delta.dy, range) : null,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                          border: Border.all(color: Colors.white, width: 2)),
                    ),
                  ),
                  const Positioned(left: -3, top: -3, child: _Corner(quarterTurns: 0)),
                  const Positioned(right: -3, top: -3, child: _Corner(quarterTurns: 1)),
                  const Positioned(right: -3, bottom: -3, child: _Corner(quarterTurns: 2)),
                  const Positioned(left: -3, bottom: -3, child: _Corner(quarterTurns: 3)),
                  Center(child: _grip(wide, l10n)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 框中央拖动胶囊：宽图左右箭头、竖图上下箭头。不做 backdrop blur。
  Widget _grip(bool wide, AppLocalizations l10n) {
    return Container(
      key: const ValueKey('exposureGrip'),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: const Color(0x8C14101E),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        spacing: 4,
        children: [
          Icon(wide ? Icons.chevron_left : Icons.keyboard_arrow_up,
              size: 14, color: Colors.white),
          Text(
            l10n.publishExposureDrag,
            style: const TextStyle(
                color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600),
          ),
          Icon(wide ? Icons.chevron_right : Icons.keyboard_arrow_down,
              size: 14, color: Colors.white),
        ],
      ),
    );
  }

  /// 缩略图条（仅多图）：点击切到对应张；当前张不透明 + 品牌紫描边（与图之间留 2px）。
  Widget _strip() {
    // ⚠️ 最多 9 张（9×48 + 8×8 = 496px）：窄屏 7 张起就放不下，所以条可横向滚动；
    // minWidth = 可用宽度保住「张数少时居中」。四周 4px 内边距给当前张描边圈（外扩 4px）留位，免被滚动视口裁掉。
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: ConstrainedBox(
            constraints: BoxConstraints(minWidth: constraints.maxWidth),
            child: Padding(
              padding: const EdgeInsets.all(4),
              child: Row(
                key: const ValueKey('exposureStrip'),
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                spacing: 8,
                children: [
                  for (var i = 0; i < widget.images.length; i++)
                    GestureDetector(
                      key: ValueKey('exposureThumb_$i'),
                      onTap: () => _pager.animateToPage(i,
                          duration: const Duration(milliseconds: 200), curve: Curves.easeOut),
                      child: Opacity(
                        opacity: i == _current ? 1 : 0.5,
                        child: SizedBox(
                          width: 48,
                          height: 48,
                          child: Stack(
                            clipBehavior: Clip.none,
                            children: [
                              Positioned.fill(
                                child: ClipRRect(
                                  borderRadius: BorderRadius.circular(10),
                                  child: Image.memory(widget.images[i].bytes,
                                      fit: BoxFit.cover, cacheWidth: 144),
                                ),
                              ),
                              if (i == _current)
                                Positioned(
                                  left: -4,
                                  top: -4,
                                  right: -4,
                                  bottom: -4,
                                  child: IgnorePointer(
                                    child: DecoratedBox(
                                      key: ValueKey('exposureThumbRing_$i'),
                                      decoration: BoxDecoration(
                                        border: Border.all(color: AppColors.mint, width: 2),
                                        borderRadius: BorderRadius.circular(14),
                                      ),
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 白框四角的 L 形角标：16×16、线宽 4、外圆角 4（UI 稿 `.cp-win .cn`）。
/// 按左上画一次，其余三角用 [RotatedBox] 顺时针转。
class _Corner extends StatelessWidget {
  const _Corner({required this.quarterTurns});

  final int quarterTurns;

  @override
  Widget build(BuildContext context) {
    return RotatedBox(
      quarterTurns: quarterTurns,
      child: const CustomPaint(size: Size(16, 16), painter: _CornerPainter()),
    );
  }
}

class _CornerPainter extends CustomPainter {
  const _CornerPainter();

  static const double _stroke = 4;
  static const double _outerRadius = 4;

  @override
  void paint(Canvas canvas, Size size) {
    const c = _stroke / 2; // 描边中线距外缘
    const r = _outerRadius - c; // 中线圆角半径
    final path = Path()
      ..moveTo(c, size.height)
      ..lineTo(c, c + r)
      ..arcToPoint(const Offset(c + r, c), radius: const Radius.circular(r))
      ..lineTo(size.width, c);
    canvas.drawPath(
      path,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = _stroke,
    );
  }

  @override
  bool shouldRepaint(covariant _CornerPainter oldDelegate) => false;
}
