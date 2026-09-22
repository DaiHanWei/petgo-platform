import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 一次性引导蒙层（V1.3.0 批次 A · Story 5.4 · AD-A21.5）。项目内此前**没有** coachmark 组件。
///
/// ## 🔴 聚光挖孔不用「超大 box-shadow 叠在半透明遮罩上」（AC5）
/// UI 稿第四步实测过那个做法：3000px 的阴影叠在另一层半透明遮罩上会**双重叠加** ——
/// 遮罩比设计意图更暗，而聚光区**并没有变亮**（它只是"没有再暗一层"）。
///
/// 这里用**分块遮罩**：把高亮框之外的区域拆成上 / 下 / 左 / 右四块各自绘制，
/// 高亮框那一格**什么都不画**。于是：
/// - 聚光区是**原样的页面**，周围才被压暗 —— 对比是真实的，不是靠叠色凑的；
/// - 任何一块遮罩都只画一次，不存在叠加区。
///
/// ## ⚠️ 同一处踩到的第二个坑
/// UI 稿里 `display:flex` 容器内的文字与按钮被同容器内 `position:absolute` 的兄弟挡住。
/// 这里的对应做法是：说明卡与四块遮罩**同为 Stack 的兄弟**，且说明卡排在**最后**
/// （Stack 后来者在上）—— 不把它塞进任何一块遮罩里。
class CoachmarkOverlay extends StatefulWidget {
  const CoachmarkOverlay({
    super.key,
    required this.spotlight,
    this.anchorKey,
    this.padding = defaultPadding,
    this.title,
    required this.text,
    required this.confirmLabel,
    required this.onDismiss,
  });

  /// 要高亮的那块区域（全局坐标）。给了 [anchorKey] 时它只是首帧的兜底值。
  final Rect spotlight;

  /// 高亮目标的锚点（bug 501）。给了就**每帧跟着它重新量**：目标在蒙层弹出后
  /// 还可能改变尺寸（例：档案页入口卡等统计数据回来才变矮），只量一次会留下一个
  /// 比卡片大的亮块。量不到（已卸载 / 未布局）时沿用上一次的矩形。
  final GlobalKey? anchorKey;

  /// 高亮框相对目标向外扩的距离。默认 [defaultPadding]；要亮块与目标严丝合缝时传 0。
  final double padding;

  static const double defaultPadding = 6;

  /// 标题行（UI 稿 P7：白色粗体一句话点明「什么东西搬家了」）。null = 只有正文。
  final String? title;

  final String text;
  final String confirmLabel;

  /// 「知道了」与点遮罩都走它。**点哪儿都能关** —— 一次性告知不该把人困住。
  final VoidCallback onDismiss;

  static const double _scrimAlpha = 0.72;

  /// 说明卡挂在高亮区的下方；下方放不下时挂到上方。
  static const double _cardGap = 12;
  static const double _cardEstimatedHeight = 150;

  /// 说明区距屏幕底部的比例（高亮框在上半屏时）。
  static const double _cardBottomFraction = 0.14;

  @override
  State<CoachmarkOverlay> createState() => _CoachmarkOverlayState();
}

class _CoachmarkOverlayState extends State<CoachmarkOverlay> {
  late Rect _spotlight = widget.spotlight;

  static const double _scrimAlpha = CoachmarkOverlay._scrimAlpha;
  static const double _cardGap = CoachmarkOverlay._cardGap;
  static const double _cardEstimatedHeight = CoachmarkOverlay._cardEstimatedHeight;
  static const double _cardBottomFraction = CoachmarkOverlay._cardBottomFraction;

  @override
  void initState() {
    super.initState();
    if (widget.anchorKey != null) _scheduleMeasure();
  }

