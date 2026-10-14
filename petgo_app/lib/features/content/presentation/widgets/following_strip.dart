import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/letter_avatar.dart';
import '../../../auth/domain/auth_state.dart';
import '../../../social/data/following_strip_repository.dart';
import '../../../social/domain/follow_list_item.dart';
import '../../../social/presentation/follow_list_page.dart';
import '../../../user_profile/presentation/public_profile_page.dart';
import '../author_moderation_callbacks.dart';

/// Following 页顶部的关注头像排（spec §2.2 · UI 稿 A2）。
///
/// 后端已排好序、最多 20 人；末尾固定一个「More」虚线圈 → 我的关注列表（Following Tab）。
/// 加载中 / 失败 / 空 → 整排不渲染（不占位）。外圈紫色纯装饰，不表示已读 / 新帖。
///
/// ⚠️ 只在**关注流有帖**时由 [FollowingTabView] 挂进瀑布流头部；无帖走空状态、不显示本组件。
class FollowingStrip extends ConsumerWidget {
  const FollowingStrip({super.key});

  static const double _avatar = 56;
  static const double _ring = 2.5;
  static const double _gap = 2.5;
  static const double _outer = _avatar + 2 * (_ring + _gap);
  static const double _itemWidth = 68;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final users = ref.watch(followingStripProvider).value ?? const <FollowingStripUser>[];
    if (users.isEmpty) return const SizedBox.shrink();
    final myId = ref.watch(authControllerProvider.select((s) => s.profile?.id));
    final l10n = AppLocalizations.of(context);
    return Column(
      key: const ValueKey('followingStrip'),
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          // 头像外圈 + 间距 + 一行昵称（按 1.3 倍字号上限留够高度，防溢出）。
          height: _outer + 8 + 24 + 16,
          child: ListView.separated(
            key: const ValueKey('followingStripList'),
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            itemCount: users.length + (myId == null ? 0 : 1),
            separatorBuilder: (_, _) => const SizedBox(width: 8),
            itemBuilder: (context, i) {
              if (i == users.length) {
                return _MoreItem(
                  label: l10n.followingStripMore,
                  onTap: () {
                    Analytics.capture('following_strip_tapped', {'target': 'more'});
                    context.push(FollowListPage.location(myId!, FollowListTab.following));
                  },
                );
              }
              final u = users[i];
              return _AvatarItem(
                user: u,
                fallbackName: l10n.feedDeletedUser,
                onTap: () => _openProfile(context, ref, u.userId),
              );
            },
          ),
        ),
        // 头像排与帖子流之间的浅色分隔带（UI 稿 A2）。
        Container(height: 8, color: AppColors.line2),
        const SizedBox(height: 8),
      ],
    );
  }

  void _openProfile(BuildContext context, WidgetRef ref, int userId) {
    Analytics.capture('following_strip_tapped', {'target': 'avatar'});
    // 主页里拉黑 / 举报此人 → 两条流里他的卡片立刻消失，头像排也重取（拉黑会解除关注）。
    // 🔴 先拿 container：回调触发时本组件可能已随列表变空而卸载。
    final container = ProviderScope.containerOf(context, listen: false);
    final hide = onAuthorHidden(ref, userId);
    void hideAndRefetch() {
      hide();
      container.invalidate(followingStripProvider);
    }

    openUserProfile(context, ref, userId, onBlocked: hideAndRefetch, onReported: hideAndRefetch);
  }
}

class _AvatarItem extends StatelessWidget {
  const _AvatarItem({required this.user, required this.fallbackName, required this.onTap});

  final FollowingStripUser user;
  final String fallbackName;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final name = (user.nickname == null || user.nickname!.trim().isEmpty) ? fallbackName : user.nickname!;
    return GestureDetector(
      key: ValueKey('followingStripAvatar_${user.userId}'),
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: SizedBox(
        width: FollowingStrip._itemWidth,
        child: Column(
          children: [
            Container(
              width: FollowingStrip._outer,
              height: FollowingStrip._outer,
              padding: const EdgeInsets.all(FollowingStrip._gap),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.mint, width: FollowingStrip._ring),
              ),
              child: LetterAvatar(url: user.avatarUrl, name: name, size: FollowingStrip._avatar),
            ),
            const SizedBox(height: 8),
            Text(
              name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.ink),
            ),
          ],
        ),
      ),
    );
  }
}

class _MoreItem extends StatelessWidget {
  const _MoreItem({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      key: const ValueKey('followingStripMore'),
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: SizedBox(
        width: FollowingStrip._itemWidth,
        child: Column(
          children: [
            SizedBox(
              width: FollowingStrip._outer,
              height: FollowingStrip._outer,
              child: CustomPaint(
                painter: _DashedCirclePainter(color: AppColors.muted),
                child: const Center(child: Icon(Icons.add_rounded, size: 26, color: AppColors.ink2)),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: AppColors.muted),
            ),
          ],
        ),
      ),
    );
  }
}

/// 虚线圆（UI 稿 A2「More」圈）。
class _DashedCirclePainter extends CustomPainter {
  _DashedCirclePainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final rect = Rect.fromCircle(center: size.center(Offset.zero), radius: size.width / 2 - 1);
    const dashCount = 32;
    const step = 2 * math.pi / dashCount;
    for (var i = 0; i < dashCount; i++) {
      canvas.drawArc(rect, step * i, step * 0.55, false, paint);
    }
  }

  @override
  bool shouldRepaint(_DashedCirclePainter oldDelegate) => oldDelegate.color != color;
}
