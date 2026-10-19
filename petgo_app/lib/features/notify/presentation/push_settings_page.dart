import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/push/push_service.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../auth/domain/auth_state.dart';
import '../data/push_permission_providers.dart';
import '../data/push_settings_repository.dart';
import '../domain/push_permission_change_reporter.dart';
import '../domain/push_settings.dart';

/// 通知设置页（V1.4.0 ④ Story 1.3 · AD-14 · UI 稿 B1–B6）。
///
/// 顶部系统权限状态卡 + 4 组 9 个推送分类开关。切换即保存（乐观更新、失败回弹 + Toast）；
/// 「订单与账号」恒开、无开关。只管系统推送 —— 关掉的类型仍进通知中心。
/// 视觉照抄设置页同一套常量（不把设置页私有方法搬成公共组件，避免顺手改动设置页）。
class PushSettingsPage extends ConsumerStatefulWidget {
  const PushSettingsPage({
    super.key,
    this.isNotificationGrantedForTest,
    this.openSettingsForTest,
    this.requestPermissionForTest,
  });

  static const routePath = '/me/settings/notifications';

  /// 仅供测试注入（widget test 里没有权限插件的平台实现）。
  final Future<bool> Function()? isNotificationGrantedForTest;
  final Future<bool> Function()? openSettingsForTest;
  final Future<bool> Function()? requestPermissionForTest;

  @override
  ConsumerState<PushSettingsPage> createState() => _PushSettingsPageState();
}

/// 一行分类：code → 文案键 / 图标 / 前景色 / 圆底色（顺序 = AD-1）。
class _Row {
  const _Row(this.code, this.icon, this.fg, this.bg);

  final String code;
  final IconData icon;
  final Color fg;
  final Color bg;
}

class _Group {
  const _Group(this.title, this.rows);

  final String Function(AppLocalizations) title;
  final List<_Row> rows;
}

/// UI 稿 `.lk2`（锁定行图标圆底 / 胶囊底）。
const Color _lockedBg = Color(0xFFF4F3F7);

/// UI 稿 `.sk`（骨架占位色）。
const Color _skeleton = Color(0xFFF1EEF7);

final List<_Group> _groups = [
  _Group((l) => l.pushSettingsGroupInteraction, const [
    _Row(PushCategoryCode.like, Icons.favorite_rounded, AppColors.coral, AppColors.coralTint),
    _Row(PushCategoryCode.comment, Icons.mode_comment_rounded, AppColors.mint, AppColors.cream2),
    _Row(PushCategoryCode.mention, Icons.alternate_email_rounded, AppColors.mint, AppColors.cream2),
    _Row(PushCategoryCode.follower, Icons.person_add_rounded, AppColors.mint, AppColors.cream2),
    _Row(PushCategoryCode.gift, Icons.redeem_rounded, AppColors.gold, AppColors.goldTint),
  ]),
  _Group((l) => l.pushSettingsGroupPet, const [
    _Row(PushCategoryCode.petReminder, Icons.cake_rounded, AppColors.gold, AppColors.goldTint),
    _Row(PushCategoryCode.achievement, Icons.emoji_events_rounded, AppColors.triageGreen, AppColors.momenBadgeBg),
  ]),
  _Group((l) => l.pushSettingsGroupPromo, const [
    _Row(PushCategoryCode.promo, Icons.campaign_rounded, AppColors.mint, AppColors.cream2),
  ]),
  _Group((l) => l.pushSettingsGroupAlways, const [
    _Row(PushCategoryCode.orderAccount, Icons.receipt_long_rounded, AppColors.ink2, _lockedBg),
  ]),
];

(String, String) _copy(AppLocalizations l, String code) => switch (code) {
      PushCategoryCode.like => (l.pushSettingsLikeTitle, l.pushSettingsLikeDesc),
      PushCategoryCode.comment => (l.pushSettingsCommentTitle, l.pushSettingsCommentDesc),
      PushCategoryCode.mention => (l.pushSettingsMentionTitle, l.pushSettingsMentionDesc),
      PushCategoryCode.follower => (l.pushSettingsFollowerTitle, l.pushSettingsFollowerDesc),
      PushCategoryCode.gift => (l.pushSettingsGiftTitle, l.pushSettingsGiftDesc),
      PushCategoryCode.petReminder => (l.pushSettingsPetReminderTitle, l.pushSettingsPetReminderDesc),
      PushCategoryCode.achievement => (l.pushSettingsAchievementTitle, l.pushSettingsAchievementDesc),
      PushCategoryCode.promo => (l.pushSettingsPromoTitle, l.pushSettingsPromoDesc),
      _ => (l.pushSettingsOrderAccountTitle, l.pushSettingsOrderAccountDesc),
    };

class _PushSettingsPageState extends ConsumerState<PushSettingsPage> with WidgetsBindingObserver {
  /// 系统通知权限；null = 尚未读到（不显示状态卡）。
  bool? _granted;
  bool _viewedReported = false;

  /// 乐观值（覆盖 provider 值），请求结束即清。
  final Map<String, bool> _optimistic = {};

