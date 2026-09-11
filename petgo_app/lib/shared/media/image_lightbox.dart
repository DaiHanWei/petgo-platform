import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../widgets/app_image.dart';

/// 全屏图片查看器（灯箱）。V1.3.0 批次 A · Story 3.1（FR-115 · AD-A14）。
///
/// ## 为什么在 `shared/` 而不是 `content/`
/// 改前它是 `content_detail_page.dart` 里的私有 `_Lightbox`。批次 B1 的场所照片（FR-112）
/// 要用同一个查看器 —— 留在 `content` 里，`places` 就得反向依赖 `content` 才能复用，
/// 否则只能再写一个。**本组件是批次 B1 的前置交付物，接口不是内部实现细节。**
///
/// ## 🔴 接口只认「URL 列表 + 下标 + 两个通用参数」（AD-A14.2）
/// **不接受也不感知**内容帖、场所或任何业务实体：没有 postId、没有 placeId、
/// 没有「这是什么东西的图」。给它一串 URL，它就放一串图。
///
/// 两个参数**是必需的，不是耦合**（AD-A14.2b，均为通用字符串形状）：
/// - [heroTagPrefix] —— 飞入动画要 Hero tag，而 **tag 从 URL 推导会炸**：
///   B1 场所页是「头图 + 照片网格」，同一张图出现两次 → 同 tag Hero 同屏 →
///   Flutter 直接抛异常。tag 必须由调用方给前缀来保证唯一。
/// - [source] —— 否则 `lightbox_opened` 分不清帖子与场所，FR-115 的指标从 B1 上线起就断裂。
///   这是对 AD-A23「不得增删属性」的**已登记显式例外**。
///
/// ## 本 story 的边界（AC7）
/// - 四个手势（下滑关闭 / 双击缩放 / 放大态防误翻页 / 图内平移）属 **Story 3.2**；
/// - 过渡动画（Hero 飞入飞出）、加载态与失败重试、埋点属 **Story 3.3**
///   —— [heroTagPrefix] 与 [source] 在本 story 只是**被接住并记下**，3.3 才开始消费；
/// - **不为场所场景做任何特化**（B1 工程师在其基础上接入）；
/// - 病例图查看器（`shared/widgets/case_image_viewer.dart`）与分享卡预览**不动**。
///
/// ## 明确不做（AC6 · AD-A15.7）
/// **长按保存到相册**。盗用顾虑 + 相册权限 + 与分享卡定位冲突：要保存只能走带水印的分享卡。
class ImageLightbox extends StatefulWidget {
  const ImageLightbox({
    super.key,
    required this.urls,
    required this.initialIndex,
    required this.heroTagPrefix,
    required this.source,
  });

  /// 要展示的**原图** URL（不是缩略图 —— 「看清楚」正是打开它的唯一理由）。
  final List<String> urls;

  /// 初始页：用户点的是哪张就从哪张开始（AC2）。越界自动夹回合法范围。
  final int initialIndex;

  /// Hero tag 前缀，由调用方保证唯一（见类注释）。
  final String heroTagPrefix;

  /// 埋点来源。本批次值域只有 `content_detail`，B1 接入时增补（AD-A26.3）。
  final String source;

  /// 打开查看器。调用方一律走这个入口，不要自己 push ——
  /// 路由形态（是否透明、是否全屏）是本组件的事，Story 3.3 还会改它。
  static Future<void> open(
    BuildContext context, {
    required List<String> urls,
    required int initialIndex,
    required String heroTagPrefix,
    required String source,
  }) {
    if (urls.isEmpty) return Future<void>.value();
    return Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => ImageLightbox(
        urls: urls,
        initialIndex: initialIndex,
        heroTagPrefix: heroTagPrefix,
        source: source,
      ),
    ));
  }

  @override
  State<ImageLightbox> createState() => _ImageLightboxState();
}

/// 第 [index] 张图的 Hero tag。
///
/// 🔴 **tag 由前缀 + 下标拼出，绝不从 URL 推导**（AD-A14.2b）：同一张图在一个页面里
/// 出现两次（场所页的「头图 + 照片网格」）时，URL 相同 → tag 相同 → 同屏两个同 tag Hero →
/// Flutter 抛异常。缩略图一侧与查看器一侧必须用**这同一个函数**算 tag，否则飞不起来。
String lightboxHeroTag(String prefix, int index) => '$prefix#$index';

