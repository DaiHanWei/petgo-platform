import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../core/router/deep_link_routes.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../core/analytics/analytics.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../profile/domain/milestone_celebration_copy.dart';
import '../../profile/domain/milestone_titles.dart';
import '../../profile/data/profile_repository.dart';
import '../data/notification_repository.dart';
import '../domain/notification_deep_link.dart';
import '../domain/notification_item.dart';
import '../domain/push_permission_prompt.dart';
import '../../../core/storage/prefs.dart';
import '../data/push_permission_providers.dart';

/// 通知中心列表页（Story 6.6 F2/F3，FR-34）。倒序六(~七)类 + 空态 + 点击标记已读并深链跳目标。
/// 🔄 PRD V1.0.0 修订（F2 · 2026-06-08）：展示类型由四类扩到六(~七)类（加生日/纪念日/里程碑节点）。
///
/// 亦是 6.1 深链未知/兜底的落地页（`/notifications`）。
class NotificationCenterPage extends ConsumerStatefulWidget {
  const NotificationCenterPage({
    super.key,
    this.prefsForTest,
    this.isNotificationGrantedForTest,
    this.openSettingsForTest,
  });

  /// 以下三个仅供测试注入 —— 生产路径全部走默认实现。
  /// 触发点 4 要读系统通知开关与 prefs，二者在 widget test 里都没有平台实现。
  final AppPrefs? prefsForTest;
  final Future<bool> Function()? isNotificationGrantedForTest;
  final Future<bool> Function()? openSettingsForTest;

  @override
  ConsumerState<NotificationCenterPage> createState() =>
      _NotificationCenterPageState();
}

