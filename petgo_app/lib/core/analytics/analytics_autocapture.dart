import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

import 'analytics.dart';

/// 全局点击 autocapture：在根部拦每一次 tap，从 semantics 树找到命中点下「最深的可点节点」，
/// 取其 label 作为 `button_tapped` 的 button_name（经 [Analytics.sanitizeTapLabel] 脱敏）。
///
/// 为什么用 semantics 而非 hit-test 渲染树：semantics 节点天然只在「可交互」处带 tap action，
/// 自动过滤滚动/纯展示区域；label 又是 Flutter 从 Text/tooltip/Semantics 归并好的人类可读串。
/// 代价：强制开启 semantics 树（轻微开销）；label 为本地化文案，故配 PII 兜底。
class AnalyticsAutocapture extends StatefulWidget {
  const AnalyticsAutocapture({super.key, required this.child});

  final Widget child;

  @override
  State<AnalyticsAutocapture> createState() => _AnalyticsAutocaptureState();
}

class _AnalyticsAutocaptureState extends State<AnalyticsAutocapture> {
  SemanticsHandle? _semanticsHandle;

  @override
  void initState() {
    super.initState();
    // 强制常开 semantics 树（否则仅在无障碍服务激活时才有），autocapture 据此识别可点节点。
    _semanticsHandle = SemanticsBinding.instance.ensureSemantics();
  }

  @override
  void dispose() {
    _semanticsHandle?.dispose();
    _semanticsHandle = null;
    super.dispose();
  }

  void _onPointerUp(PointerUpEvent event) {
    final label = _tappableLabelAt(event.position);
    if (label == null) return; // 命中点下无可点节点（滚动/空白）→ 不上报
    Analytics.captureTap(label);
  }

  /// 在 semantics 树中找命中 [global] 且带 tap action 的最深（面积最小）节点的 label。
  String? _tappableLabelAt(Offset global) {
    final root = _rootSemanticsNode();
    if (root == null) return null;

    SemanticsNode? best;
    var bestArea = double.infinity;

    void visit(SemanticsNode node, Matrix4 ancestorToGlobal) {
      // node.transform: 本节点坐标系 → 父坐标系；累乘得 本节点 → 全局。
      final ownToGlobal = node.transform == null
          ? ancestorToGlobal
          : (ancestorToGlobal.clone()..multiply(node.transform!));
      final globalRect = MatrixUtils.transformRect(ownToGlobal, node.rect);
      if (globalRect.contains(global)) {
        final data = node.getSemanticsData();
        if (data.hasAction(SemanticsAction.tap)) {
          final area = globalRect.width * globalRect.height;
          if (area <= bestArea) {
            bestArea = area;
            best = node;
          }
        }
      }
      node.visitChildren((child) {
        visit(child, ownToGlobal);
        return true;
      });
    }

    visit(root, Matrix4.identity());

    final node = best;
    if (node == null) return null;
    final data = node.getSemanticsData();
    return data.label.isNotEmpty ? data.label : data.tooltip;
  }

  /// 取活跃 view 的 semantics 根节点（多视图下 semanticsOwner 挂在子 pipeline owner 上，故递归找）。
  SemanticsNode? _rootSemanticsNode() {
    SemanticsNode? found;
    void search(PipelineOwner owner) {
      found ??= owner.semanticsOwner?.rootSemanticsNode;
      if (found == null) owner.visitChildren(search);
    }

    search(RendererBinding.instance.rootPipelineOwner);
    return found;
  }

  @override
  Widget build(BuildContext context) {
    // translucent：自身不吞手势，仅旁路监听 pointer，不影响下层正常点击。
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerUp: _onPointerUp,
      child: widget.child,
    );
  }
}
