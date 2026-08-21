import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_export.dart';
import '../../../../shared/card_render/card_frame.dart';
import '../../../../shared/card_render/card_render_pipeline.dart';
import '../../../../shared/card_render/card_watermark.dart';
import '../../domain/share_card_data.dart';
import 'share_card_template.dart';

/// 分享卡预览（Story 9.3 · UI 稿 SH2 / SH3）。
///
/// 详情页互动栏的分享图标 → 本页 → 「分享到 Story」→ 出图 → 系统分享菜单。
///
/// ⚠️ **必须有这一屏**，不是产品加戏：出图靠的是截屏式导出，卡面**得先真的画在屏幕上**
/// 才能截。把卡藏在屏幕外 offstage 是不画的，`toImage` 会拿到空图。
/// 顺带也符合 UI 稿 SH2/SH3 那两屏（顶栏「Pratinjau Kartu」+ 吸底 CTA）。
///
/// 尺寸切换（9:16 / 1:1）是**研发加的开关**：UI 稿只写了"双规格"没画切换控件，
/// 而两种尺寸都做了就得有地方选。若产品另有想法，改这一处即可。
class ShareCardPreviewPage extends StatefulWidget {
  const ShareCardPreviewPage({super.key, required this.data});

  final ShareCardData data;

  /// 出图测试缝。
  ///
  /// ⚠️ 为什么需要它：`toImage` 是**真实引擎异步操作**，在 widget test 默认的
  /// fake-async 时钟里永远不会完成（9-1 踩过）。不留缝的话，「点了分享按钮之后
  /// 到底有没有上报埋点」这条根本跑不到。
  ///
  /// 它替掉的只是"调用管线"这一步 —— 管线本身在 9-1 有自己的像素级测试，
  /// 所以这里没有把任何未验证的东西藏起来。
  @visibleForTesting
  static Future<Uint8List?> Function(CardCanvas canvas)? captureForTest;

  @override
  State<ShareCardPreviewPage> createState() => _ShareCardPreviewPageState();
}

class _ShareCardPreviewPageState extends State<ShareCardPreviewPage> {
  final GlobalKey _boundaryKey = GlobalKey();


  /// 默认 9:16（Instagram Stories 是这个功能的主场景）。
  CardCanvas _canvas = CardCanvas.story;
  bool _busy = false;

  Future<void> _shareIt() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _busy = true);
    try {
      final capture = ShareCardPreviewPage.captureForTest;
      final startedAt = DateTime.now();
      final bytes = capture != null
          ? await capture(_canvas)
          : await CardRenderPipeline.capture(boundaryKey: _boundaryKey, canvas: _canvas);
      final elapsedMs = DateTime.now().difference(startedAt).inMilliseconds;
      if (!mounted) return;
      if (bytes == null) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(l10n.shareCardExportError)));
        return;
      }

      // ===== 埋点：出图与分享是**两个**事件，别再合成一个（Story 10.1 订正）=====
      //
      // 🔴 9-3 当初把 `post_share_card_sent` 报在了这一刻（出图成功），并带了
      //    `ratio`/`template` 两个属性。三处都不对：
      //    ① **时机**：清单与 PRD 都写 E-13 = 「系统分享菜单回调成功」。报在出图那刻，
      //       等于**每次预览导出都算一次分享** —— 用户看了一眼就退出也计入，
      //       「实际分享出去多少」这个数直接失真（且只会高估，无法事后修正）。
      //    ② **属性**：E-13 唯一的属性是 `channel`（走哪个渠道），当时没报；
      //       报上去的 `ratio`/`template` 其实是 E-12 的 `size`/`template`。
      //    ③ **词表**：`ratio: '1:1'/'9:16'` 与 `template: 'text'` 都不在清单 §3 的词表里
      //       （应为 `size: '1x1'/'9x16'`、`template: 'text_only'`）。
      //    现按 E-12 / E-13 各归各位。
      //
      // E-12：出图成功。`duration_ms` 是**生成基建的性能**，也是这条的加粗属性。
      // ⚠️ 用 unawaited 语义：埋点不该挡住分享面板弹出。
      Analytics.capture('post_share_card_generated', {
        'template': widget.data.hasImage ? 'image' : 'text_only',
        'size': _canvas == CardCanvas.square ? '1x1' : '9x16',
        'duration_ms': elapsedMs,
      });

      final box = context.findRenderObject() as RenderBox?;
      final origin = box != null ? box.localToGlobal(Offset.zero) & box.size : null;
      await CardExport.showSheet(
        context,
        bytes: bytes,
        name: 'tailtopia_card',
        shareOrigin: origin,
        // E-13：**系统面板回调分享成功之后**才报，取消不报。
        onShared: (channel) =>
            Analytics.capture('post_share_card_sent', {'channel': channel}),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.shareCardPreviewTitle)),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: SegmentedButton<CardCanvas>(
                key: const ValueKey('shareCardRatioToggle'),
                segments: const [
                  ButtonSegment(value: CardCanvas.story, label: Text('9:16')),
                  ButtonSegment(value: CardCanvas.square, label: Text('1:1')),
                ],
                selected: {_canvas},
                onSelectionChanged: (s) => setState(() => _canvas = s.first),
              ),
            ),
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 48),
                  child: CardFrame(
                    // key 随画布变：换尺寸时强制重建，别让 State 复用旧画布的布局。
                    key: ValueKey(_canvas),
                    boundaryKey: _boundaryKey,
                    canvas: _canvas,
                    // 水印挂截图区外：预览与手动截屏带水印，正式导出不带（9-1 AC5）。
                    watermark: CardWatermark(canvas: _canvas),
                    child: ShareCardTemplate(data: widget.data, canvas: _canvas),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('shareCardShareCta'),
                  onPressed: _busy ? null : _shareIt,
                  child: Text(l10n.shareCardShareCta),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
