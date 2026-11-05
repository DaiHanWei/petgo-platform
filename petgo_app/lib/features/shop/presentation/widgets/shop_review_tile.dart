/// 一条商品评价（v1.4.2 shop Story 8.2：详情页评价区 B1 与全部评价页 B12 共用）。
///
/// 昵称（服务端已打码）、星级、相对时间、「Varian: {规格}」（[showSpec]）、正文、缩略图（点开全屏查看器）。
/// 🔴 昵称 / 规格缺失时**不画该行**，不编占位。
library;

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/case_image_viewer.dart';
import '../../domain/shop_review.dart';
import 'shop_decor.dart';
import 'shop_pressable.dart';

class ShopReviewTile extends StatelessWidget {
  const ShopReviewTile({super.key, required this.review, this.showSpec = false});

  final ShopReviewItem review;

  /// 全部评价页显示「Varian: {规格}」；详情页页内摘要不显示（UI B1）。
  final bool showSpec;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = review;
    final created = r.createdAt;
    final images = r.imageUrls.where((u) => u.isNotEmpty).toList(growable: false);
    return Padding(
      key: ValueKey('shopReview_${r.id}'),
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (r.nickname != null)
                Flexible(
                  child: Text(r.nickname!,
                      key: const ValueKey('shopReviewNickname'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.body
                          .copyWith(fontWeight: FontWeight.w600, color: ShopColors.text)),
                ),
              if (r.nickname != null) const SizedBox(width: 8),
              _Stars(rating: r.rating),
              const Spacer(),
              if (created != null)
                Text(formatPublishTime(context, l10n, created),
                    style: AppTypography.caption.copyWith(color: ShopColors.text4)),
            ],
          ),
          if (showSpec && r.specName != null) ...[
            const SizedBox(height: 4),
            Text('${l10n.tokoVariantLabel}: ${r.specName}',
                key: const ValueKey('shopReviewSpec'),
                style: AppTypography.caption.copyWith(color: ShopColors.text3)),
          ],
          if (r.content != null && r.content!.trim().isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(r.content!,
                style: AppTypography.body.copyWith(color: ShopColors.text2, height: 1.5)),
          ],
          if (images.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (var i = 0; i < images.length; i++)
                  ShopPressable(
                    key: ValueKey('shopReviewImage_${r.id}_$i'),
                    onTap: () =>
                        showImageGalleryFullScreen(context, srcs: images, initialIndex: i),
                    child: ShopImage(url: images[i], size: 64, radius: 8),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

class _Stars extends StatelessWidget {
  const _Stars({required this.rating});

  final int rating;

  @override
  Widget build(BuildContext context) => Semantics(
        label: '$rating/5',
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 1; i <= 5; i++)
              Icon(i <= rating ? Icons.star_rounded : Icons.star_outline_rounded,
                  size: 14, color: i <= rating ? ShopColors.warnTitle : ShopColors.disabledText),
          ],
        ),
      );
}