  /// 在途的 code：同一 code 在途时再点忽略，不同 code 可并行。
  final Set<String> _inFlight = {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshPermission();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshPermission(); // 从系统设置返回 → 刷新状态卡
  }

  /// 同设置页 `_refreshNotifStatus` 三步：上报跃迁 → setState → 已授权则补注册离线推送
  /// （开权限的入口从设置页挪到了这里，补注册跟着挪，否则要等下次冷启动才生效）。
  Future<void> _refreshPermission() async {
    final granted = await (widget.isNotificationGrantedForTest ?? isPushPermissionGranted)();
    await PushPermissionChangeReporter.record(granted, fromScreen: 'push_settings_page');
    if (!mounted) return;
    setState(() => _granted = granted);
    if (!_viewedReported) {
      _viewedReported = true; // 每次进页报一次；resume 刷新不重报
      Analytics.capture('push_settings_viewed', {'system_permission': granted ? 'on' : 'off'});
    }
    if (granted) {
      final isVet = ref.read(authControllerProvider).isVet;
      ref.read(pushServiceProvider).syncRegistration(isVet: isVet);
    }
  }

  /// 「Buka Pengaturan」：先申请一次系统权限，没拿到再去系统设置。
  /// ⚠️ 只调 openAppSettings 不够：iOS 上**从没申请过**（notDetermined，例如跳过了 F7 软引导）的 App
  /// 在系统设置里根本没有「通知」这一项 —— 用户无路可开。`request()` 对 notDetermined 弹系统窗；
  /// 已拒绝则立即返回 false（不打扰），再去系统设置。
  Future<void> _onOpenSystem() async {
    final granted = await (widget.requestPermissionForTest ?? requestPushPermission)();
    if (granted) {
      await _refreshPermission();
      return;
    }
    await (widget.openSettingsForTest ?? openPushSettings)();
  }

  Future<void> _toggle(String code, bool value) async {
    if (_inFlight.contains(code)) return;
    _inFlight.add(code);
    setState(() => _optimistic[code] = value);
    try {
      final saved = await ref.read(pushSettingsRepositoryProvider).update(code, value);
      final confirmed = saved.isEnabled(code);
      if (!mounted) return;
      ref.read(pushSettingsProvider.notifier).applyConfirmed(code, confirmed);
      setState(() => _optimistic.remove(code));
      // 只在服务端确认后报（失败回弹不报，免得看板把失败次数算成关闭次数）。
      Analytics.capture('push_category_toggled', {'category': code.toLowerCase(), 'enabled': confirmed});
    } catch (_) {
      if (!mounted) return;
      setState(() => _optimistic.remove(code));
      showAppToast(context, AppLocalizations.of(context).pushSettingsSaveFailed);
    } finally {
      _inFlight.remove(code);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(pushSettingsProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        leading: IconButton(
          key: const ValueKey('pushSettingsBack'),
          icon: const Icon(Icons.chevron_left_rounded, size: 28),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: () => context.canPop() ? context.pop() : context.go('/me/settings'),
        ),
        title: Text(l10n.notificationCenterTitle),
      ),
      body: SafeArea(
        top: false,
        child: switch (async) {
          AsyncData(:final value) => _content(l10n, value),
          AsyncError() => Center(
              child: EmptyState(
                icon: Icons.cloud_off_rounded,
                title: l10n.pushSettingsLoadFailed,
                actionLabel: l10n.commonRetry,
                onAction: () => ref.read(pushSettingsProvider.notifier).retry(),
              ),
            ),
          _ => const _PushSettingsSkeleton(),
        },
      ),
    );
  }

