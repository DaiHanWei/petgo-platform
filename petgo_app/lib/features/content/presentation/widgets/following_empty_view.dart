import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../social/presentation/widgets/suggested_follow_list.dart';
import '../social_tab.dart';

/// Following 空状态（spec §2.4 · UI 稿 A3）：图标 + 标题 + 说明 + 推荐关注 +「Explore Discover ›」。
///
/// 触发 = 关注流首页为空（一个都没关注，或关注的人都没有可见帖）；此时**不显示头像排**。
/// 在这里关注某人后**不立刻刷新**关注流（2026-10-14 拍板）——下拉刷新或下次进入才出现。
/// 本组件放进可下拉的滚动容器里由调用方提供下拉刷新。
class FollowingEmptyView extends ConsumerWidget {
  const FollowingEmptyView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      key: const ValueKey('followingEmptyView'),
      padding: const EdgeInsets.fromLTRB(24, 32, 24, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 96,
              height: 96,
              decoration: const BoxDecoration(color: AppColors.violet100, shape: BoxShape.circle),
              child: const Icon(Icons.person_add_alt_outlined, size: 42, color: AppColors.mint),
            ),
          ),
          const SizedBox(height: 24),
          Text(
            l10n.followingEmptyTitle,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.ink),
          ),
          const SizedBox(height: 10),
          Text(
            l10n.followingEmptyBody,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 14.5, height: 1.45, color: AppColors.ink2),
          ),
          const SizedBox(height: 16),
          const SuggestedFollowList(
            source: 'following_empty',
            surfaceScope: 'followingEmpty',
            maxItems: 5,
            emphasized: true,
          ),
          const SizedBox(height: 16),
          Center(
            child: TextButton(
              key: const ValueKey('followingExploreDiscover'),
              onPressed: () => ref.read(socialTabProvider.notifier).select(SocialTab.discover),
              style: TextButton.styleFrom(foregroundColor: AppColors.mint),
              child: Text(
                l10n.followingExploreDiscover,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
