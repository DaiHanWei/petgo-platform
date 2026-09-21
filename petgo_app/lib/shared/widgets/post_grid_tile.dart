import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';
import 'post_cover.dart';

/// 2 列内容网格里的一格（原型 `pgrid` 的一个 cell）。
///
/// ## 为什么抽成公共组件（V1.3.0 batch-b1 Story 2.2）
/// 这段视觉原本是 `me_page.dart` 里的私有 `_MyPostCard`。Story 2.2 要在**他人公开主页**上
/// 复用「我的」页那套网格（AC4）—— 复制一份的话，圆角 / badge 配色 / 首图降级这些细节
/// 会各改各的，两个页面几个版本之后就长得不一样了。
///
/// ⚠️ **本组件只管一格的样子**：网格布局（列数 / 间距）、点击去哪儿、以及取数分页
/// 都由各自页面决定 —— 两边的数据源与分页策略本来就不同。
///
/// ## [isPrivate] 只在作者自视时才可能为真
/// 他人主页的列表**服务端就只给 PUBLIC**（NFR-2），所以那边这个角标永远不出现。
/// 不要因此把它删掉：「我的发布」靠它给未同步到 Moment 的 Diary 打「仅自己可见」。
class PostGridTile extends StatelessWidget {
  const PostGridTile({
    super.key,
    required this.postId,
    required this.type,
    required this.onTap,
    this.firstImageUrl,
    this.isPrivate = false,
  });

  final int postId;

  /// 内容类型线格式：DAILY / GROWTH_MOMENT / KNOWLEDGE。
  final String type;

  final String? firstImageUrl;

  /// 仅自己可见（Story 4.2 · AC8）。他人主页恒为 false。
  final bool isPrivate;

  final VoidCallback onTap;

  /// 类型 → (badge 文案, 文字色, 底色)：Momen 绿 / Tips 黄 / Cerita 紫（原型 b-happy/b-tips/b-story）。
  static (String, Color, Color) _badgeStyle(String type, AppLocalizations l10n) {
    switch (type) {
      case 'GROWTH_MOMENT':
        return (l10n.mePostTypeMomen, AppColors.momenBadgeText, AppColors.momenBadgeBg);
      case 'KNOWLEDGE':
        return (l10n.mePostTypeTips, AppColors.tipsBadgeText, AppColors.goldTint);
      default: // DAILY
        return (l10n.mePostTypeCerita, AppColors.mint, AppColors.skyTint);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hasImage = firstImageUrl != null && firstImageUrl!.isNotEmpty;
    final (label, fg, bg) = _badgeStyle(type, l10n);
    return GestureDetector(
      key: ValueKey('postGridTile_$postId'),
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(11),
        child: Stack(
          fit: StackFit.expand,
          children: [
            hasImage
                ? AppImage.widget(
                    firstImageUrl!,
                    fit: BoxFit.cover,
                    thumbWidth: 400, // 网格小图
                    errorBuilder: (context, error, stack) =>
                        PostCoverPlaceholder(type: type, emojiSize: 30),
                  )
                : PostCoverPlaceholder(type: type, emojiSize: 30),
            // 私密标识：放右上角与左上角的类型 badge 分开，两者可同时出现
            // （一条内容既是 Diary 又是私密）。
            if (isPrivate)
              Positioned(
                top: 5,
                right: 5,
                child: Container(
                  key: ValueKey('postGridTilePrivate_$postId'),
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppColors.ink.withValues(alpha: 0.62),
                    borderRadius: BorderRadius.circular(5),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.lock_outline, size: 9, color: AppColors.onAccent),
                      const SizedBox(width: 2),
                      Text(
                        l10n.mePostPrivateBadge,
                        style: const TextStyle(
                          fontSize: 9,
                          fontWeight: FontWeight.w700,
                          color: AppColors.onAccent,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            Positioned(
              top: 5,
              left: 5,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: bg,
                  borderRadius: BorderRadius.circular(5),
                ),
                child: Text(
                  label,
                  style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: fg),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
