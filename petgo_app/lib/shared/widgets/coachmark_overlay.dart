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
class CoachmarkOverlay extends StatelessWidget {
  const CoachmarkOverlay({
    super.key,
    required this.spotlight,
    required this.text,
    required this.confirmLabel,
    required this.onDismiss,
  });

  /// 要高亮的那块区域（全局坐标）。
  final Rect spotlight;

  final String text;
  final String confirmLabel;

  /// 「知道了」与点遮罩都走它。**点哪儿都能关** —— 一次性告知不该把人困住。
  final VoidCallback onDismiss;

  /// 高亮框四周多留一点，免得描边贴着内容。
  static const double _pad = 6;
  static const double _scrimAlpha = 0.62;

  /// 说明卡挂在高亮区的下方；下方放不下时挂到上方。
  static const double _cardGap = 12;
  static const double _cardEstimatedHeight = 150;

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final hole = Rect.fromLTRB(
      (spotlight.left - _pad).clamp(0.0, size.width),
      (spotlight.top - _pad).clamp(0.0, size.height),
      (spotlight.right + _pad).clamp(0.0, size.width),
      (spotlight.bottom + _pad).clamp(0.0, size.height),
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
                  border: Border.all(color: AppColors.mint, width: 2),
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
            ),
          ),
          // 说明卡：Stack 的**最后一个兄弟**，不塞进任何一块遮罩里。
          Positioned(
            left: 16,
            right: 16,
            top: below ? hole.bottom + _cardGap : null,
            bottom: below ? null : (size.height - hole.top) + _cardGap,
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
          onTap: onDismiss,
          child: ColoredBox(color: Colors.black.withValues(alpha: _scrimAlpha)),
        ),
      );

  Widget _card(BuildContext context) => Container(
        key: const ValueKey('coachmarkCard'),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                text,
                style: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.ink),
              ),
            ),
            const SizedBox(height: 10),
            FilledButton(
              key: const ValueKey('coachmarkGotIt'),
              onPressed: onDismiss,
              child: Text(confirmLabel),
            ),
          ],
        ),
      );
}
