import 'package:flutter/material.dart';

import '../../core/analytics/analytics.dart';
import '../../core/theme/colors.dart';
import '../../features/content/domain/content_tag.dart';
import 'anchored_tooltip.dart';
import 'tag_icon.dart';

/// 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。
///
/// 🛡 点击**复用 Story 5.1 建的那个共享 tooltip**，不新建（AC 明写不得两条 story 各建一个）。
///
/// 两种形态：
/// - [ContentTagChip.overlay]：叠在图片上（首页卡左下角位、详情页首图角落）。
/// - [ContentTagChip.inline]：正文下方单独一行（详情页**无图**时用）。
///
/// 🛡 两种形态**视觉完全相同**（UI 稿 `.deco-badge`），区别只在**谁负责定位** ——
/// overlay 由 [FeedImage] 的左下角位摆放，inline 由调用方按普通行内元素排。
class ContentTagChip extends StatelessWidget {
  const ContentTagChip._({
    super.key,
    required this.tag,
    required this.onImage,
    required this.position,
  });

  /// 叠在图片上的形态。
  const ContentTagChip.overlay({
    Key? key,
    required ContentTag tag,
    required String position,
  }) : this._(key: key, tag: tag, onImage: true, position: position);

  /// 正文下方的形态（无图时）。
  const ContentTagChip.inline({
    Key? key,
    required ContentTag tag,
    required String position,
  }) : this._(key: key, tag: tag, onImage: false, position: position);

  final ContentTag tag;
  final bool onImage;

  /// 这个标签长在哪儿（埋点 E-16 的 `position`）：`feed` / `detail` / `diary`。
  ///
  /// 🔴 **与形态（overlay/inline）不是一回事**：详情页首图角落是 overlay、
  /// 详情页无图时是 inline，两者的 `position` 都是 `detail`。
  /// 拿 `onImage` 反推展示位会把这两种情况记成两个地方。
  final String position;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      key: ValueKey('contentTag_${tag.code}'),
      behavior: HitTestBehavior.opaque,
      // 🛡 点标签只弹提示、**不触发外层的整块点击**（否则点标签会被跳进详情页）。
      onTap: () {
        // E-16（Story 10.1 补齐）。`badge_id` 同 E-15 取 `code`。
        Analytics.capture('content_badge_tooltip_opened', {
          'badge_id': tag.code,
          'position': position,
        });
        showAnchoredTooltip(context, title: tag.name, message: tag.description);
      },
      child: Container(
        // UI 稿 `.deco-badge`：padding 4/10、全圆角、橙→红 135° 渐变、双层红投影。
        // 🔴 两种形态（叠图 / 无图 inline）**用同一套视觉** —— 规格里 6 处装饰标签
        //    全是这一个 class，没有第二套样式。此前实现成"叠图深底 / 无图金色浅底"
        //    是与规格不符（2026-08-24 实机比对发现）。
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            // CSS 135deg = 左上 → 右下。
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.gold, AppColors.decoBadgeEnd],
          ),
          borderRadius: BorderRadius.all(Radius.circular(999)),
          boxShadow: [
            // 规格两层都是 rgba(240,66,90,.22)：近处收边 + 远处扩散。
            BoxShadow(color: Color(0x38F0425A), offset: Offset(0, 1), blurRadius: 2),
            BoxShadow(color: Color(0x38F0425A), offset: Offset(0, 3), blurRadius: 8),
          ],
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Story 11.5：图标改为上传的图片（存量 emoji 走 TagIcon 内的兼容分支）。
            // 边长 9 = UI 稿 `.deco-badge .st` 的 font-size。
            TagIcon(icon: tag.icon, size: 9),
            const SizedBox(width: 4),
            // ⚠️ 标签文案由运营配，可能很长 —— 限一行 + 打点，
            // 否则窄屏上它会一路铺到轮播圆点底下（AC 自己也标了这个风险）。
            Flexible(
              child: Text(
                tag.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 9.5,
                  fontWeight: FontWeight.w700,
                  color: Colors.white,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