  /// 每帧之后量一次锚点；变了才 setState。postFrameCallback 本身不催帧 ——
  /// 页面不动就不会有下一帧，所以这里不是忙循环。
  void _scheduleMeasure() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final ro = widget.anchorKey?.currentContext?.findRenderObject();
      if (ro is RenderBox && ro.attached && ro.hasSize) {
        final Rect r = ro.localToGlobal(Offset.zero) & ro.size;
        if (r != _spotlight) setState(() => _spotlight = r);
      }
      _scheduleMeasure();
    });
  }

  @override
  void didUpdateWidget(CoachmarkOverlay old) {
    super.didUpdateWidget(old);
    if (widget.anchorKey == null && widget.spotlight != old.spotlight) {
      _spotlight = widget.spotlight;
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final spotlight = _spotlight;
    final double pad = widget.padding;
    final hole = Rect.fromLTRB(
      (spotlight.left - pad).clamp(0.0, size.width),
      (spotlight.top - pad).clamp(0.0, size.height),
      (spotlight.right + pad).clamp(0.0, size.width),
      (spotlight.bottom + pad).clamp(0.0, size.height),
    );
    final bool below = hole.bottom + _cardGap + _cardEstimatedHeight <= size.height;

    return Material(
      type: MaterialType.transparency,
      child: Stack(
        children: [
          // —— 四块遮罩：挖孔那一格什么都不画，所以聚光区是**原样的页面** ——
          _scrim(left: 0, top: 0, width: size.width, height: hole.top),
          _scrim(
              left: 0,
              top: hole.bottom,
              width: size.width,
              height: (size.height - hole.bottom).clamp(0.0, size.height)),
          _scrim(left: 0, top: hole.top, width: hole.left, height: hole.height),
          _scrim(
              left: hole.right,
              top: hole.top,
              width: (size.width - hole.right).clamp(0.0, size.width),
              height: hole.height),
          // 高亮描边（只有边框，不填充 —— 填充就又把聚光区压暗了）。
          Positioned.fromRect(
            rect: hole,
            child: IgnorePointer(
              child: Container(
                key: const ValueKey('coachmarkSpotlight'),
                decoration: BoxDecoration(
                  border: Border.all(color: AppColors.mint, width: 2.5),
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
            ),
          ),
          // 说明卡：Stack 的**最后一个兄弟**，不塞进任何一块遮罩里。
          // UI 稿 P7：说明文字落在**屏幕下部的空白处**，不紧贴高亮框 ——
          // 紧贴时白字压在被压暗的页面内容上（实测和「Pencapaian」撞在一起）。
          // 高亮框在上半屏时贴底放；在下半屏时仍挂到框上方。
          Positioned(
            left: 24,
            right: 24,
            bottom: below
                ? size.height * _cardBottomFraction
                : (size.height - hole.top) + _cardGap,
            child: _card(context),
          ),
        ],
      ),
    );
  }

  /// 一块遮罩。点它也关 —— 一次性告知不该把人困住。
  Widget _scrim({
    required double left,
    required double top,
    required double width,
    required double height,
  }) =>
      Positioned(
        left: left,
        top: top,
        width: width < 0 ? 0 : width,
        height: height < 0 ? 0 : height,
        child: GestureDetector(
          // 四块共用一个 ValueKey：它们是同一件东西的四个片段，
          // 测试要的是「一共四块、互不重叠、都不盖住聚光区」。
          key: const ValueKey('coachmarkScrim'),
          behavior: HitTestBehavior.opaque,
          onTap: widget.onDismiss,
          // UI 稿 P7：深紫黑（品牌 splashInk），不是纯黑 —— 纯黑压在淡紫页面上发脏。
          child: ColoredBox(color: AppColors.splashInk.withValues(alpha: _scrimAlpha)),
        ),
      );

  /// 说明区（UI 稿 P7）：**文字直接压在遮罩上**、居中 —— 标题白色粗体、正文浅紫、
  /// 下接白底胶囊「知道了」。改前是一张白卡片 + 右对齐紫按钮，像个普通弹窗，
  /// 和「这里有个东西换了位置」的轻提示语气不符（2026-09-21 对稿修正）。
  Widget _card(BuildContext context) => Column(
        key: const ValueKey('coachmarkCard'),
        mainAxisSize: MainAxisSize.min,
        children: [
          if (widget.title != null) ...[
            Text(
              widget.title!,
              key: const ValueKey('coachmarkTitle'),
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w700, color: Colors.white),
            ),
            const SizedBox(height: 8),
          ],
          Text(
            widget.text,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 12, height: 1.6, color: AppColors.lineViolet),
          ),
          const SizedBox(height: 20),
          FilledButton(
            key: const ValueKey('coachmarkGotIt'),
            onPressed: widget.onDismiss,
            style: FilledButton.styleFrom(
              backgroundColor: Colors.white,
              foregroundColor: AppColors.ink,
              shape: const StadiumBorder(),
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 11),
              textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
            ),
            child: Text(widget.confirmLabel),
          ),
        ],
      );
}
