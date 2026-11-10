import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../domain/topic.dart';
import '../topic_page.dart';
import '../topic_providers.dart';
import 'topic_official_badge.dart';

/// Discover 首页话题行（V1.4.2 social-topic · Story 4.3 · AC4 · UI C1）。
///
/// 纯文字链：「#displayName」13/1.4 w600 mint600 + 官方项后 4 跟 Resmi；项距 16、每项上下 12（可点高 44）、
/// 屏边 16；横向滚动，被屏边截断的那项即「可右滑」提示。
/// 🛡 0 个 / 取数失败 / 加载中 → **整行不渲染、不留占位**（同 `PetRecommendationStrip`）。
class HomeTopicRow extends ConsumerWidget {
  const HomeTopicRow({super.key});

  static const double itemHeight = 44;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final topics = ref.watch(homeTopicRowProvider).value;
    if (topics == null || topics.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      key: const ValueKey('homeTopicRow'),
      height: itemHeight,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        itemCount: topics.length,
        separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.lg),
        itemBuilder: (context, i) => _item(context, topics[i], i),
      ),
    );
  }

  Widget _item(BuildContext context, TopicSummary t, int index) {
    return InkWell(
      key: ValueKey('homeTopic_${t.name}'),
      onTap: () {
        // R-2：topic_id = 标准名；不传显示写法。
        Analytics.capture('home_topic_row_tapped', {
          'topic_id': t.name,
          'position': index + 1,
          'is_pinned_by_ops': t.featured,
        });
        context.push(TopicPage.routeFor(t.name, from: TopicEntrySource.homeRow));
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '#${t.displayName}',
              style: const TextStyle(
                fontSize: 13,
                height: 1.4,
                fontWeight: FontWeight.w600,
                color: AppColors.mint600,
              ),
            ),
            if (t.official) ...[
              const SizedBox(width: AppSpacing.xs),
              const TopicOfficialBadge(),
            ],
          ],
        ),
      ),
    );
  }
}