class _ImageLightboxState extends State<ImageLightbox> {
  late final PageController _controller;
  late int _current;

  int get _safeInitialIndex =>
      widget.urls.isEmpty ? 0 : widget.initialIndex.clamp(0, widget.urls.length - 1);

  @override
  void initState() {
    super.initState();
    _current = _safeInitialIndex;
    _controller = PageController(initialPage: _current);
    // AC1 全屏沉浸态：把系统栏收起来，图片铺满整块屏幕。
    // 改前这里是一个黑色 AppBar + 保留状态栏 —— 用户对比小红书后的原话是
    // 「点开放大不是全屏放大」，说的就是这一层。
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  }

  @override
  void dispose() {
    // 🔴 AC3：恢复系统栏**放在 dispose**，因为它对所有退出路径都会跑到 ——
    // ✕、单击图片/黑边、系统返回键、iOS 侧滑、上层把整条路由掀掉，都会走到这里。
    // 放进 ✕ 的 onTap 里只覆盖一条路，其余路径会把用户留在一个没有状态栏的界面上，
    // 而那个界面已经不是灯箱了。
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.manual, overlays: SystemUiOverlay.values);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      // AC1：**没有 AppBar**。页码与关闭都是悬浮件，图片因此能延伸到状态栏区域。
      body: Stack(
        children: [
          Positioned.fill(child: _pager()),
          // 悬浮控件套 SafeArea：系统栏虽已隐藏，刘海/挖孔仍在，不套会被切掉一角。
          SafeArea(
            child: Stack(
              children: [
                Positioned(top: 0, left: 0, child: _closeButton()),
                if (widget.urls.length > 1)
                  Positioned(top: 0, left: 0, right: 0, child: Center(child: _counterPill())),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _pager() => PageView.builder(
        key: const ValueKey('lightboxPager'),
        controller: _controller,
        // 🔴 **单图也用 PageView**（bug 20260727-372）：改前单图直接塞 InteractiveViewer，
        // 进灯箱后翻不到同一条内容的其余图。itemCount 是 1 时它退化成不可翻页，行为一致。
        itemCount: widget.urls.length,
        onPageChanged: (i) => setState(() => _current = i),
        itemBuilder: (context, i) => GestureDetector(
          // 🔴 **单击图片或黑边即关闭**（bug 20260701-192 的修复，对齐主流看图 App）。
          // opaque 让黑边也算命中区。四个手势（Story 3.2）要与它并存 —— 改写时顺手删掉就是回归。
          behavior: HitTestBehavior.opaque,
          onTap: () => Navigator.of(context).pop(),
          child: Center(
            // 缩放实现沿用 InteractiveViewer，本 story 只换外壳与接口，不换实现（AD-A14 / Dev Notes）。
            child: InteractiveViewer(
              child: AppImage.widget(widget.urls[i], fit: BoxFit.contain),
            ),
          ),
        ),
      );

  /// 悬浮关闭 ✕（左上）。命中框 44×44，图标仍是 22 —— 扩热区不放大图标。
  Widget _closeButton() => GestureDetector(
        key: const ValueKey('lightboxClose'),
        behavior: HitTestBehavior.opaque,
        onTap: () => Navigator.of(context).pop(),
        child: Container(
          width: 44,
          height: 44,
          margin: const EdgeInsets.all(8),
          decoration: const BoxDecoration(color: Colors.black38, shape: BoxShape.circle),
          child: const Icon(Icons.close_rounded, size: 22, color: Colors.white),
        ),
      );

  /// 页码胶囊（顶部居中），替代改前那个黑色 AppBar 标题（AC2）。
  Widget _counterPill() => Container(
        key: const ValueKey('lightboxCounter'),
        margin: const EdgeInsets.only(top: 14),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black38,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          '${_current + 1}/${widget.urls.length}',
          style: const TextStyle(fontSize: 13, color: Colors.white, fontWeight: FontWeight.w600),
        ),
      );
}
