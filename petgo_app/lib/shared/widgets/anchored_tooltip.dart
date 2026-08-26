/// 通用锚定提示层（V1.1.6 Story 5.1 · FR-74）。
///
/// 🛡 **本组件是共享件，Story 5.2 的内容装饰标签要复用它** —— 不得两条 story 各建一个。
/// 共享目录下此前**只有 toast、没有任何 tooltip / 浮层组件**，所以这里是从零建的。
///
/// ## 为什么走浮层而不是塞进页面布局
/// 标签就贴在昵称旁边。塞进布局里意味着一展开就把整行（乃至整列内容）顶动 ——
/// 在 Feed 这种长列表里尤其难受。浮层挂在 root Overlay 上，**永不影响布局**
/// （项目里的 toast 已是同款写法）。
///
/// ## 行为
/// - **贴住被点的那个图标**：优先出现在图标下方，下方放不下就翻到上方；左右夹在屏幕内。
/// - **点外部关闭**：整屏铺一层透明拦截层，点任何位置都关。
///   ⚠️ 开着的时候**任何一次点击都先被拦截层吃掉**（包括点另一个标签）——
///   行为是「先关，再点才开另一个」。若让拦截层透传，点击会同时落到下面的卡片上
///   （把用户直接带进详情页），那更糟。
/// - 单实例：不会叠出两层。
/// - 四处展示位行为一致（同一个函数）。
library;

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

OverlayEntry? _current;

/// 关掉当前提示层（没有则无操作）。
void dismissAnchoredTooltip() {
  final entry = _current;
  _current = null;
  if (entry != null && entry.mounted) entry.remove();
}

/// 在 [anchorContext] 对应的控件旁弹出提示层。
///
/// [title] 标签名、[message] 运营配置的那句说明。
void showAnchoredTooltip(BuildContext anchorContext,
    {required String title, required String message}) {
  final overlay = Overlay.maybeOf(anchorContext, rootOverlay: true);
  final box = anchorContext.findRenderObject();
  if (overlay == null || box is! RenderBox || !box.hasSize) return;

  dismissAnchoredTooltip();

  final overlayBox = overlay.context.findRenderObject();
  if (overlayBox is! RenderBox) return;
  final topLeft = box.localToGlobal(Offset.zero, ancestor: overlayBox);
  final anchor = topLeft & box.size;

  final entry = OverlayEntry(
    builder: (context) => _AnchoredTooltipLayer(
      anchor: anchor,
      title: title,
      message: message,
      onDismiss: dismissAnchoredTooltip,
    ),
  );
  _current = entry;
  overlay.insert(entry);
}

class _AnchoredTooltipLayer extends StatelessWidget {
  const _AnchoredTooltipLayer({
    required this.anchor,
    required this.title,
    required this.message,
    required this.onDismiss,
  });

  final Rect anchor;
  final String title;
  final String message;
  final VoidCallback onDismiss;

  /// 气泡与图标之间的间隙。
  static const double _gap = 6;

  /// 气泡最大宽度：太宽会横跨整屏、读起来反而费劲。
  static const double _maxWidth = 240;

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;
    return Stack(
      children: [
        // 点外部关闭：整屏透明拦截层。
        Positioned.fill(
          child: GestureDetector(
            key: const ValueKey('tooltipBarrier'),
            behavior: HitTestBehavior.opaque,
            onTap: onDismiss,
            child: const SizedBox.expand(),
          ),
        ),
        CustomSingleChildLayout(
          delegate: _AnchorDelegate(anchor: anchor, gap: _gap, screen: size),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: _maxWidth),
            child: Material(
              key: const ValueKey('tagTooltip'),
              color: Colors.transparent,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
                decoration: BoxDecoration(
                  color: AppColors.ink,
                  borderRadius: BorderRadius.circular(10),
                  boxShadow: const [
                    BoxShadow(color: Color(0x33000000), blurRadius: 12, offset: Offset(0, 4)),
                  ],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: const TextStyle(
                            color: Colors.white, fontSize: 12.5, fontWeight: FontWeight.w700)),
                    if (message.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text(message,
                          style: const TextStyle(
                              color: Colors.white70, fontSize: 11.5, height: 1.35)),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// 把气泡摆到锚点旁边：优先下方，放不下翻上方；左右夹在屏幕内。
class _AnchorDelegate extends SingleChildLayoutDelegate {
  const _AnchorDelegate({required this.anchor, required this.gap, required this.screen});

  final Rect anchor;
  final double gap;
  final Size screen;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) =>
      constraints.loosen();

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    // 竖向：下方优先；下面装不下就翻到上方。
    double dy = anchor.bottom + gap;
    if (dy + childSize.height > size.height - 8) {
      dy = anchor.top - gap - childSize.height;
    }
    dy = dy.clamp(8.0, (size.height - childSize.height - 8).clamp(8.0, double.infinity));

    // 横向：尽量与图标居中对齐，再夹回屏幕内（否则贴边的标签会把气泡挤出屏幕）。
    double dx = anchor.center.dx - childSize.width / 2;
    dx = dx.clamp(8.0, (size.width - childSize.width - 8).clamp(8.0, double.infinity));
    return Offset(dx, dy);
  }

  @override
  bool shouldRelayout(covariant _AnchorDelegate old) =>
      old.anchor != anchor || old.screen != screen;
}
