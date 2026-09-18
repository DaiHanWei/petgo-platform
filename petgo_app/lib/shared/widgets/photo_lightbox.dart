import 'package:flutter/material.dart';

import '../../core/theme/typography.dart';
import 'app_image.dart';

/// 全屏照片灯箱：`PageView` 承载多图左右翻页，每页可捏合缩放；顶栏显示页码。
///
/// <h2>🔴 这是从内容详情页**原样搬过来**的（V1.3.0 batch-b1 Story 1.6 · AC1）</h2>
/// 原址 `content_detail_page.dart` 的私有 `_Lightbox`。搬的目的只是**让场所详情页也能用**
/// （AC2），**不是**顺手优化灯箱：
/// <ul>
///   <li>任何视觉 / 交互改动都会让内容详情页的既有验收失效；</li>
///   <li>重做灯箱是**批次 A 的 FR-115**（新版灯箱）的事 —— 在这里改会与那条撞车。</li>
/// </ul>
/// 所以下面两条历史 bug 的修复**必须原样保留**：
/// <ul>
///   <li>**bug 20260727-372**：单图进灯箱后无法滑动看其余图 —— 因此外层是 `PageView`
///       而不是一个裸 `InteractiveViewer`。别"优化"成单图直接 InteractiveViewer。</li>
///   <li>**bug 20260701-192**：点图片（或黑边）关闭大图，对齐主流看图 App 交互。</li>
/// </ul>
///
/// <h2>⚠️ 另有一个全屏看图组件，二者**刻意不合并**</h2>
/// `case_image_viewer.dart` 的 `showImageGalleryFullScreen`（兽医 / IM / 病例 / 商品详情用）
/// 是另一套实现（`showDialog` + 支持本地 `File`）。把两者合并会同时动到那四处调用方的行为，
/// 而本 story 的口径是「只搬不改」。**统一它们属于批次 A 的 FR-115。**
///
/// 🔴 调用方要传**能拿到的最大尺寸那条 URL**，不是列表里那张缩略图 ——
/// 缩略图放大只会看到一团糊，而"看清楚"正是打开灯箱的唯一理由。
/// （场所照片的"最大尺寸"是服务端给的 1080 px 那一版：真·原图不对外发，
/// 因为那条路径没有 `format,jpg` 重编码，会带着 GPS 出去 —— E4。）
///
/// <h2>后续动作（Story 1.6 · AC3 · B1-D4）</h2>
/// TODO(批次A/FR-115)：批次 A 的**新版灯箱**落地后，把本组件与
/// `case_image_viewer.dart` 的 `showImageGalleryFullScreen` **整体替换**成那一份，
/// 届时本文件与它的调用方（内容详情页 / 场所详情页）一并改。
/// ⚠️ **本 story 不等批次 A** —— 那边还没交付，等它等于让 B1 停摆（B1-D4 原话）。
/// 替换时要带走的历史修复仍是上面那两条 bug（20260727-372 / 20260701-192），
/// 以及"别传列表缩略图"这条。
class PhotoLightbox extends StatefulWidget {
  const PhotoLightbox({super.key, required this.urls, required this.initialIndex});

  final List<String> urls;
  final int initialIndex;

  @override
  State<PhotoLightbox> createState() => _PhotoLightboxState();
}

/// 打开灯箱（`MaterialPageRoute` push —— 与内容详情页原先的行为一致）。
///
/// 空列表直接不开（没有可看的东西，开出来是一屏黑）。
/// 下标越界一律夹回合法范围：调用方的下标来自各自的轮播状态，少一张图就会越界，
/// 不该因此崩在 `PageController` 里。
Future<void> openPhotoLightbox(
  BuildContext context, {
  required List<String> urls,
  int initialIndex = 0,
}) {
  if (urls.isEmpty) return Future<void>.value();
  return Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (_) => PhotoLightbox(
      urls: urls,
      initialIndex: initialIndex.clamp(0, urls.length - 1),
    ),
  ));
}

class _PhotoLightboxState extends State<PhotoLightbox> {
  /// 🔴 夹取放在**组件自己**这里，不是只放在 `openPhotoLightbox`（code-review 2026-09-15）：
  /// `PhotoLightbox` 已经是公共类，谁都能直接 `MaterialPageRoute(builder: …)` 构造 ——
  /// 那条路径上越界下标会给出一屏空白视口 + 标题「6/2」。
  late final int _initial = widget.urls.isEmpty
      ? 0
      : widget.initialIndex.clamp(0, widget.urls.length - 1);
  late final PageController _controller = PageController(initialPage: _initial);
  late int _current = _initial;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: widget.urls.length > 1
            ? Text('${_current + 1}/${widget.urls.length}',
                style: AppTypography.body.copyWith(color: Colors.white))
            : null,
        centerTitle: true,
      ),
      body: PageView.builder(
        controller: _controller,
        itemCount: widget.urls.length,
        onPageChanged: (i) => setState(() => _current = i),
        // 点击图片（或黑边）关闭大图（bug 20260701-192，对齐主流看图 App 交互；与翻页/缩放手势不冲突）。
        itemBuilder: (context, i) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => Navigator.of(context).pop(),
          child: Center(
            child: InteractiveViewer(
              child: AppImage.widget(
                widget.urls[i],
                fit: BoxFit.contain,
                // ⚠️ 这是「只搬不改」的**唯一一处例外**，而且只动失败路径：
                // 原先没有 errorBuilder —— 死链进来是**一屏全黑** + 每页一条未捕获图片异常，
                // 用户分不清"还在加载"和"这张没了"。而 Story 1.6 把场所横滑流的**破图占位
                // 也变成了可点的**，点破图正好落进那一屏黑。成功路径的渲染一字未变。
                errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined,
                    color: Colors.white54, size: 48),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
