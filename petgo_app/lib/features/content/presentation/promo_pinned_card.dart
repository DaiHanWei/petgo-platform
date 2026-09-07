import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../shared/widgets/feed_image.dart';
import '../domain/pinned_slot.dart';
import 'pinned_badge.dart';

/// 推广卡片（V1.1.6 Story 4.3 · FR-68 对象 b）。
///
/// ## 🔴 为什么不长得跟普通内容卡一模一样
/// UI 稿屏 02 标着「推广卡片」，画出来的却是一张**普通内容卡的数据**：
/// 头像 + 作者名 + 类型徽章 + 「···」+ 点赞 212 + 评论 27 + 正文 + 「10 分钟前」，
/// 而且**没画顶置角标**。
///
/// 但推广卡片按 FR-68 只有**三个字段**：图片、标题、跳转目标 ——
/// 它**没有作者、没有点赞评论、没有时间**。照那一屏实现意味着要**编造**
/// 一个作者名和一组互动数字给用户看，那不是"视觉一致"，那是骗人。
///
/// 所以取 FR-68 正文的口径「与普通内容条目相同的外观 + 顶置角标」，落成：
/// **图片（同一条三段口径 + 右上角顶置角标）+ 标题（占正文的位置与排版）**，
/// 没有数据的三块就不渲染。
///
/// ## 🛡 不加"广告 / 推广"字样
/// 与顶置的已发布内容**共用同一个角标**，视觉不作区分（FR-68）。
/// 本版本按"平台自有活动引导"定位使用，不涉及广告披露要求。
/// ⚠️ 若未来承接第三方付费投放，须重新评估应用商店的广告政策 —— 记为商业化前的前置检查。
class PromoPinnedCard extends StatelessWidget {
  const PromoPinnedCard({
    super.key,
    required this.promo,
    required this.maxImageHeight,
    this.onTap,
  });

  final PromoCard promo;
  final double maxImageHeight;

  /// 空 = 纯展示卡（不可点）。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.of(context).size.width;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 图片走与普通卡**同一条**三段口径：比例收敛 + 高度护栏 + 加载期占位。
          // ⚠️ 因此 16:9 的横幅进首页会被左右各裁约四分之一 —— 运营做素材时就该按 0.75~1.34 出图。
          FeedImage(
            urls: [promo.imageUrl],
            type: 'DAILY',
            declaredSize: null, // 运营配的素材没有尺寸数据，走占位兜底那条路
            width: width,
            maxImageHeight: maxImageHeight,
            topRight: const PinnedBadge(),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge, 10, AppSpacing.screenEdge, 0),
            // 标题占正文的位置与排版（同一套字号与行数上限）。
            child: Text(promo.title,
                key: const ValueKey('promoTitle'),
                style: AppTypography.body,
                maxLines: 2,
                overflow: TextOverflow.ellipsis),
          ),
          // ⚠️ 刻意不渲染作者行 / 操作行 / 时间行 —— 推广卡片没有这三样数据。
          const SizedBox(height: 5, child: ColoredBox(color: AppColors.surface)),
        ],
      ),
    );
  }
}
