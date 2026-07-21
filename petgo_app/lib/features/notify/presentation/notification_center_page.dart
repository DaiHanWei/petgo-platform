import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

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
  ConsumerState<NotificationCenterPage> createState() =>
      _NotificationCenterPageState();
}

class _NotificationCenterPageState
    extends ConsumerState<NotificationCenterPage> {
  late Future<NotificationPage> _page;
  final Set<String> _locallyReadTokens = <String>{};

  @override
  void initState() {
    super.initState();
    _page = ref.read(notificationRepositoryProvider).list();
    // list() 服务端按 DB 真实未读校准 Redis 角标（自愈计数漂移：角标>0 但列表空等）；
    // 拉完刷新铃铛未读角标使其立即与真实一致。
    _page.whenComplete(() {
      if (mounted) ref.invalidate(unreadCountProvider);
    });
  }

  /// 重拉列表（bug 20260625-088：加载失败态的重试）。
  void _reload() {
    setState(() {
      _page = ref.read(notificationRepositoryProvider).list();
      _page.whenComplete(() {
        if (mounted) ref.invalidate(unreadCountProvider);
      });
    });
  }

  Future<void> _onTap(NotificationItem item) async {
    final token = item.deepLinkToken;
    if (token != null && token.isNotEmpty && !item.read) {
      setState(() {
        _locallyReadTokens.add(token);
      });
    }
    // 列表点击与系统推送直跳共用 NotificationDeepLink.open（标记已读 + 角标重算 + 算 location）。
    final location = await NotificationDeepLink.open(
      ref,
      type: item.deepLinkType,
      token: token,
      targetRef: item.targetRef,
      commentAnchor: item.deepLinkType == 'CONTENT_COMMENTED',
    );
    if (!mounted) return;
    // 兜底落点是本页自身时不重复 push。
    if (location != DeepLinkRoutes.notificationsCenter) {
      context.push(location);
    }
  }

  bool _isRead(NotificationItem item) {
    final token = item.deepLinkToken;
    return item.read || (token != null && _locallyReadTokens.contains(token));
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
        title: Text(
          l10n.notificationCenterTitle,
          style: const TextStyle(
            fontSize: 19,
            fontWeight: FontWeight.w700,
            color: AppColors.ink,
          ),
        ),
      ),
      body: FutureBuilder<NotificationPage>(
        future: _page,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          // bug 20260625-088：加载失败必须显式报错 + 重试，**绝不**把请求错误静默画成空态
          // （401/500/超时都会走到这里；此前无此分支 → 一律显示「暂无通知」误导用户）。
          if (snapshot.hasError) {
            return _errorState(l10n);
          }
          final items = snapshot.data?.items ?? const <NotificationItem>[];
          if (items.isEmpty) {
            return EmptyState(
              key: const ValueKey('notificationEmpty'),
              icon: Icons.notifications_none,
              iconBackground: AppColors.cream2, // 浅紫圆底盘（原型 notif-empty）
              title: l10n.notificationEmpty,
              message: l10n.notificationEmptyHint,
            );
          }
          return _notificationList(l10n, items);
        },
      ),
    );
  }

  Widget _notificationList(
    AppLocalizations l10n,
    List<NotificationItem> items,
  ) {
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
          _groupLabel(l10n.notifyGroupToday),
          for (final it in today)
            _NotificationTile(
              item: it,
              read: _isRead(it),
              onTap: () => _onTap(it),
            ),
        ],
        if (earlier.isNotEmpty) ...[
          const SizedBox(height: 8),
          _groupLabel(l10n.notifyGroupEarlier),
          for (final it in earlier)
            _NotificationTile(
              item: it,
              read: _isRead(it),
              onTap: () => _onTap(it),
            ),
        ],
      ],
    );
  }

  /// 加载失败态（bug 20260625-088）：显式错误 + 重试按钮，区别于真·空态。
  Widget _errorState(AppLocalizations l10n) => Center(
    child: Column(
      key: const ValueKey('notificationError'),
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(
          Icons.cloud_off_outlined,
          size: 48,
          color: AppColors.textTertiary,
        ),
        const SizedBox(height: 12),
        Text(
          l10n.notificationLoadFailed,
          style: const TextStyle(fontSize: 14, color: AppColors.ink2),
        ),
        const SizedBox(height: 16),
        FilledButton(
          key: const ValueKey('notificationRetry'),
          onPressed: _reload,
          child: Text(l10n.notificationLoadRetry),
        ),
      ],
    ),
  );

  Widget _groupLabel(String text) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 10, 4, 8),
    child: Text(
      text,
      style: const TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.6,
        color: AppColors.muted,
      ),
    ),
  );
}

class _NotificationTile extends StatefulWidget {
  const _NotificationTile({
    required this.item,
    required this.read,
    required this.onTap,
  });

  final NotificationItem item;
  final bool read;
  final VoidCallback onTap;

  @override
  State<_NotificationTile> createState() => _NotificationTileState();
}

