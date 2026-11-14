import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/deep_link_routes.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../profile/data/current_pet_provider.dart';
import '../data/health_notification_repository.dart';
import '../domain/health_reminder_attribution.dart';
import '../domain/health_reminder_open.dart';

/// 健康提醒点开路由页 `/health-reminder/open?t=<token>&src=push|inbox`（V1.5.0 Story 3.4，架构增量 §5.4 / §7）。
///
/// 推送点击（冷启动 / 后台 / 前台都经 `PushService._onNotificationClicked` → `pushClickLocation`）与通知中心点击
/// 共用这一个入口：显示加载态 → 调点开接口 → 按结果切宠物并 **replace** 到健康记录页（返回键回到点击前的页面），
/// 或落通知中心。
class HealthReminderOpenPage extends ConsumerStatefulWidget {
  const HealthReminderOpenPage({super.key, required this.token, this.source = 'inbox'});

  final String token;

  /// `push` / `inbox`。
  final String source;

  static const String location = DeepLinkRoutes.healthReminderOpen;

  @override
  ConsumerState<HealthReminderOpenPage> createState() => _HealthReminderOpenPageState();
}

class _HealthReminderOpenPageState extends ConsumerState<HealthReminderOpenPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _open());
  }

  String get _source => widget.source == 'push' ? 'push' : 'inbox';

  Future<void> _open() async {
    if (widget.token.isEmpty) {
      _fallback();
      return;
    }
    HealthReminderOpenResult result;
    try {
      result = await ref.read(healthNotificationRepositoryProvider).open(widget.token, source: _source);
    } catch (_) {
      // 接口失败 / 404 → 通知中心，无 toast（AC2.2）。拿不到 analytics 对象 → 不报 opened。
      _fallback();
      return;
    }
    if (!mounted) return;
    if (!result.isOk) {
      _report(result, 'inbox_fallback');
      _fallback(toast: AppLocalizations.of(context).healthReminderPetUnavailable);
      return;
    }
    final petId = result.petId;
    if (petId != null) {
      final current = ref.read(effectivePetIdProvider).value;
      if (current != petId) {
        // 埋点只带 id 与来源枚举，不带宠物名（同 pet_switcher）。
        Analytics.capture('pet_switched', {
          'from_pet_id': ?current,
          'to_pet_id': petId,
          'source': 'health_reminder',
        });
        ref.read(currentPetIdProvider.notifier).select(petId);
      }
    }
    _report(result, healthReminderRouteValue(result));
    final a = result.analytics;
    if (petId != null && a != null && a.notificationId.isNotEmpty) {
      ref.read(healthReminderAttributionProvider.notifier).recordOpened(petId, a.notificationId, a.notificationKind);
    }
    if (!mounted) return;
    context.pushReplacement(healthReminderTargetLocation(result, widget.token));
  }

  /// `health_notification_opened`（PRD §4）：`source=push` 每个通知最多一次，`source=inbox` 每次都报。
  void _report(HealthReminderOpenResult r, String route) {
    final a = r.analytics;
    if (a == null) return;
    if (_source == 'push' && !ref.read(healthReminderAttributionProvider.notifier).claimPushReport(widget.token)) {
      return;
    }
    Analytics.capture('health_notification_opened', {
      'notification_id': a.notificationId,
      'pet_id': ?r.petId,
      'notification_kind': ?a.notificationKind,
      'push_point': ?a.pushPoint,
      'origin': ?a.origin,
      'merged_count': a.mergedCount,
      'source': _source,
      'route': route,
    });
  }

  void _fallback({String? toast}) {
    if (!mounted) return;
    // 先取根 Overlay：go 之后本页被移除，本页 context 不能再用。
    final overlay = Overlay.maybeOf(context, rootOverlay: true);
    context.go(DeepLinkRoutes.notificationsCenter);
    if (toast != null && overlay != null && overlay.mounted) showAppToastOnOverlay(overlay, toast);
  }

  @override
  Widget build(BuildContext context) => const Scaffold(
        backgroundColor: AppColors.cream2,
        body: Center(child: CircularProgressIndicator(key: ValueKey('healthReminderOpenLoading'))),
      );
}