class _NotificationCenterPageState
    extends ConsumerState<NotificationCenterPage> {
  late Future<NotificationPage> _page;
  final Set<String> _locallyReadTokens = <String>{};

  /// 已累加的条目（首页 + 后续每页追加）。
  ///
  /// 🔴 2026-08-19 修：此前本页**只拉最新 20 条且界面上没有任何「加载更多」** ——
  /// `nextCursor` / `hasMore` 两个字段解析进来了却一处未用。后果是**第 20 条之后的通知
  /// 在 App 里永远到不了**；而铃铛角标数的是全库未读、不受这 20 条限制，于是一旦有未读
  /// 落在第一页之外，就出现「角标有数字、点进去没有新消息」，且**用户永远读不到那几条、
  /// 角标永远消不掉**。线上反馈的正是这个。
  final List<NotificationItem> _items = <NotificationItem>[];
  String? _nextCursor;
  bool _hasMore = false;
  bool _loadingMore = false;
  bool _loadMoreFailed = false;
  bool _markingAllRead = false;
  final ScrollController _scroll = ScrollController();

  /// 触发点 4（FR-85 / Story 8.2）：打开通知中心且系统通知关闭 → 顶部引导条。
  ///
  /// **为什么这个位置语境最贴合**：用户主动打开通知中心，说明他此刻在意「有没有人找我」；
  /// 而通知关着的话，这一页里的东西他从来不会被及时告知。
  bool _showPushBanner = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _loadFirstPage();
    _maybeShowPushBanner();
  }

  /// 判定 + 曝光上报。⚠️ 判定与「各一次」的标记都走 [PushPermissionPrompt]，
  /// 与触发点 1/2 共用同一套（形态不同，判定必须同一份）。
  Future<void> _maybeShowPushBanner() async {
    try {
      final prefs = widget.prefsForTest ?? await AppPrefs.create();
      final should = await PushPermissionPrompt.shouldPrompt(
        PushTriggerPoint.notificationCenter,
        prefs: prefs,
        isGranted: widget.isNotificationGrantedForTest,
      );
      if (!should || !mounted) return;
      // 展示即用掉这次机会（与触发点 1/2 同口径：划走也算）。
      await PushPermissionPrompt.markPrompted(
        PushTriggerPoint.notificationCenter,
        prefs: prefs,
      );
      unawaited(PushPermissionPrompt.reportShown(PushTriggerPoint.notificationCenter));
      if (mounted) setState(() => _showPushBanner = true);
    } catch (e) {
      debugPrint('[PushPrompt] notification center banner failed: $e');
    }
  }

  Future<void> _openPushSettings() async {
    unawaited(PushPermissionPrompt.reportResponded(
      PushTriggerPoint.notificationCenter,
      PushPromptResult.settingsOpened,
    ));
    await (widget.openSettingsForTest ?? openPushSettings)();
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// 首页（cursor=null）。服务端在此按 DB 真实未读校准 Redis 角标（自愈计数漂移）；
  /// 拉完刷新铃铛角标使其立即与真实一致。
  void _loadFirstPage() {
    final f = ref.read(notificationRepositoryProvider).list();
    _page = f;
    f.then((page) {
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(page.items);
        _nextCursor = page.nextCursor;
        _hasMore = page.hasMore;
        _loadMoreFailed = false;
      });
    }).whenComplete(() {
      if (mounted) ref.invalidate(unreadCountProvider);
    });
  }

  /// 重拉列表（bug 20260625-088：加载失败态的重试）。
  void _reload() {
    setState(_loadFirstPage);
  }

  /// 滚到底部附近 → 自动加载下一页。
  ///
  /// ⚠️ **上一次加载更多失败后不再自动重试**，改由用户点末尾的重试入口。否则持续失败时
  /// （断网、后端 5xx）每一次滚动抖动都会再发一次请求，变成请求风暴。
  void _onScroll() {
    if (!_scroll.hasClients || _loadMoreFailed) return;
    final pos = _scroll.position;
    if (pos.pixels >= pos.maxScrollExtent - 320) {
      _loadMore();
    }
  }

  Future<void> _loadMore() async {
    final cursor = _nextCursor;
    if (_loadingMore || !_hasMore || cursor == null) return;
    setState(() {
      _loadingMore = true;
      _loadMoreFailed = false;
    });
    try {
      // ⚠️ cursor 对客户端是**不透明串**：原样回传，不解析、不拼装
      // （服务端格式为 "<epochMicros>_<id>" 的复合游标，见后端 NotificationRepository#findPageBefore）。
      final page = await ref.read(notificationRepositoryProvider).list(cursor: cursor);
      if (!mounted) return;
      setState(() {
        _items.addAll(page.items);
        _nextCursor = page.nextCursor;
        _hasMore = page.hasMore;
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted) return;
      // 加载更多失败**不清空已加载的内容**，只在末尾给一个可重试的提示。
      setState(() {
        _loadingMore = false;
        _loadMoreFailed = true;
      });
    }
  }

  /// 全部标为已读（2026-08-19 加）。后端 read-all 接口与仓库方法早已存在、只缺界面入口。
  ///
  /// 产品定：**不做二次确认**（2026-08-19）。这也是用户被卡死的角标的**唯一自助出路** ——
  /// 在翻页修好之前，落在第一页之外的未读通知既读不到、角标也消不掉。
  Future<void> _markAllRead() async {
    if (_markingAllRead) return;
    setState(() => _markingAllRead = true);
    try {
      await ref.read(notificationRepositoryProvider).markAllRead();
      if (!mounted) return;
      // 本地即时置灰：已加载的条目全部按已读渲染（服务端已落库，重进页面同样是已读）。
      setState(() {
        for (final it in _items) {
          final t = it.deepLinkToken;
          if (t != null && t.isNotEmpty) _locallyReadTokens.add(t);
        }
      });
      ref.invalidate(unreadCountProvider);
    } catch (_) {
      // 失败不改本地已读态，避免界面与服务端不一致。
    } finally {
      if (mounted) setState(() => _markingAllRead = false);
    }
  }

  /// 通知类型 → 埋点取值（PRD §3.2 口径）。
  ///
  /// ⚠️ 与线格式**刻意分开**：线格式是 UPPER_SNAKE 的枚举名，埋点取值由 PRD 定死；
  /// 直接把枚举名发上去会让这条事件与 PRD 的判读口径对不上。
  static String _notifTypeForAnalytics(String type) => switch (type) {
    'MILESTONE_SM_NODE' => 'milestone_sm',
    'MILESTONE_NODE' => 'milestone_l',
    'CONTENT_LIKED' => 'like',
    'CONTENT_COMMENTED' => 'comment',
    'VET_REPLY' => 'vet_reply',
    _ => type.toLowerCase(),
  };

  /// 里程碑级别（S/M/L），从编码里取（形如 `C-S14`）。非里程碑通知返回 null。
  static String? _milestoneLevelOf(NotificationItem item) {
    if (!item.type.startsWith('MILESTONE')) return null;
    final code = item.targetRef;
    if (code == null) return null;
    final parts = code.split('-');
    if (parts.length < 2 || parts[1].isEmpty) return null;
    return parts[1][0].toUpperCase();
  }

  Future<void> _onTap(NotificationItem item) async {
    // 埋点（V1.1.6 Story 6.1 · AC6）：**所有类型都报** —— 只报里程碑就没法横向对比点击率，
    // 而"S/M 通知到底是留痕还是召回"这个判断恰恰要靠与点赞/评论类的对比得出。
    final props = <String, Object>{'notif_type': _notifTypeForAnalytics(item.type)};
    // 里程碑类另带级别（S/M/L）——判读时要能把 S/M 与 L 拆开看。
    final level = _milestoneLevelOf(item);
    if (level != null) {
      props['level'] = level;
    }
    Analytics.capture('app_notification_item_tapped', props);
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
    // 兜底落点是本页自身时不重复跳转。
    if (location == DeepLinkRoutes.notificationsCenter) return;
    if (DeepLinkRoutes.isShellTabRoot(location)) {
      // Tab 分支根（如纪念日→/profile=Diary）只能 go 切分支：push 会二次构建 shell
      // → GlobalKey 撞车白屏 + 此后该 Tab 永久失效（bug 20260729）。go 同时收掉本页栈。
      context.go(location);
    } else {
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
        // 🐛 2026-08-07：**永远给一个出口**。
        //
        // `AppBar` 只在 `Navigator.canPop()` 为真时才自动生成返回箭头。冷启动点推送落到这里时
        // 本页曾是栈里唯一一页（`go` 替换了整个栈）⇒ 没有箭头、iOS 左滑手势也因无上一页而失效，
        // 用户被困死只能杀进程（真机实测）。
        //
        // 根因已在 `app_router.dart` 的 `_goDeepLink` 修掉（先铺底座再 push）。这里是第二道防线：
        // 任何未来路径若又把本页作为栈底送达，用户至少还能回到主页，而不是死在这一屏。
        leading: Navigator.of(context).canPop()
            ? null // 有上一页 → 交给 AppBar 的默认返回键（保留系统返回语义与左滑手势）
            : IconButton(
                key: const ValueKey('notificationCenterHome'),
                icon: const Icon(Icons.arrow_back),
                color: AppColors.ink,
                tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                onPressed: () => context.go('/home'),
              ),
        title: Text(
          l10n.notificationCenterTitle,
          style: const TextStyle(
            fontSize: 19,
            fontWeight: FontWeight.w700,
            color: AppColors.ink,
          ),
        ),
        // 「全部已读」（2026-08-19 加，产品定：右上角、**不做二次确认**）。
        // 仅在已加载到至少一条未读时露出 —— 没有未读时这个按钮没有意义，露出来只是噪音。
        actions: [
          if (_items.any((it) => !_isRead(it)))
            _markingAllRead
                ? const Padding(
                    padding: EdgeInsets.only(right: 18),
                    child: Center(
                      child: SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    ),
                  )
                : IconButton(
                    key: const ValueKey('notificationMarkAllRead'),
                    icon: const Icon(Icons.done_all),
                    color: AppColors.ink,
                    tooltip: l10n.notifyMarkAllRead,
                    onPressed: _markAllRead,
                  ),
        ],
      ),
      // 引导条在列表**之外**、置于其上：它对空态与有内容态都要出现 ——
      // 通知中心是空的恰恰可能正因为通知被关着（收不到 → 也没人点进来看过）。
      body: Column(
        children: [
          if (_showPushBanner) _pushBanner(l10n),
          Expanded(child: _listArea(l10n)),
        ],
      ),
    );
  }

  /// 触发点 4 的引导条。形态与「我的」页的 [PushEnableGuide] 同族（同一套文案键），
  /// 但这里是页内顶部条 + 可关闭，故不直接复用那个组件。
  Widget _pushBanner(AppLocalizations l10n) {
    return Container(
      key: const ValueKey('pushCenterBanner'),
      margin: const EdgeInsets.all(12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          const Icon(Icons.notifications_active_outlined,
              color: AppColors.accentConsult),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.pushEnableGuideTitle,
                    style: const TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(l10n.pushEnableGuideBody,
                    style: const TextStyle(color: AppColors.textSecondary, fontSize: 12)),
              ],
            ),
          ),
          TextButton(
            key: const ValueKey('pushCenterBannerAction'),
            onPressed: _openPushSettings,
            child: Text(l10n.mediaOpenSettings),
          ),
          IconButton(
            key: const ValueKey('pushCenterBannerClose'),
            icon: const Icon(Icons.close, size: 18),
            tooltip: l10n.commonClose,
            onPressed: () {
              // 关掉即上报 dismissed。标记在展示时就已置位，这里不再动它。
              unawaited(PushPermissionPrompt.reportResponded(
                PushTriggerPoint.notificationCenter,
                PushPromptResult.dismissed,
              ));
              setState(() => _showPushBanner = false);
            },
          ),
        ],
      ),
    );
  }

  Widget _listArea(AppLocalizations l10n) {
    return FutureBuilder<NotificationPage>(
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
          // ⚠️ 用累加列表（含后续页），**不是** snapshot 里的首页快照 ——
          // 否则每次 setState 重建都会把翻页加载到的内容抹回第一页。
          final items = _items;
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
        });
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
    // bug 20260730-436：显式 padding 会关掉 ScrollView 自动注入的底部安全区补偿，
    // edge-to-edge 下末条会被系统手势条/三键遮挡——固定 20 之上叠加安全区高度。
    // V1.1.6 Story 6.1：S/M 里程碑通知要显示**具体是哪一条**，文案里带 `{name}` 占位符。
    // V1 单宠物，取当前档案的名字；拿不到就退化成简短标题（仍然具体）。
    final petName = ref.watch(petProfileProvider).asData?.value?.name;
    return ListView(
      controller: _scroll, // 滚到底附近自动加载下一页（_onScroll）
      padding: EdgeInsets.fromLTRB(16, 4, 16, 20 + MediaQuery.paddingOf(context).bottom),
      children: [
        if (today.isNotEmpty) ...[
          _groupLabel(l10n.notifyGroupToday),
          for (final it in today)
            _NotificationTile(
              item: it,
              read: _isRead(it),
              onTap: () => _onTap(it),
              petName: petName,
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
              petName: petName,
            ),
        ],
        // 翻页尾部：加载中 / 失败可重试 / 到底了。
        if (_hasMore || _loadingMore || _loadMoreFailed)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Center(
              child: _loadMoreFailed
                  ? TextButton(
                      key: const ValueKey('notificationLoadMoreRetry'),
                      onPressed: _loadMore,
                      child: Text(l10n.notifyLoadMoreRetry),
                    )
                  : const SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
            ),
          ),
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

/// 通知正文样式——折叠溢出测量与渲染共用同一常量，防两处样式漂移（bug 20260727-368）。
const TextStyle _kBodyStyle = TextStyle(fontSize: 12, height: 1.45, color: AppColors.ink2);

class _NotificationTile extends StatefulWidget {
  const _NotificationTile({
    required this.item,
    required this.read,
    required this.onTap,
    this.petName,
  });

  final NotificationItem item;
  final bool read;
  final VoidCallback onTap;

  /// 当前宠物名（V1 单宠物）。里程碑庆祝文案里带 `{name}` 占位符，靠它替换。
  /// 拿不到时退化成里程碑的**简短标题** —— 仍然是具体名称，不会落到中性兜底。
  final String? petName;

  @override
  State<_NotificationTile> createState() => _NotificationTileState();
}

class _NotificationTileState extends State<_NotificationTile> {
  bool _expanded = false;

  /// S/M 里程碑通知的文案（V1.1.6 Story 6.1 · AC4）。
  ///
  /// 🔴 **具体名称由客户端按里程碑编码查表得出**，后端只下发编码（`targetRef`）——
  /// 这是本模块一贯的约定：后端不下发展示文案，杜绝中文泄漏到印尼语界面。
  ///
  /// 优先用**庆祝文案**（AC 要求复用它）；它带 `{name}` 占位符，需要宠物名。
  /// 拿不到宠物名时退化成里程碑的**简短标题** —— 仍然是具体名称，
  /// **绝不会落到"你完成了一个里程碑"那种看不出发生了什么的兜底**。
  ({String title, String body}) _milestoneCopy(BuildContext context) {
    final code = widget.item.targetRef;
    final locale = Localizations.localeOf(context);
    if (code == null || code.isEmpty) {
      return (title: AppLocalizations.of(context).notifyTypeMilestoneNode, body: '');
    }
    final name = widget.petName;
    if (name == null || name.isEmpty) {
      return (title: localizedMilestoneTitle(code, locale), body: '');
    }
    return localizedMilestoneCelebration(code, locale, name);
  }

  /// 变体派生（内容审核 cm-7）：NAME_RESET/AVATAR_RESET 按 targetRef 判别主体是用户还是宠物。
  /// 后端约定 targetRef="NICKNAME"（昵称）/"USER_AVATAR"（用户头像）→ 用户变体；
  /// 否则为宠物 cardToken → 宠物变体。App 据此选 body 键（不渲染后端串）。
  bool get _isUserSubject =>
      widget.item.targetRef == 'NICKNAME' || widget.item.targetRef == 'USER_AVATAR';

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
    // V1.1.6 Story 6.1：S/M 级里程碑（只留痕、不推送）。与 L 级同族但换一个更轻的图标 ——
    // 同族是因为落点一样（都进里程碑列表），轻一档是因为它本就是"小成就"。
    'MILESTONE_SM_NODE' => (
      Icons.military_tech_rounded,
      AppColors.gold,
      AppColors.goldTint,
    ),
    // bug 20260729-391：以下类型此前无映射 → 全落兜底渲染，同瞬两条(结案+邀评)看似「重复发送」。
    'TICKET_RESOLVED' => (
      Icons.support_agent_rounded,
      AppColors.mint,
      AppColors.cream2,
    ),
    'CSAT_SURVEY' => (Icons.star_rate_rounded, AppColors.gold, AppColors.goldTint),
    'REFUND_REJECTED' => (
      Icons.currency_exchange_rounded,
      AppColors.coral,
      AppColors.coralTint,
    ),
    'CONTENT_REVIEW_APPROVED' => (
      Icons.check_circle_rounded,
      AppColors.triageGreen,
      AppColors.momenBadgeBg,
    ),
    'CONTENT_REVIEW_REJECTED' || 'CONTENT_REMOVED' => (
      Icons.remove_circle_outline_rounded,
      AppColors.coral,
      AppColors.coralTint,
    ),
    'REPORT_REVIEWED' => (
      Icons.verified_user_rounded,
      AppColors.mint,
      AppColors.cream2,
    ),
    // 审核类（cm-7）：名称/头像重置与审核超时用中性警示琥珀（盾牌+叹号）。
    'NAME_RESET' || 'AVATAR_RESET' || 'CONTENT_REVIEW_TIMED_OUT' => (
      Icons.gpp_maybe_rounded,
      AppColors.gold,
      AppColors.goldTint,
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
    // 🔴 S/M 必须写明**是哪一条里程碑**（AC4 明令禁止"你完成了一个里程碑"这类泛化文案）。
    'MILESTONE_SM_NODE' => _milestoneCopy(context).title,
    'TICKET_RESOLVED' => l10n.notifyTypeTicketResolved,
    'CSAT_SURVEY' => l10n.notifyTypeCsatSurvey,
    'REFUND_REJECTED' => l10n.notifyTypeRefundRejected,
    'CONTENT_REVIEW_APPROVED' => l10n.notifyTypeReviewApproved,
    'CONTENT_REVIEW_REJECTED' => l10n.notifyTypeReviewRejected,
    'CONTENT_REMOVED' => l10n.notifyTypeContentRemoved,
    'REPORT_REVIEWED' => l10n.notifyTypeReportReviewed,
    // 审核类（cm-7）。
    'NAME_RESET' => l10n.notifyTypeNameReset,
    'AVATAR_RESET' => l10n.notifyTypeAvatarReset,
    'CONTENT_REVIEW_TIMED_OUT' => l10n.notifyTypeReviewTimedOut,
    // 未知类型兜底：中性「系统通知」，不再复用页面标题（bug 20260729-391 的「克隆卡」观感来源）。
    _ => l10n.notifyTypeSystem,
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
    'MILESTONE_SM_NODE' => _milestoneCopy(context).body,
    'TICKET_RESOLVED' => l10n.notifyBodyTicketResolved,
    'CSAT_SURVEY' => l10n.notifyBodyCsatSurvey,
    'REFUND_REJECTED' => l10n.notifyBodyRefundRejected,
    'CONTENT_REVIEW_APPROVED' => l10n.notifyBodyReviewApproved,
    'CONTENT_REVIEW_REJECTED' => l10n.notifyBodyReviewRejected,
    'CONTENT_REMOVED' => l10n.notifyBodyContentRemoved,
    'REPORT_REVIEWED' => l10n.notifyBodyReportReviewed,
    // 审核类（cm-7）：名称/头像按 targetRef 选 用户/宠物 变体。
    'NAME_RESET' =>
        _isUserSubject ? l10n.notifyBodyNameResetUser : l10n.notifyBodyNameResetPet,
    'AVATAR_RESET' =>
        _isUserSubject ? l10n.notifyBodyAvatarResetUser : l10n.notifyBodyAvatarResetPet,
    'CONTENT_REVIEW_TIMED_OUT' => l10n.notifyBodyReviewTimedOut,
    // 未知类型兜底：中性正文，不再复用空态提示串（那本身就是个 bug）。
    _ => l10n.notifyBodySystem,
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
                  child: LayoutBuilder(builder: (context, constraints) {
                    // bug 20260727-368：只在正文折叠态(2 行)真溢出时才显示 展开/收起 按钮。
                    // 测量必须与正文 Text 同 style、同可用宽、同 textScaler（clamp≤1.3 后的实际值）。
                    // bug 20260730-431：style 必须 merge DefaultTextStyle——裸 _kBodyStyle 无
                    // fontFamily，TextPainter 落到平台默认字体(Roboto 偏窄)，而渲染 Text 继承主题
                    // Poppins(偏宽)，临界长度文案「量得下画不下」→ 溢出却不出展开按钮。
                    final bodyStyle =
                        DefaultTextStyle.of(context).style.merge(_kBodyStyle);
                    final bodyPainter = TextPainter(
                      text: TextSpan(text: _typeBody(l10n), style: bodyStyle),
                      maxLines: 2,
                      textDirection: Directionality.of(context),
                      textScaler: MediaQuery.textScalerOf(context),
                    )..layout(maxWidth: constraints.maxWidth);
                    final needsToggle = bodyPainter.didExceedMaxLines;
                    return Column(
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
                          style: bodyStyle,
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
                            if (needsToggle)
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
                    );
                  }),
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
