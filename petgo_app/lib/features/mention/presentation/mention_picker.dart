import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../data/mention_candidate_repository.dart';

/// @ 用户选择器浮层（V1.3.0 batch-b1 Story 3.2 · AC1/AC2/AC3）。
///
/// <h3>🔴 这不是搜索框</h3>
/// 里面那个输入框**只在候选集这批人之内过滤**（AC2），没有全局用户搜索
/// （留在 1.6.0，未前移 —— AD-10 Rule 3）。所以：
/// <ul>
///   <li>占位文案是「找名字」而不是「搜索用户」；</li>
///   <li>没有放大镜图标 —— 放大镜就是"能搜到任何人"的视觉承诺；</li>
///   <li>过滤不出结果时说的是「没有匹配的名字」，不是「没有找到该用户」。</li>
/// </ul>
/// ⚠️ 长得像搜索框、文案写"搜索用户"，用户会期待搜到任何人，而实际只能搜 30 个 ——
/// 这比没有更糟。
///
/// <h3>AC3：候选为空时**不显示过滤输入框**</h3>
/// 一个搜不出任何东西的输入框只会让人一直敲。空态直接说清楚"先去互动"。
class MentionPicker extends ConsumerStatefulWidget {
  const MentionPicker({
    super.key,
    required this.keyword,
    required this.onSelected,
    this.maxHeight = 220,
  });

  /// 用户在正文里 `@` 后面已经打出来的字（来自 `MentionDraft.queryAt`）。
  ///
  /// 它与浮层内那个过滤框是**同一个过滤条件的两个入口**：正文里接着打字能筛，
  /// 嫌在正文里打字别扭的人也可以在浮层里打。
  final String keyword;

  /// 选中一个人。调用方负责把 `@昵称` 插进文本并记下 userId（见 `MentionDraft.insert`）。
  final void Function(MentionCandidate candidate) onSelected;

  final double maxHeight;

  @override
  ConsumerState<MentionPicker> createState() => _MentionPickerState();
}

class _MentionPickerState extends ConsumerState<MentionPicker> {
  final TextEditingController _filter = TextEditingController();

  @override
  void dispose() {
    _filter.dispose();
    super.dispose();
  }

  /// 正文里打的字与浮层里打的字**取并集过滤**（谁有值用谁；都有值则都要满足）。
  bool _matches(MentionCandidate c) {
    final String nickname = c.nickname.toLowerCase();
    for (final String word in <String>[widget.keyword, _filter.text]) {
      final String w = word.trim().toLowerCase();
      if (w.isNotEmpty && !nickname.contains(w)) return false;
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final candidates = ref.watch(mentionCandidatesProvider);
    // 取数失败与"一个人都没有"走**同一个空态**：候选集取不到时用户能做的事
    // 与没互动过的人完全一样（去互动 / 手打名字），没必要多一种说法。
    final List<MentionCandidate> all = candidates.value ?? const <MentionCandidate>[];
    final bool loading = candidates.isLoading && !candidates.hasValue;
    final List<MentionCandidate> shown = all.where(_matches).toList(growable: false);

    return Container(
      key: const ValueKey('mentionPicker'),
      constraints: BoxConstraints(maxHeight: widget.maxHeight),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: AppRounded.lgRadius,
        border: Border.all(color: AppColors.border),
      ),
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // AC3：候选为空（含还在加载）→ **不显示过滤输入框**。
          if (all.isNotEmpty) _filterField(l10n),
          if (loading)
            const Padding(
              padding: EdgeInsets.all(AppSpacing.md),
              child: Center(
                child: SizedBox(
                    width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              ),
            )
          else if (all.isEmpty)
            _emptyState(l10n.mentionEmptyTitle, l10n.mentionEmptyHint)
          else if (shown.isEmpty)
            // 过滤不出来 ≠ 没有候选：这里说的是"这批人里没有叫这个的"，
            // ⚠️ 不能说成"没有找到该用户"（那是在承诺能搜到任何人）。
            _emptyState(l10n.mentionNoMatch, null)
          else
            Flexible(
              child: ListView.builder(
                shrinkWrap: true,
                padding: EdgeInsets.zero,
                itemCount: shown.length,
                itemBuilder: (context, i) => _row(shown[i]),
              ),
            ),
        ],
      ),
    );
  }

  Widget _filterField(AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.xs, AppSpacing.md, AppSpacing.xs),
      child: TextField(
        key: const ValueKey('mentionFilterInput'),
        controller: _filter,
        onChanged: (_) => setState(() {}),
        style: AppTypography.body,
        decoration: InputDecoration(
          // ⚠️ 占位是「找名字」，**不是**「搜索用户」；也刻意不放放大镜图标。
          hintText: l10n.mentionFilterHint,
          isDense: true,
          filled: true,
          fillColor: AppColors.cream2,
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(999),
            borderSide: BorderSide.none,
          ),
        ),
      ),
    );
  }

  Widget _emptyState(String title, String? hint) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.md),
      child: Column(
        key: const ValueKey('mentionPickerEmpty'),
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(title, style: AppTypography.caption, textAlign: TextAlign.center),
          if (hint != null) ...[
            const SizedBox(height: AppSpacing.xs),
            Text(hint, style: AppTypography.micro, textAlign: TextAlign.center),
          ],
        ],
      ),
    );
  }

  Widget _row(MentionCandidate c) {
    return InkWell(
      key: ValueKey('mentionCandidate_${c.userId}'),
      onTap: () => widget.onSelected(c),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
        child: Row(
          children: [
            InitialAvatar(nickname: c.nickname, avatarUrl: c.avatarUrl, radius: 14),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(c.nickname, style: AppTypography.body, overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
      ),
    );
  }
}
