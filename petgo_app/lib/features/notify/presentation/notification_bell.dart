import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../shared/widgets/count_badge.dart';
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
            // 样式已抽到 CountBadge（V1.3.0 Story 1.5），里程碑未庆祝角标复用同一份 ——
            // 抽出前它不是可复用单位，第二处只能照着再画一个，两份手抄迟早走散。
            // **视觉零变化**，只是不再内联那段 Container。
            child: CountBadge(key: const ValueKey('notificationBadge'), count: unread),
          ),
      ],
    );
  }
}