class _NotificationTileState extends State<_NotificationTile> {
  bool _expanded = false;

  /// 圆角方形彩色图标块配色（按 type）：兽医薄荷 / 点赞红 / 评论紫 / 里程碑绿 / 生日金。
  (IconData, Color, Color) get _iconStyle => switch (widget.item.type) {
    'VET_REPLY' || 'CONSULT_CLOSED' => (
      Icons.medical_services_rounded,
      AppColors.mint,
      AppColors.cream2,
    ),
    'CONTENT_LIKED' => (
      Icons.favorite_rounded,
      AppColors.coral,
      AppColors.coralTint,
    ),
    'CONTENT_COMMENTED' => (
      Icons.mode_comment_rounded,
      AppColors.mint,
      AppColors.cream2,
    ),
    'NEW_CONSULT_REQUEST' => (
      Icons.inbox_rounded,
      AppColors.mint,
      AppColors.cream2,
    ),
    'PET_BIRTHDAY' => (Icons.cake_rounded, AppColors.gold, AppColors.goldTint),
    'COMPANION_ANNIVERSARY' => (
      Icons.celebration_rounded,
      AppColors.gold,
      AppColors.goldTint,
    ),
    'MILESTONE_NODE' => (
      Icons.emoji_events_rounded,
      AppColors.triageGreen,
      AppColors.momenBadgeBg,
    ),
    _ => (Icons.notifications_rounded, AppColors.mint, AppColors.cream2),
  };

  String _typeLabel(AppLocalizations l10n) => switch (widget.item.type) {
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

  /// 副标题（按 type 本地化，随 App 语言）。
  String _typeBody(AppLocalizations l10n) => switch (widget.item.type) {
    'VET_REPLY' => l10n.notifyBodyVetReply,
    'CONSULT_CLOSED' => l10n.notifyBodyConsultClosed,
    'CONTENT_LIKED' => l10n.notifyBodyContentLiked,
    'CONTENT_COMMENTED' => l10n.notifyBodyContentCommented,
    'NEW_CONSULT_REQUEST' => l10n.notifyBodyNewRequest,
    'PET_BIRTHDAY' => l10n.notifyBodyPetBirthday,
    'COMPANION_ANNIVERSARY' => l10n.notifyBodyCompanionAnniversary,
    'MILESTONE_NODE' => l10n.notifyBodyMilestoneNode,
    _ => l10n.notificationEmptyHint,
  };

  /// 相对时间，随 App 语言本地化（今天：刚刚 / N 分钟前 / N 小时前；更早：本地化日期）。
  String _relativeTime(AppLocalizations l10n, String locale) {
    final d = widget.item.createdAt;
    if (d == null) return '';
    final now = DateTime.now();
    final diff = now.difference(d);
    final isToday =
        d.year == now.year && d.month == now.month && d.day == now.day;
    if (!isToday) {
      return DateFormat('d MMM, HH:mm', locale).format(d);
    }
    if (diff.inMinutes < 1) return l10n.notifyTimeJustNow;
    if (diff.inHours < 1) return l10n.notifyTimeMinutesAgo(diff.inMinutes);
    return l10n.notifyTimeHoursAgo(diff.inHours);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (icon, fg, bg) = _iconStyle;
    final unread = !widget.read;
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Material(
        color: unread ? AppColors.cream2 : AppColors.card,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          key: ValueKey(
            'notification_${widget.item.deepLinkToken ?? widget.item.type}',
          ),
          borderRadius: BorderRadius.circular(14),
          onTap: widget.onTap,
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
                  decoration: BoxDecoration(
                    color: bg,
                    borderRadius: BorderRadius.circular(11),
                  ),
                  child: Icon(icon, size: 20, color: fg),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 文案按 type 本地化，随 App 语言渲染；**不渲染后端 title/body**（后端串为服务端语言）。
                      Text(
                        _typeLabel(l10n),
                        style: const TextStyle(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        _typeBody(l10n),
                        maxLines: _expanded ? null : 2,
                        overflow: _expanded
                            ? TextOverflow.visible
                            : TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 12,
                          height: 1.45,
                          color: AppColors.ink2,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Text(
                              _relativeTime(
                                l10n,
                                Localizations.localeOf(context).toString(),
                              ),
                              style: const TextStyle(
                                fontSize: 11,
                                color: AppColors.muted,
                              ),
                            ),
                          ),
                          TextButton(
                            style: TextButton.styleFrom(
                              minimumSize: Size.zero,
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                                vertical: 4,
                              ),
                              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                              visualDensity: VisualDensity.compact,
                            ),
                            onPressed: () {
                              setState(() {
                                _expanded = !_expanded;
                              });
                            },
                            child: Text(
                              _expanded
                                  ? l10n.notificationCollapse
                                  : l10n.notificationExpand,
                              style: const TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ],
                      ),
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
                    decoration: const BoxDecoration(
                      color: AppColors.mint,
                      shape: BoxShape.circle,
                    ),
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
