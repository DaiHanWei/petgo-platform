import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/deep_link_routes.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/notification_repository.dart';
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
    final token = item.deepLinkToken;
    if (token != null) {
      try {
        await ref.read(notificationRepositoryProvider).markRead(token);
        ref.invalidate(unreadCountProvider); // 角标刷新
      } catch (_) {
        // 标记失败不阻断跳转。
      }
    }
    if (!mounted) return;
    final location = DeepLinkRoutes.pushPayloadToLocation(
      item.deepLinkType, token,
      commentAnchor: item.deepLinkType == 'CONTENT_COMMENTED',
    );
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
      appBar: AppBar(title: Text(l10n.notificationCenterTitle)),
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
          return ListView.separated(
            itemCount: items.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (ctx, i) => _NotificationTile(item: items[i], onTap: () => _onTap(items[i])),
          );
        },
      ),
    );
  }
}

class _NotificationTile extends StatelessWidget {
  const _NotificationTile({required this.item, required this.onTap});

  final NotificationItem item;
  final VoidCallback onTap;

  IconData get _icon => switch (item.type) {
        'VET_REPLY' || 'CONSULT_CLOSED' => Icons.medical_services_outlined,
        'CONTENT_LIKED' => Icons.favorite_border,
        'CONTENT_COMMENTED' => Icons.mode_comment_outlined,
        'NEW_CONSULT_REQUEST' => Icons.inbox_outlined,
        // 🔄 PRD V1.0.0 修订（F2）：定时系统推送三类。
        'PET_BIRTHDAY' => Icons.cake_outlined,
        'COMPANION_ANNIVERSARY' => Icons.celebration_outlined,
        'MILESTONE_NODE' => Icons.flag_outlined,
        _ => Icons.notifications_outlined,
      };

  String _typeLabel(AppLocalizations l10n) => switch (item.type) {
        'VET_REPLY' => l10n.notifyTypeVetReply,
        'CONSULT_CLOSED' => l10n.notifyTypeConsultClosed,
        'CONTENT_LIKED' => l10n.notifyTypeContentLiked,
        'CONTENT_COMMENTED' => l10n.notifyTypeContentCommented,
        'NEW_CONSULT_REQUEST' => l10n.notifyTypeNewRequest,
        // 🔄 PRD V1.0.0 修订（F2）：定时系统推送三类。
        'PET_BIRTHDAY' => l10n.notifyTypePetBirthday,
        'COMPANION_ANNIVERSARY' => l10n.notifyTypeCompanionAnniversary,
        'MILESTONE_NODE' => l10n.notifyTypeMilestoneNode,
        _ => l10n.notificationCenterTitle,
      };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      key: ValueKey('notification_${item.deepLinkToken ?? item.type}'),
      leading: Icon(_icon, color: AppColors.accentConsult),
      // 未读高亮（加粗 + 圆点）。
      title: Text(item.title ?? _typeLabel(l10n),
          style: item.read ? AppTypography.body : AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
      subtitle: item.body == null ? null : Text(item.body!, maxLines: 2, overflow: TextOverflow.ellipsis),
      trailing: item.read
          ? null
          : Container(width: 8, height: 8,
              decoration: const BoxDecoration(color: AppColors.danger, shape: BoxShape.circle)),
      onTap: onTap,
    );
  }
}
