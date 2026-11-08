import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/rich_text/body_tokens.dart';
import '../../../../shared/widgets/keyboard_suggest_panel.dart';
import '../../domain/topic.dart';
import 'topic_official_badge.dart';

/// 发布页 # 联想面板（V1.4.2 social-topic · Story 4.4 · C2 / C3 / E2 / E5）。
///
/// 纯展示：状态（加载 / 失败 / 结果）由发布页持有并传入。外壳是共享件 [KeyboardSuggestPanel]。
/// - 有结果：每行「# 圆位 + #displayName (+ Resmi)」，服务端顺序即官方在前；
/// - 无结果且关键词合法：只剩一行「#关键词 / Topik baru」（C3）；关键词不合法且无结果 → 宿主不该渲染本面板（[shouldShow]）；
/// - 加载中：居中 18px 转圈、内边距 12、无文案（E2）；
/// - 失败：一行 `detailNetworkError` + 「Coba lagi」（E5）。
class TopicSuggestPanel extends StatelessWidget {
  const TopicSuggestPanel({
    super.key,
    required this.keyword,
    required this.loading,
    required this.failed,
    required this.suggestions,
    required this.onSelect,
    required this.onCreate,
    required this.onRetry,
  });

  final String keyword;
  final bool loading;
  final bool failed;

  /// null = 还没有任何结果。
  final List<TopicSummary>? suggestions;
  final ValueChanged<TopicSummary> onSelect;
  final ValueChanged<String> onCreate;
  final VoidCallback onRetry;

  /// 行内边距：竖 8、横 20（与发布页内容边距对齐）。
  static const EdgeInsets rowPadding = EdgeInsets.symmetric(vertical: 8, horizontal: 20);

  /// 面板该不该出现：已拿到空结果、且关键词不是合法话题名（1 个字 / 纯数字 / 空）→ 不出现。
  static bool shouldShow({
    required String keyword,
    required bool loading,
    required bool failed,
    required List<TopicSummary>? suggestions,
  }) {
    if (loading || failed) return true;
    if (suggestions == null) return true;
    return suggestions.isNotEmpty || isValidTopicName(keyword);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final List<TopicSummary>? list = suggestions;
    final Widget content;
    if (failed) {
      content = _error(l10n);
    } else if (list == null || (loading && list.isEmpty)) {
      content = const Padding(
        key: ValueKey('topicSuggestLoading'),
        padding: EdgeInsets.all(12),
        // heightFactor: 1 —— 否则被外壳 Flexible 包住会撑满 220（V1.4.2 5.2 复审顺带修复）。
        child: Center(
          heightFactor: 1,
          child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );
    } else if (list.isEmpty) {
      content = _newRow(l10n);
    } else {
      content = ListView.builder(
        shrinkWrap: true,
        padding: EdgeInsets.zero,
        itemCount: list.length,
        itemBuilder: (context, i) => _row(list[i]),
      );
    }
    return KeyboardSuggestPanel(
      key: const ValueKey('topicSuggestPanel'),
      child: content,
    );
  }

  Widget _lead(IconData icon) => Container(
        width: 28,
        height: 28,
        alignment: Alignment.center,
        decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
        child: Icon(icon, size: 16, color: AppColors.mint),
      );

  Widget _row(TopicSummary t) => InkWell(
        key: ValueKey('topicSuggestion_${t.name}'),
        onTap: () => onSelect(t),
        child: Padding(
          padding: rowPadding,
          child: Row(
            children: [
              _lead(Icons.tag),
              const SizedBox(width: 8),
              Flexible(
                child: Text('#${t.displayName}',
                    style: AppTypography.body, maxLines: 1, overflow: TextOverflow.ellipsis),
              ),
              if (t.official) ...[
                const SizedBox(width: 6),
                const TopicOfficialBadge(),
              ],
            ],
          ),
        ),
      );

  Widget _newRow(AppLocalizations l10n) => InkWell(
        key: const ValueKey('topicSuggestNew'),
        onTap: () => onCreate(keyword),
        child: Padding(
          padding: rowPadding,
          child: Row(
            children: [
              _lead(Icons.add),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('#$keyword',
                        style: AppTypography.body, maxLines: 1, overflow: TextOverflow.ellipsis),
                    Text(l10n.topicNewLabel,
                        style: AppTypography.micro.copyWith(color: AppColors.muted)),
                  ],
                ),
              ),
            ],
          ),
        ),
      );

  Widget _error(AppLocalizations l10n) => Padding(
        key: const ValueKey('topicSuggestError'),
        padding: const EdgeInsets.all(12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(l10n.detailNetworkError,
                style: AppTypography.body.copyWith(color: AppColors.ink2), textAlign: TextAlign.center),
            TextButton.icon(
              key: const ValueKey('topicSuggestRetry'),
              onPressed: onRetry,
              style: TextButton.styleFrom(foregroundColor: AppColors.mint),
              icon: const Icon(Icons.refresh, size: 16),
              label: Text(l10n.commonRetry,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      );
}