  Widget _content(AppLocalizations l10n, PushSettings settings) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
      children: [
        if (_granted != null) ...[
          _statusCard(l10n, _granted!),
          const SizedBox(height: 22),
        ],
        for (var g = 0; g < _groups.length; g++) ...[
          if (g > 0) const SizedBox(height: 22),
          _sectionTitle(_groups[g].title(l10n)),
          _card([
            for (var i = 0; i < _groups[g].rows.length; i++) ...[
              if (i > 0) _divider(),
              _row(l10n, settings, _groups[g].rows[i]),
            ],
          ]),
        ],
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.only(left: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.info_outline_rounded, size: 14, color: AppColors.muted),
              const SizedBox(width: 6),
              Expanded(
                child: Text(l10n.pushSettingsFooter,
                    style: const TextStyle(fontSize: 12, color: AppColors.muted, height: 1.5)),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _statusCard(AppLocalizations l10n, bool granted) {
    Widget iconCircle(IconData icon, Color color) => Container(
          width: 40,
          height: 40,
          decoration: const BoxDecoration(color: AppColors.card, shape: BoxShape.circle),
          child: Icon(icon, size: 22, color: color),
        );
    if (granted) {
      return Container(
        key: const ValueKey('pushSettingsStatusOn'),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(16)),
        child: Row(
          children: [
            iconCircle(Icons.notifications_active_rounded, AppColors.mint),
            const SizedBox(width: 12),
            Expanded(
              child: Text(l10n.pushSettingsSystemOn,
                  style: const TextStyle(
                      fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.ink, height: 1.45)),
            ),
          ],
        ),
      );
    }
    return Container(
      key: const ValueKey('pushSettingsStatusOff'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(color: AppColors.coralTint, borderRadius: BorderRadius.circular(16)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              iconCircle(Icons.notifications_off_rounded, AppColors.popRed),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.pushSettingsSystemOff,
                        style: const TextStyle(
                            fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.ink, height: 1.45)),
                    const SizedBox(height: 2),
                    Text(l10n.pushSettingsSystemOffHint,
                        style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                  ],
                ),
              ),
            ],
          ),
          // 稿 B3：紧凑按钮左对齐（L2 还原度；原 Container 带 alignment 被撑成通栏）。
          Padding(
            padding: const EdgeInsets.only(left: 52, top: 4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                boxShadow: const [
                  BoxShadow(color: Color(0x47845EC9), offset: Offset(0, 6), blurRadius: 16),
                ],
              ),
              child: Material(
                color: AppColors.mint,
                borderRadius: BorderRadius.circular(12),
                child: InkWell(
                  key: const ValueKey('pushSettingsOpenSystem'),
                  borderRadius: BorderRadius.circular(12),
                  onTap: _onOpenSystem,
                  child: Container(
                    height: 36,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    // widthFactor: 1 → 按文字收宽（带 alignment 的 Container 会吃满松约束、变通栏）。
                    child: Center(
                      widthFactor: 1,
                      child: Text(l10n.mediaOpenSettings,
                          style: const TextStyle(
                              fontSize: 12.5, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
                    ),
                  ),
                ),
              ),
            ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(AppLocalizations l10n, PushSettings settings, _Row row) {
    final (title, desc) = _copy(l10n, row.code);
    final locked = settings.isLocked(row.code);
    final value = _optimistic[row.code] ?? settings.isEnabled(row.code);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(color: row.bg, shape: BoxShape.circle),
            child: Icon(row.icon, size: 19, color: row.fg),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        color: locked ? AppColors.ink2 : AppColors.ink)),
                const SizedBox(height: 2),
                Text(desc, style: const TextStyle(fontSize: 12, color: AppColors.muted, height: 1.4)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          if (locked)
            Container(
              key: ValueKey('pushSettingsLocked_${row.code}'),
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
              decoration: BoxDecoration(color: _lockedBg, borderRadius: BorderRadius.circular(999)),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.lock_outline_rounded, size: 12, color: AppColors.muted),
                  const SizedBox(width: 3),
                  Text(l10n.pushSettingsAlwaysOn,
                      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.muted)),
                ],
              ),
            )
          else
            Switch(
              key: ValueKey('pushSettingsSwitch_${row.code}'),
              value: value,
              onChanged: (v) => _toggle(row.code, v),
              activeThumbColor: AppColors.onAccent,
              activeTrackColor: AppColors.mint,
              inactiveTrackColor: AppColors.line,
              inactiveThumbColor: AppColors.card,
              trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
            ),
        ],
      ),
    );
  }
}

// ---- 以下视觉常量照抄设置页（settings_page.dart `_sectionTitle` / `_card`）----

Widget _sectionTitle(String text) => Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 10),
      child: Text(text,
          style: const TextStyle(
              fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.muted, letterSpacing: 0.6)),
    );

Widget _card(List<Widget> children) => Container(
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [
          BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 3), blurRadius: 12),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: children),
    );

/// 分隔线从文字列起、不压图标（UI 稿 `.nrow+.nrow` margin-left 64；与设置页 indent 16 不同）。
Widget _divider() =>
    const Divider(height: 1, thickness: 1, color: AppColors.line2, indent: 64, endIndent: 16);

/// 加载骨架（UI 稿 B5）：状态卡形 + 一个组标题 + 一张卡 5 行。静态灰块、无 shimmer。
class _PushSettingsSkeleton extends StatelessWidget {
  const _PushSettingsSkeleton();

  static Widget _block(double w, double h, {double radius = 6}) => Container(
        width: w,
        height: h,
        decoration: BoxDecoration(color: _skeleton, borderRadius: BorderRadius.circular(radius)),
      );

  @override
  Widget build(BuildContext context) {
    return ListView(
      key: const ValueKey('pushSettingsSkeleton'),
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(16)),
          child: LayoutBuilder(
            builder: (context, c) => Row(
              children: [
                _block(40, 40, radius: 20),
                const SizedBox(width: 12),
                _block((c.maxWidth - 52) * 0.5, 12),
              ],
            ),
          ),
        ),
        const SizedBox(height: 22),
        Padding(padding: const EdgeInsets.only(left: 4, bottom: 10), child: _block(80, 10)),
        _card([
          for (var i = 0; i < 5; i++)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
              child: Row(
                children: [
                  _block(36, 36, radius: 18),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _block(110, 12),
                        const SizedBox(height: 6),
                        _block(170, 10),
                      ],
                    ),
                  ),
                  _block(46, 28, radius: 14),
                ],
              ),
            ),
        ]),
      ],
    );
  }
}
