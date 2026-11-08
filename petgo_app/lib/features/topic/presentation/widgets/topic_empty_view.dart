import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/router/route_intent.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/empty_state.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../../content/domain/content_type.dart';
import '../../../content/presentation/publish_compose_page.dart';
import '../../domain/topic.dart';
import '../topic_page.dart';
import 'topic_official_badge.dart';

/// 打开发布页的函数签名（测试注入用）。
typedef TopicPublishOpener = Future<void> Function(BuildContext context, String initialText);

/// 话题页空态（V1.4.2 social-topic · Story 4.3 · AC2.4 · UI C9）：当前页签首屏 0 条。
///
/// 从页签下 32 起：`EmptyState(hideIcon)`（标题 18 → 8 → 副文 13 → 16 →「Buat Postingan」）→ 32 →
/// 「Topik populer lainnya」15/1.45 w600 → 8 → 推荐行（≤10，44 高、整行 InkWell、无分隔线）。
/// 推荐为空（含取数失败）→ 标题与列表整段不渲染。
class TopicEmptyView extends ConsumerWidget {
  const TopicEmptyView({
    super.key,
    required this.displayName,
    required this.related,
    this.topGap = AppSpacing.xxl,
  });

  /// 空态块距页签的上间距（UI C9：页签下 32）。嵌在 `FeedMasonryView` footer 里时它已有 16 上边距，调用方传 16。
  final double topGap;

  /// 预填用的显示写法（`#KucingLucu `，story「待确认」第 5 条）。
  final String displayName;
  final List<TopicSummary> related;

  /// 测试钩子：非 null 时代替 `PublishComposePage.open`（断言预填参数用）。生产恒为 null。
  @visibleForTesting
  static TopicPublishOpener? debugPublishOpener;

  static const double relatedRowHeight = 44;

  Future<void> _openPublish(BuildContext context) {
    final text = '#$displayName ';
    final hook = debugPublishOpener;
    if (hook != null) return hook(context, text);
    // 默认 Momen（DAILY），预填「#显示写法 」带尾随空格（发布页已支持 initialText）。
    return PublishComposePage.open(context, preset: ContentType.daily, initialText: text);
  }

  void _onCreate(BuildContext context, WidgetRef ref) {
    void open() {
      if (context.mounted) _openPublish(context);
    }

    // 游客 → 强登录引导；登录成功后由 pendingAction 续开发布页（回调只用 context，不碰 WidgetRef）。
    requireLogin(
      ref,
      context,
      entrySource: 'topic_empty',
      pendingAction: RouteIntent(onResume: open),
      onAllowed: open,
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Column(
      key: const ValueKey('topicEmptyView'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(height: topGap),
        EmptyState(
          hideIcon: true,
          title: l10n.topicEmptyTitle,
          message: l10n.topicEmptyBody,
          actionLabel: l10n.topicEmptyCta,
          onAction: () => _onCreate(context, ref),
        ),
        if (related.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.xxl),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
            child: Text(
              l10n.topicRelatedTitle,
              key: const ValueKey('topicRelatedTitle'),
              style: const TextStyle(
                fontSize: 15,
                height: 1.45,
                fontWeight: FontWeight.w600,
                color: AppColors.ink,
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          for (var i = 0; i < related.length && i < 10; i++) _row(context, related[i], i),
          const SizedBox(height: AppSpacing.xxl),
        ],
      ],
    );
  }

  Widget _row(BuildContext context, TopicSummary t, int i) {
    return InkWell(
      key: ValueKey('topicRelated_${t.name}'),
      onTap: () => context.push(TopicPage.routeFor(t.name, from: TopicEntrySource.related)),
      child: SizedBox(
        height: relatedRowHeight,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
          child: Row(
            children: [
              ConstrainedBox(
                constraints: const BoxConstraints(minWidth: 16),
                child: Text(
                  '${i + 1}',
                  key: ValueKey('topicRelatedRank_${i + 1}'),
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    fontFeatures: const [FontFeature.tabularFigures()],
                    color: i < 3 ? AppColors.mint600 : AppColors.muted,
                  ),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              // 名字 + 官方标记占满剩余宽度，箭头恒贴右（UI 稿 C9）；不能用 Flexible + Spacer —— 两者平分空余，箭头随名字长短漂移。
              Expanded(
                child: Row(
                  children: [
                    Flexible(
                      child: Text(
                        '#${t.displayName}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.body,
                      ),
                    ),
                    if (t.official) ...[
                      const SizedBox(width: AppSpacing.sm),
                      const TopicOfficialBadge(),
                    ],
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded, size: 20, color: AppColors.muted),
            ],
          ),
        ),
      ),
    );
  }
}
