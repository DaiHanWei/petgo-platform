import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../data/notification_repository.dart';

/// 首页顶部通知铃铛 + 未读红色角标（Story 6.6 F1，FR-34）。
///
/// 角标读 `unreadCountProvider`（Redis 计数）：0 隐藏 / >0 显示红色数字。
/// **与问诊 Tab 红点并存不互斥**（FR-19）。点击进通知中心 `/notifications`。
class NotificationBell extends ConsumerWidget {
  const NotificationBell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unread = ref.watch(unreadCountProvider).maybeWhen(data: (c) => c, orElse: () => 0);
    return Stack(
      clipBehavior: Clip.none,
      children: [
        // 与「我的」页右上 ibtn 同款：38×38 白底圆角11 + 淡阴影（原型 .ibtn）。
        InkWell(
          key: const ValueKey('notificationBell'),
          onTap: () => context.push('/notifications'),
          borderRadius: BorderRadius.circular(11),
          child: Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(11),
              boxShadow: const [
                BoxShadow(color: Color(0x12162233), blurRadius: 8, offset: Offset(0, 2)),
              ],
            ),
            child: const Icon(Icons.notifications_outlined, size: 18, color: AppColors.ink2),
          ),
        ),
        if (unread > 0)
          Positioned(
            right: -2,
            top: -2,
            child: Container(
              key: const ValueKey('notificationBadge'),
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
              constraints: const BoxConstraints(minWidth: 16),
              decoration: BoxDecoration(
                color: AppColors.danger,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                unread > 99 ? '99+' : '$unread',
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
              ),
            ),
          ),
      ],
    );
  }
}
