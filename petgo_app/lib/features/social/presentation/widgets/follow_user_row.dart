import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/letter_avatar.dart';
import '../../../user_profile/presentation/public_profile_page.dart';
import '../../domain/follow_list_item.dart';
import 'follow_button.dart';

/// 关注类列表的一行（关注 / 粉丝 / 推荐关注共用）：头像 40 + 昵称 + 可选副标题 + [FollowButton]。
///
/// 注销行不可点、无按钮；自己 / 我拉黑的人无按钮（Story 2.2 AC4 / AC5）。
class FollowUserRow extends ConsumerWidget {
  const FollowUserRow({
    super.key,
    required this.item,
    required this.surfaceKey,
    required this.persistent,
    required this.source,
    this.subtitle,
    this.rowKey,
    this.filledFollow = false,
    this.verticalPadding = 12,
  });

  final FollowListItem item;
  final String surfaceKey;
  final bool persistent;
  final String source;

  /// 昵称下方小字（如「Anjing · 2 thn」）；null 则不画。
  final String? subtitle;

  /// 行外层 Key（页面各自约定，测试用）。
  final Key? rowKey;

  /// 「未关注」态用实心紫胶囊（Following 空状态推荐行）。
  final bool filledFollow;

  /// 行上下内边距。
  final double verticalPadding;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final name = item.deleted ? l10n.feedDeletedUser : (item.nickname ?? l10n.feedDeletedUser);
    final token = item.userToken;
    final nameText = Text(name,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: AppTypography.body.copyWith(fontWeight: FontWeight.w600));
    final identity = Row(
      children: [
        LetterAvatar(
            url: item.deleted ? null : item.avatarUrl,
            name: name,
            deleted: item.deleted,
            size: 40,
            growth: item.badge),
        const SizedBox(width: 12),
        Expanded(
          child: subtitle == null
              ? nameText
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    nameText,
                    const SizedBox(height: 2),
                    Text(subtitle!,
                        key: ValueKey('followRowSubtitle_${item.userId}'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                  ],
                ),
        ),
      ],
    );
    final showButton = !item.deleted && !item.self && !item.blockedByMe && token != null;
    return Padding(
      key: rowKey,
      padding: EdgeInsets.symmetric(vertical: verticalPadding),
      child: Row(
        children: [
          Expanded(
            child: item.deleted
                ? identity
                : GestureDetector(
                    key: ValueKey('followListOpen_${item.userId}'),
                    behavior: HitTestBehavior.opaque,
                    // T-21：from 记 avatar（PRD 定死三值，不新增）。
                    onTap: () => openUserProfile(context, ref, item.userId),
                    child: identity,
                  ),
          ),
          if (showButton) ...[
            const SizedBox(width: 8),
            FollowButton(
              userId: item.userId,
              userToken: token,
              initial: item.followState,
              snapshotEpoch: item.followEpoch,
              surfaceKey: surfaceKey,
              style: FollowButtonStyle.listCompact,
              persistent: persistent,
              filledIdle: filledFollow,
              source: source,
            ),
          ],
        ],
      ),
    );
  }
}
