import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';
import '../utils/compact_count.dart';

/// 主页 / 我的页的四格数据（V1.3.4 follow · Story 1.3 · AC4.2，UI 稿 A1 / A2）：
/// Postingan / Suka / Mengikuti / Pengikut。
///
/// 4 等宽列；数字 17 / w700 + 等宽数字，标签 11 / `ink2`；列间 1px `line2` 竖分隔（上下各内缩 6）。
/// 数字一律经 [formatCompactCount]（D-3）。
///
/// [onFollowingTap] / [onFollowersTap] 为 null 时那一格**不包 InkWell、无水波纹**
/// （Story 1.3 两格不可点；Story 2.2 接上关注列表）。
///
/// [postCount] 等传 null 时数字位显示「–」（T-14：我的页 `/me` 未返回时不闪 0）。
class ProfileStatsRow extends StatelessWidget {
  const ProfileStatsRow({
    super.key,
    required this.postCount,
    required this.likeCount,
    required this.followingCount,
    required this.followerCount,
    this.onFollowingTap,
    this.onFollowersTap,
  });

  final int? postCount;
  final int? likeCount;
  final int? followingCount;
  final int? followerCount;
  final VoidCallback? onFollowingTap;
  final VoidCallback? onFollowersTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    String fmt(int? n) => n == null ? '–' : formatCompactCount(n, locale);
    Widget divider() => Container(
          width: 1,
          margin: const EdgeInsets.symmetric(vertical: 6),
          color: AppColors.line2,
        );
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _cell(const ValueKey('profileStatPosts'), fmt(postCount), l10n.profileStatPosts, null),
          divider(),
          _cell(const ValueKey('profileStatLikes'), fmt(likeCount), l10n.profileStatLikes, null),
          divider(),
          _cell(const ValueKey('profileStatFollowing'), fmt(followingCount),
              l10n.followStatFollowing, onFollowingTap),
          divider(),
          _cell(const ValueKey('profileStatFollowers'), fmt(followerCount),
              l10n.followStatFollowers, onFollowersTap),
        ],
      ),
    );
  }

  Widget _cell(Key key, String value, String label, VoidCallback? onTap) {
    final content = Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 🛡 窄屏 + 文字缩放 1.3 时「999,9K」这类无空格串可能放不下 → 等比缩小，
          //    而不是折行截断把单位 K 丢掉（code-review 1.3 F4）。
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              value,
              maxLines: 1,
              style: const TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w700,
                color: AppColors.ink,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ),
          const SizedBox(height: 2),
          Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, color: AppColors.ink2),
          ),
        ],
      ),
    );
    return Expanded(
      child: KeyedSubtree(
        key: key,
        child: onTap == null
            ? content
            : InkWell(onTap: onTap, borderRadius: BorderRadius.circular(8), child: content),
      ),
    );
  }
}
