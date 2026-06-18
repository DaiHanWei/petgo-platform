import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/deep_link_routes.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/notification_repository.dart';
import '../domain/notification_deep_link.dart';
import '../domain/notification_item.dart';

/// 通知中心列表页（Story 6.6 F2/F3，FR-34）。倒序六(~七)类 + 空态 + 点击标记已读并深链跳目标。
/// 🔄 PRD V1.0.0 修订（F2 · 2026-06-08）：展示类型由四类扩到六(~七)类（加生日/纪念日/里程碑节点）。
///
/// 亦是 6.1 深链未知/兜底的落地页（`/notifications`）。
class NotificationCenterPage extends ConsumerStatefulWidget {
  const NotificationCenterPage({super.key});

  @override
  ConsumerState<NotificationCenterPage> createState() => _NotificationCenterPageState();
}

class _NotificationCenterPageState extends ConsumerState<NotificationCenterPage> {
  late Future<NotificationPage> _page;

  @override
  void initState() {
    super.initState();
    _page = ref.read(notificationRepositoryProvider).list();
  }

  Future<void> _onTap(NotificationItem item) async {
    // 列表点击与系统推送直跳共用 NotificationDeepLink.open（标记已读 + 角标重算 + 算 location）。
    final location = await NotificationDeepLink.open(
      ref,
      type: item.deepLinkType,
      token: item.deepLinkToken,
      commentAnchor: item.deepLinkType == 'CONTENT_COMMENTED',
    );
    if (!mounted) return;
    // 兜底落点是本页自身时不重复 push。
    if (location != DeepLinkRoutes.notificationsCenter) {
      context.push(location);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        scrolledUnderElevation: 0,
        titleSpacing: 20,
        title: Text(l10n.notificationCenterTitle,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
      body: FutureBuilder<NotificationPage>(
        future: _page,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          final items = snapshot.data?.items ?? const <NotificationItem>[];
          if (items.isEmpty) {
            return EmptyState(
              key: const ValueKey('notificationEmpty'),
              icon: Icons.notifications_none,
              title: l10n.notificationEmpty,
            );
          }
          // 按时间分组：今天 → HARI INI；其余 → KEMARIN（notif.html）。
          final now = DateTime.now();
          bool isToday(NotificationItem it) {
            final d = it.createdAt;
            if (d == null) return true;
            return d.year == now.year && d.month == now.month && d.day == now.day;
          }
          final today = items.where(isToday).toList();
          final earlier = items.where((it) => !isToday(it)).toList();
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 20),
            children: [
              if (today.isNotEmpty) ...[
                _groupLabel('HARI INI'),
                for (final it in today) _NotificationTile(item: it, onTap: () => _onTap(it)),
              ],
              if (earlier.isNotEmpty) ...[
                const SizedBox(height: 8),
                _groupLabel('KEMARIN'),
                for (final it in earlier) _NotificationTile(item: it, onTap: () => _onTap(it)),
              ],
            ],
          );
        },
      ),
    );
  }

  Widget _groupLabel(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 10, 4, 8),
        child: Text(text,
            style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.6,
                color: AppColors.muted)),
      );
}

class _NotificationTile extends StatelessWidget {
  const _NotificationTile({required this.item, required this.onTap});

  final NotificationItem item;
  final VoidCallback onTap;

  /// 圆角方形彩色图标块配色（按 type）：兽医薄荷 / 点赞红 / 评论紫 / 里程碑绿 / 生日金。
  (IconData, Color, Color) get _iconStyle => switch (item.type) {
        'VET_REPLY' || 'CONSULT_CLOSED' =>
          (Icons.medical_services_rounded, AppColors.mint, AppColors.cream2),
        'CONTENT_LIKED' => (Icons.favorite_rounded, AppColors.coral, AppColors.coralTint),
        'CONTENT_COMMENTED' =>
          (Icons.mode_comment_rounded, AppColors.mint, AppColors.cream2),
        'NEW_CONSULT_REQUEST' => (Icons.inbox_rounded, AppColors.mint, AppColors.cream2),
        'PET_BIRTHDAY' => (Icons.cake_rounded, AppColors.gold, AppColors.goldTint),
        'COMPANION_ANNIVERSARY' =>
          (Icons.celebration_rounded, AppColors.gold, AppColors.goldTint),
        'MILESTONE_NODE' =>
          (Icons.emoji_events_rounded, AppColors.triageGreen, AppColors.momenBadgeBg),
        _ => (Icons.notifications_rounded, AppColors.mint, AppColors.cream2),
      };

  String _typeLabel(AppLocalizations l10n) => switch (item.type) {
        'VET_REPLY' => l10n.notifyTypeVetReply,
        'CONSULT_CLOSED' => l10n.notifyTypeConsultClosed,
        'CONTENT_LIKED' => l10n.notifyTypeContentLiked,
        'CONTENT_COMMENTED' => l10n.notifyTypeContentCommented,
        'NEW_CONSULT_REQUEST' => l10n.notifyTypeNewRequest,
        'PET_BIRTHDAY' => l10n.notifyTypePetBirthday,
        'COMPANION_ANNIVERSARY' => l10n.notifyTypeCompanionAnniversary,
        'MILESTONE_NODE' => l10n.notifyTypeMilestoneNode,
        _ => l10n.notificationCenterTitle,
      };

  /// 印尼语相对时间（notif.html：5 menit lalu / 1 jam lalu / Kemarin HH:mm）。
  String _relativeTime() {
    final d = item.createdAt;
    if (d == null) return '';
    final now = DateTime.now();
    final diff = now.difference(d);
    final isToday = d.year == now.year && d.month == now.month && d.day == now.day;
    if (!isToday) {
      return 'Kemarin ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
    }
    if (diff.inMinutes < 1) return 'Baru saja';
    if (diff.inHours < 1) return '${diff.inMinutes} menit lalu';
    return '${diff.inHours} jam lalu';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (icon, fg, bg) = _iconStyle;
    final unread = !item.read;
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Material(
        color: unread ? AppColors.cream2 : AppColors.card,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          key: ValueKey('notification_${item.deepLinkToken ?? item.type}'),
          borderRadius: BorderRadius.circular(14),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 圆角方形彩色图标块。
                Container(
                  width: 40,
                  height: 40,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(11)),
                  child: Icon(icon, size: 20, color: fg),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(item.title ?? _typeLabel(l10n),
                          style: TextStyle(
                              fontSize: 13.5,
                              fontWeight: FontWeight.w700,
                              color: AppColors.ink)),
                      if (item.body != null) ...[
                        const SizedBox(height: 3),
                        Text(item.body!,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                                fontSize: 12, height: 1.45, color: AppColors.ink2)),
                      ],
                      const SizedBox(height: 5),
                      Text(_relativeTime(),
                          style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    ],
                  ),
                ),
                // 未读紫点。
                if (unread) ...[
                  const SizedBox(width: 8),
                  Container(
                    width: 8,
                    height: 8,
                    margin: const EdgeInsets.only(top: 4),
                    decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
