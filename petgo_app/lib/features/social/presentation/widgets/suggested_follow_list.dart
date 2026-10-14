import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../profile/domain/pet_age.dart';
import '../../data/follow_suggestion_repository.dart';
import '../../domain/follow_list_item.dart';
import '../blocked_list_skeleton.dart';
import 'follow_user_row.dart';

/// 推荐关注：标题「Disarankan untukmu」+ 行列表（头像 / 昵称 /「物种 · 年龄」/ 关注按钮）。
///
/// 加载中 → 骨架；失败或空数组 → 整段隐藏（T-24）。推荐行属非常驻位置（AD-7）：
/// 点关注后按钮保留「Mengikuti」可再点取消，行不移除。设计为放进外层 ListView 的一个子项。
class SuggestedFollowList extends ConsumerStatefulWidget {
  const SuggestedFollowList({super.key, required this.source, this.surfaceScope = 'suggest', this.maxItems, this.emphasized = false});

  /// 关注按钮埋点 source（关注页为 `suggested`）。
  final String source;

  /// 行 surfaceKey 前缀；不同页面区分，避免同一用户的关注状态面互相串。
  final String surfaceScope;

  /// 最多显示几行；null = 全部（关注页行为不变）。Following 空状态传 5，让「Explore Discover」留在首屏附近。
  final int? maxItems;

  /// Following 空状态样式（UI 稿 A3）：句首大写粗体标题 + 实心紫 Follow 胶囊 + 行间无分隔线。
  final bool emphasized;

  @override
  ConsumerState<SuggestedFollowList> createState() => _SuggestedFollowListState();
}

class _SuggestedFollowListState extends ConsumerState<SuggestedFollowList> {
  bool _shownReported = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final suggestions = ref.watch(followSuggestionsProvider);
    // Riverpod 3 的 AsyncError 会带着上一次的值 —— 刷新失败时不能继续显示旧推荐（T-24 失败即隐藏）。
    final all = suggestions.hasError ? const <FollowListItem>[] : (suggestions.value ?? const <FollowListItem>[]);
    final cap = widget.maxItems;
    final items = (cap != null && all.length > cap) ? all.sublist(0, cap) : all;
    if (!_shownReported && items.isNotEmpty) {
      _shownReported = true;
      Analytics.capture('suggested_follow_shown');
    }
    if (suggestions.isLoading && !suggestions.hasValue) {
      return const SizedBox(
        key: ValueKey('followSuggestLoading'),
        height: 320,
        child: BlockedListSkeleton(),
      );
    }
    if (items.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsets.only(top: 16, bottom: widget.emphasized ? 4 : 2),
          child: Text(
            widget.emphasized ? l10n.followSuggestedTitle : l10n.followSuggestedTitle.toUpperCase(),
            key: const ValueKey('followSuggestTitle'),
            style: widget.emphasized
                ? const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)
                : const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.6,
                    color: AppColors.muted,
                  ),
          ),
        ),
        for (var i = 0; i < items.length; i++) ...[
          if (i > 0 && !widget.emphasized) const Divider(height: 1, thickness: 1, color: AppColors.line2),
          FollowUserRow(
            item: items[i],
            rowKey: ValueKey('followSuggestRow_${items[i].userId}'),
            surfaceKey: '${widget.surfaceScope}:${items[i].userId}',
            persistent: false,
            filledFollow: widget.emphasized,
            verticalPadding: widget.emphasized ? 14 : 12,
            source: widget.source,
            subtitle: _subtitle(l10n, items[i]),
          ),
        ],
      ],
    );
  }

  /// 「物种 · 年龄」；两项都缺 → null（不画副标题）。
  static String? _subtitle(AppLocalizations l10n, FollowListItem item) {
    final parts = [
      ?petSpeciesLabel(l10n, item.petType),
      ?formatPetAge(l10n, item.petBirthday),
    ];
    return parts.isEmpty ? null : parts.join(' · ');
  }
}
