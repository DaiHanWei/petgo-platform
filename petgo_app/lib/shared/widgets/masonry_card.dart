import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/domain/feed_item.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';
import 'post_cover.dart';

/// Feed 单列卡片（原型 feed.html `.card`）。
///
/// 头部行：作者头像（彩色首字母圆）+ 昵称 / 相对时间 + 类型彩徽章；
/// 其下正文（前 3 行）+ 全宽首图（无图 → 类型彩块）。注销作者 → 本地化「已注销用户」+ 默认头像，
/// 头像不可点（Story 3.8）。
class MasonryCard extends StatelessWidget {
  const MasonryCard({
    super.key,
    required this.item,
    required this.deletedUserLabel,
    this.onTap,
    this.onLongPress,
    this.onAuthorTap,
  });

  final FeedItem item;

  /// 注销用户占位文案（来自 .arb，双语）。
  final String deletedUserLabel;
  final VoidCallback? onTap;

  /// 长按 context menu（Story 3.7 举报入口，UX-DR12）。
  final VoidCallback? onLongPress;

  /// 点作者头像/昵称（Story 3.8 迷你主页卡）；注销作者不挂（不触发）。
  final VoidCallback? onAuthorTap;

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

  static String _relativeTime(AppLocalizations l10n, DateTime t) {
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return l10n.timeJustNow;
    if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
    if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
    return l10n.timeDaysAgo(d.inDays);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final String name =
        item.authorDeleted ? deletedUserLabel : (item.authorNickname ?? deletedUserLabel);
    final (badgeLabel, badgeFg, badgeBg) = _badgeStyle(item.type, l10n);
    final time = _relativeTime(l10n, item.createdAt);

    return Semantics(
      button: onTap != null,
      label: name,
      child: GestureDetector(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: AppRounded.phoneRadius,
            border: Border.all(color: AppColors.border),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 头部行：头像 + 昵称/时间 + 点赞 + 类型徽章（原型 .row）。
                    GestureDetector(
                      onTap: (item.authorDeleted || onAuthorTap == null) ? null : onAuthorTap,
                      child: Row(
                        children: [
                          _Avatar(
                              url: item.authorDeleted ? null : item.authorAvatarUrl,
                              name: name,
                              deleted: item.authorDeleted),
                          const SizedBox(width: AppSpacing.sm),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(name,
                                    style: const TextStyle(
                                        fontSize: 13.5, fontWeight: FontWeight.w700, color: AppColors.ink),
                                    maxLines: 1, overflow: TextOverflow.ellipsis),
                                Text(time,
                                    style: const TextStyle(fontSize: 11.5, color: AppColors.muted),
                                    maxLines: 1, overflow: TextOverflow.ellipsis),
                              ],
                            ),
                          ),
                          const SizedBox(width: AppSpacing.sm),
                          // 类型彩徽章（原型 badge）。
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                            decoration: BoxDecoration(
                                color: badgeBg, borderRadius: BorderRadius.circular(6)),
                            child: Text(badgeLabel,
                                style: TextStyle(
                                    fontSize: 10, fontWeight: FontWeight.w700, color: badgeFg)),
                          ),
                        ],
                      ),
                    ),
                    if (item.body != null && item.body!.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.sm),
                      Text(item.body!,
                          style: AppTypography.body, maxLines: 3, overflow: TextOverflow.ellipsis),
                    ],
                    SizedBox(height: item.hasImage ? AppSpacing.sm : AppSpacing.md),
                  ],
                ),
              ),
              // 全宽首图（无图 → 类型彩块占位）。固定高度 cover：原型图区高度受控，
              // 一屏可见多卡（避免真图按宽比撑满整屏，单卡占屏）。
              if (item.hasImage)
                AppImage.widget(
                  item.firstImageUrl!,
                  fit: BoxFit.cover,
                  width: double.infinity,
                  height: _placeholderHeight(item.id),
                  errorBuilder: (context, error, stack) =>
                      PostCoverPlaceholder(type: item.type, height: _placeholderHeight(item.id)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 占位封面高度：按 id 在 140/180/220 间轻微错落（单列卡更宽，封面更高）。
double _placeholderHeight(int id) => 140 + (id.abs() % 3) * 40;

/// 作者头像（原型 .av）：有图用网络图，否则彩色圆 + 昵称首字母。
class _Avatar extends StatelessWidget {
  const _Avatar({this.url, required this.name, this.deleted = false});

  final String? url;
  final String name;
  final bool deleted;

  static const List<Color> _palette = [
    AppColors.mint,
    AppColors.mint500,
    AppColors.triageGreen,
    AppColors.gold,
    AppColors.coral,
  ];

  @override
  Widget build(BuildContext context) {
    const double size = 34;
    if (url != null && url!.isNotEmpty) {
      return CircleAvatar(radius: size / 2, backgroundImage: AppImage.provider(url));
    }
    final trimmed = name.trim();
    // 注销作者 → 默认 person 头像（Story 3.8），不用昵称首字母。
    if (deleted || trimmed.isEmpty) {
      return const CircleAvatar(
        radius: size / 2,
        backgroundColor: AppColors.border,
        child: Icon(Icons.person_rounded, size: 18, color: AppColors.textTertiary),
      );
    }
    final color = _palette[trimmed.codeUnits.fold<int>(0, (a, b) => a + b) % _palette.length];
    return CircleAvatar(
      radius: size / 2,
      backgroundColor: color,
      child: Text(trimmed.characters.first.toUpperCase(),
          style: const TextStyle(
              fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
    );
  }
}
