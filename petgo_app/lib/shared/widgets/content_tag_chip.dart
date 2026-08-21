import 'package:flutter/material.dart';

import '../../core/analytics/analytics.dart';
import '../../core/theme/colors.dart';
import '../../features/content/domain/content_tag.dart';
import 'anchored_tooltip.dart';

/// 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。
///
/// 🛡 点击**复用 Story 5.1 建的那个共享 tooltip**，不新建（AC 明写不得两条 story 各建一个）。
///
/// 两种形态：
/// - [ContentTagChip.overlay]：叠在图片上（首页卡左下角位、详情页首图角落）—— 深底白字，图片明暗都压得住。
/// - [ContentTagChip.inline]：正文下方单独一行的小胶囊（详情页**无图**时用）。
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
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: onImage ? const Color(0x8A000000) : AppColors.goldTint,
          borderRadius: BorderRadius.circular(11),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(tag.icon, style: const TextStyle(fontSize: 11, height: 1.0)),
            const SizedBox(width: 4),
            // ⚠️ 标签文案由运营配，可能很长 —— 限一行 + 打点，
            // 否则窄屏上它会一路铺到轮播圆点底下（AC 自己也标了这个风险）。
            Flexible(
              child: Text(
                tag.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                  color: onImage ? Colors.white : AppColors.tipsBadgeText,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
