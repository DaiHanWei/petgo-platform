import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/pet_status_selector.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/user_state.dart';
import '../../content/domain/home_refresh_provider.dart';
import '../data/milestone_repository.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/card_link.dart';
import '../domain/pet_profile.dart';
import '../domain/share_service.dart';
import '../domain/timeline_item.dart';
import 'diary_guest_page.dart';
import 'widgets/archive_calendar.dart';
import 'widgets/diary_header.dart';
import 'widgets/share_fab.dart';
import 'widgets/timeline_item_tile.dart';

/// Diary 页（`/profile`）的四种用户状态（V1.1.2 · AD-15）。
///
/// **互斥且穷尽**：新增状态需改本枚举 → 分发处 `switch` 编译期报错，不会静默漏分支。
enum DiaryUserState {
  /// 游客（未登录）→ FR-80 游客引导态（Story 2.2 填充）。
  guest,

  /// 状态 B / C（PLANNING / ENTHUSIAST）→ 既有「有宠专属 + 修改宠物状态入口」页。
  nonOwner,

  /// 状态 A（HAS_PET）但无宠物档案 → FR-0G 建档引导（Story 2.3 填充）。
  ownerWithoutProfile,

  /// 状态 A 且已建档 → 真实成长档案页。
  ownerWithProfile,
}

/// AD-15 唯一状态判定：按序（游客 → 非有宠 → 有无档案）分发，不留隐式 fallback。
///
/// `petStatus` 未知（null，如 profile 尚未回填）按 HAS_PET 处理 —— 与改版前行为一致。
/// 不新增字段：只吃 `isLoggedIn` / `petStatus` / 「有无档案」三个既有信号。
///
/// 判定顺序**不在本函数里重写一遍**，而是复用应用级的 [resolveAppUserState]（Story 2.4 · AD-8）
/// 再折叠到本页的四个渲染分支 —— 落地 Tab 分流、埋点 `user_state`、本页分支三处共用一套顺序，
/// 避免日后各改一处而口径分叉。
DiaryUserState resolveDiaryUserState({
  required bool isLoggedIn,
  required String? petStatus,
  required bool hasPetProfile,
}) {
  final app = resolveAppUserState(
    isLoggedIn: isLoggedIn,
    // 兽医与用户侧路由互斥（router 层守卫），走不到本页 → 此处恒 false。
    isVet: false,
    petStatus: petStatus,
    hasPetProfile: hasPetProfile,
  );
  return switch (app) {
    AppUserState.guest => DiaryUserState.guest,
    // B 与 C 在本页渲染同一张「有宠专属」页，故折叠为一个分支。
    AppUserState.planning || AppUserState.enthusiast => DiaryUserState.nonOwner,
    AppUserState.ownerWithoutProfile => DiaryUserState.ownerWithoutProfile,
    AppUserState.ownerWithProfile => DiaryUserState.ownerWithProfile,
    AppUserState.vet => DiaryUserState.guest, // 不可达；穷尽 switch 要求给个安全默认
  };
}

/// Diary（成长档案）Tab 主屏。四态由 [resolveDiaryUserState] **单一入口**分发（AD-15）：
/// - 游客 → FR-80 游客引导态（本 Story 为占位，2.2 填充）；
/// - 状态 A + 无档案 → FR-0G 建档引导（本 Story 沿用既有空状态，2.3 填充）；
/// - 状态 A + 有档案 → 信息卡 + 分享 FAB + 倒序时间线；
/// - 状态 B/C → 「有宠专属」+ 修改状态入口。
///
/// ⚠️ 本页**任何其它位置不得再判用户状态**（AD-15 核心约束）——要加状态相关分支，
/// 一律扩 [DiaryUserState] 并在下面的 switch 里布线。
class GrowthArchivePage extends ConsumerWidget {
  const GrowthArchivePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider);
    final petStatus = auth.profile?.petStatus;

    // 仅「有宠路径」订阅档案：游客无令牌，订阅会打出 401 → 全局强登录引导
    // （门控在 2.4 放行游客进本页后即暴露）；非有宠态本就无档案可拉。
    // 该条件与 resolveDiaryUserState 返回两个 owner 态的条件等价，故下面 `profileAsync!` 安全。
    final ownerPath = auth.isLoggedIn && (petStatus == null || petStatus == 'HAS_PET');
    final profileAsync = ownerPath ? ref.watch(petProfileProvider) : null;

    // ===== AD-15：全页唯一的用户状态判定 + 分发 =====
    final state = resolveDiaryUserState(
      isLoggedIn: auth.isLoggedIn,
      petStatus: petStatus,
      // 「已建档」以真实档案为准，不信任登录响应里可能 stale 的 hasPetProfile（与「我的」页同口径）。
      // 加载中 / 失败先落 ownerWithoutProfile，两者的具体渲染由 _ownerBranch 内的
      // when(loading/error) 承接 —— 行为与改版前完全一致。
      hasPetProfile: profileAsync?.asData?.value != null,
    );

    return switch (state) {
      DiaryUserState.guest => const DiaryGuestPage(),
      DiaryUserState.nonOwner =>
        _NonOwnerView(onChangeStatus: () => _openStatusEditor(context, ref)),
      DiaryUserState.ownerWithoutProfile ||
      DiaryUserState.ownerWithProfile =>
        _ownerBranch(context, ref, profileAsync!, state),
    };
  }

  /// 状态 A 外壳（已建档 / 未建档共用）：cream 底 + 档案加载态。
  ///
  /// 分享名片按钮**不再是右下悬浮 FAB**（2026-08-04 用户要求）—— 它会盖住时间线与日历
  /// 右下角的内容；改为页头标题行里编辑铅笔旁的小图标，由 [_ArchiveBody] 注入。
  Widget _ownerBranch(
    BuildContext context,
    WidgetRef ref,
    AsyncValue<PetProfile?> profileAsync,
    DiaryUserState state,
  ) {
    return Scaffold(
      backgroundColor: AppColors.cream,
      body: profileAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => _EmptyProfileView(onCreate: () => context.push('/profile/create')),
        data: (profile) {
          // state 与本次 build 的 asData 同源，故 ownerWithProfile ⟺ profile != null；
          // 判空仅为空安全，不构成第二处状态判定。
          if (state == DiaryUserState.ownerWithProfile && profile != null) {
            return _ArchiveBody(
              profile: profile,
              onEditProfile: () => context.push('/profile/edit'),
            );
          }
          // 状态 A 未建档 → 建档引导态（Story 2.3 按 A2 稿补齐副文案；仍复用 FR-0G 既有引导）。
          return _EmptyProfileView(
            onCreate: () => context.push('/profile/create'),
            onChangeStatus: () => _openStatusEditor(context, ref),
          );
        },
      ),
    );
  }

  Future<void> _openStatusEditor(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    String? selected = ref.read(authControllerProvider).profile?.petStatus;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.base,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.growthEditStatusTitle, style: Theme.of(ctx).textTheme.titleMedium),
              const SizedBox(height: AppSpacing.md),
              PetStatusSelector(
                selected: selected,
                onChanged: (v) => setSheetState(() => selected = v),
              ),
              const SizedBox(height: AppSpacing.md),
              FilledButton(
                key: const ValueKey('saveStatusButton'),
                onPressed: selected == null
                    ? null
                    : () async {
                        final chosen = selected!;
                        final ok = await _saveStatus(context, ref, chosen);
                        if (!ok) return; // 失败留在 sheet 供重试
                        if (ctx.mounted) Navigator.of(ctx).pop();
                        // 非有宠（PLANNING/ENTHUSIAST）成长档案无内容 → 回首页；
                        // HAS_PET 留在本 tab 走建档引导（页面 rebuild 到 _EmptyProfileView）。
                        if (chosen != 'HAS_PET' && context.mounted) context.go('/home');
                      },
                child: Text(l10n.commonSave),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<bool> _saveStatus(BuildContext context, WidgetRef ref, String status) async {
    final l10n = AppLocalizations.of(context);
    try {
      final updated = await ref.read(meRepositoryProvider).updatePetStatus(status);
      // FR-21 即时同步：回填全局 profile → 「我的」一致；bump 首页 → Feed 即时刷新。
      ref.read(authControllerProvider.notifier).applyProfile(updated);
      ref.read(homeRefreshProvider.notifier).bump();
      ref.invalidate(petProfileProvider);
      ref.invalidate(timelineFirstPageProvider);
      ref.invalidate(archiveStatsProvider);
      return true;
    } catch (_) {
      if (context.mounted) {
        showAppToast(context, l10n.growthStatusSaveFailed);
      }
      return false;
    }
  }
}

/// 档案主屏内容（信息卡 + 统计栏 + 里程碑 + 双视图）。view mode 为本地状态：
/// 留在页面切换时保持（session 内），离开后重建恢复默认时间线（StatefulShellRoute 分支保活下为近似）。
enum _ArchiveView { timeline, calendar }

class _ArchiveBody extends ConsumerStatefulWidget {
  const _ArchiveBody({required this.profile, required this.onEditProfile});

  final PetProfile profile;
  final VoidCallback onEditProfile;

  @override
  ConsumerState<_ArchiveBody> createState() => _ArchiveBodyState();
}

class _ArchiveBodyState extends ConsumerState<_ArchiveBody> {
  // Debug 截图钩子（仅 debug + flag）：默认进 Kalender 视图，截 catatan-calendar 用。
  _ArchiveView _view =
      (kDebugMode && const String.fromEnvironment('DEV_ARCHIVE_VIEW') == 'calendar')
          ? _ArchiveView.calendar
          : _ArchiveView.timeline;

  /// 页面唯一的滚动器。时间线是它的一个子节点（不是自己滚），所以「翻页」只能由这里判断触底。
  final ScrollController _scroll = ScrollController();

  /// 触底信号（自增计数）。用 tick 而不是 bool：连续触底要能反复触发下一页，
  /// bool 会因值没变而收不到通知。
  final ValueNotifier<int> _loadMoreTick = ValueNotifier<int>(0);

  /// 提前 400px 预取：等真正到底再转圈，用户会先看到一段空白。
  static const double _kPrefetchExtent = 400;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_maybeLoadMore);
  }

  @override
  void dispose() {
    _scroll.removeListener(_maybeLoadMore);
    _scroll.dispose();
    _loadMoreTick.dispose();
    super.dispose();
  }

  void _maybeLoadMore() {
    if (_view != _ArchiveView.timeline) return; // 日历视图按月取数，与游标分页无关
    final pos = _scroll.position;
    if (pos.pixels >= pos.maxScrollExtent - _kPrefetchExtent) {
      _loadMoreTick.value++;
    }
  }

  /// 内容压根没撑出滚动余量时的兜底（code-review 2026-08-04）。
  ///
  /// 翻页信号全靠 [_maybeLoadMore] 的滚动通知。若首屏总高不超过视口（大屏 / 这一页全是矮胶囊与
  /// banner / 某页返回空 items 但 `hasMore` 仍为 true），`maxScrollExtent` 恒为 0 →
  /// **永远不会有滚动通知** → 第 21 条起又变成「App 里看不到」，与本次要修的缺口同形。
  ///
  /// 不会打转：tick 走的还是 `_TimelineView._onNearBottom` 同一条路径，
  /// 没有下一页时 `_loadMore` 直接返回（不 setState → 不重建 → 不再触发）；
  /// 失败态下 `_onNearBottom` 早退，所以也不会变成失败重试风暴。
  void _bumpIfViewportNotScrollable() {
    if (_view != _ArchiveView.timeline) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      if (_view != _ArchiveView.timeline) return;
      if (_scroll.position.maxScrollExtent <= 0) {
        _loadMoreTick.value++;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final stats = ref.watch(archiveStatsProvider).asData?.value;
    _bumpIfViewportNotScrollable();
    return RefreshIndicator(
      color: AppColors.mint,
      onRefresh: () async {
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
        // bug 20260727-365：日历视图的数据在 calendarMonthProvider（按年月分族），漏失效会导致
        // 日历 tab 下拉刷新只走动画不刷数据；整族失效（含非当前月缓存），照 pet_profile_edit_page 先例。
        ref.invalidate(calendarMonthProvider);
      },
      child: ListView(
        controller: _scroll,
        // 底部留白要**避开凸起的「＋」**：它比底栏高出一截，48 只够躲开底栏本身，
        // 滚到底时最后一个元素（引导卡的 CTA 最明显）会被压住（2026-08-04 实机确认）。
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 38, AppSpacing.lg, 92),
        children: [
          // 页头（标题行 + 信息卡 + 健康记录入口 + 里程碑进度）走 Story 2.2 抽出的共用组件，
          // 与游客态同一份实现（3.3 要求两态页头一致）。入口跳转由本页注入。
          DiaryHeader(
            profile: widget.profile,
            happyCount: stats?.happyMomentCount,
            consultCount: stats?.consultCount,
            milestoneCompleted: stats?.milestoneCompleted,
            milestoneTotal: stats?.milestoneTotal,
            healthRecordCount: stats?.healthRecordCount,
            titleAction: _shareButton(),
            onEditProfile: widget.onEditProfile,
            onOpenIdCard: () => context.push('/profile/id-card'),
            onOpenHealth: () => context.push('/profile/health'),
            onOpenMilestones: () => context.push('/profile/milestones'),
          ),
          _viewToggleRow(l10n),
          const SizedBox(height: 10),
          // 时间线**始终挂载**、只在切到日历时 offstage（code-review 2026-08-04）：
          // 原先是条件构建，切一次日历就把 _TimelineView 的 State 连同已翻的页、游标一起 dispose，
          // 切回来列表退回 20 条、滚动位置还被 clamp 得跳一下。offstage 保住状态，
          // 且不参与布局绘制；日历仍只在需要时才构建（避免白拉一次按月取数）。
          Offstage(
            offstage: _view != _ArchiveView.timeline,
            child: _TimelineView(
              petName: widget.profile.name,
              loadMoreTick: _loadMoreTick,
              onContentChanged: _bumpIfViewportNotScrollable,
            ),
          ),
          if (_view == _ArchiveView.calendar) _calendarView(),
        ],
      ),
    );
  }

  /// 分享名片按钮（Story 2.7 的动效按钮，紧凑形态）。
  ///
  /// 位置从「右下悬浮 FAB」挪到页头标题行编辑铅笔旁（2026-08-04 用户要求）。
  /// 仅在有 `cardToken` 时渲染 —— 老档案可能没有，没有就分不出去（AC3 的原有约束不变）。
  Widget? _shareButton() {
    final profile = widget.profile;
    if (profile.cardToken.isEmpty) return null;
    final l10n = AppLocalizations.of(context);
    final alreadyShown = ref.watch(shareFabAnimatedShownProvider).asData?.value ?? true;
    return ShareFab(
      compact: true,
      semanticLabel: l10n.shareFabLabel,
      animate: !alreadyShown,
      onAnimationShown: () {
        markShareFabAnimated();
        ref.invalidate(shareFabAnimatedShownProvider);
      },
      onPressed: (origin) async {
        // 传按钮矩形作 iOS 分享面板锚点 + await + 兜错（bug 20260707：iOS 点了没反应）。
        try {
          await ref.read(shareServiceProvider)(
            petCardShareUrl(profile.cardToken),
            sharePositionOrigin: origin,
          );
        } catch (_) {
          if (mounted) showAppToast(context, l10n.shareFailed);
        }
        // 名片分享信号 → 里程碑 C-S3 自动完成（Story 8.3，fire-and-forget，失败静默）。
        ref.read(milestoneRepositoryProvider).signalCardShared().catchError((_) {});
        ref.invalidate(milestoneListProvider);
      },
    );
  }

  /// Timeline / Kalender 药丸切换（paspor.html 双按钮）。
  Widget _viewToggleRow(AppLocalizations l10n) {
    return Row(
      children: [
        Expanded(child: _toggleBtn('⏱ ${l10n.growthArchiveViewTimeline}', _view == _ArchiveView.timeline,
            () => _switchView(_ArchiveView.timeline), const ValueKey('archiveViewTimeline'))),
        const SizedBox(width: 7),
        Expanded(child: _toggleBtn('📅 ${l10n.growthArchiveViewCalendar}', _view == _ArchiveView.calendar,
            () => _switchView(_ArchiveView.calendar), const ValueKey('archiveViewCalendar'))),
      ],
    );
  }

  /// T-11 diary_view_mode_switched（Story 6.1）：日历视图本版本接了健康记录，看看有多少人真的用它。
  void _switchView(_ArchiveView to) {
    if (_view == to) return;
    Analytics.capture('diary_view_mode_switched', {'to_view': to.name});
    setState(() => _view = to);
  }

  Widget _toggleBtn(String label, bool on, VoidCallback onTap, Key key) => GestureDetector(
        key: key,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 9),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: on ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(10),
            border: on ? null : Border.all(color: AppColors.line, width: 1.5),
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: on ? AppColors.onAccent : AppColors.ink2)),
        ),
      );

  Widget _calendarView() {
    return ArchiveCalendar(
      onOpenDay: (date) => context.push(
          '/profile/day?date=${date.year}-${_two(date.month)}-${_two(date.day)}'),
      onAddOnDate: (date) => context.push(
          '/publish?preset=growth-calendar&date=${date.year}-${_two(date.month)}-${_two(date.day)}'),
    );
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
}

/// 时间线视图（五类条目倒序；首条显 🌟；加载失败可重试）。
///
/// Story 3.3：条目渲染改走 **Story 2.2 建立的共用组件** [TimelineItemTile]，与游客示例本同一份实现
/// （NFR-7）；本视图只负责**按 itemType 注入真实态的点击语义**——组件自身不持有任何跳转。
class _TimelineView extends ConsumerStatefulWidget {
  const _TimelineView({
    required this.petName,
    required this.loadMoreTick,
    this.onContentChanged,
  });

  /// 宠物名（类③ 庆祝文案与类⑤ 证件卡标题需要）。
  final String petName;

  /// 宿主页滚到接近底部时自增（见 [_ArchiveBodyState._maybeLoadMore]）。
  final ValueNotifier<int> loadMoreTick;

  /// 追加了新条目后回调宿主页 —— 让它再判一次「内容够不够撑出滚动余量」
  /// （见 [_ArchiveBodyState._bumpIfViewportNotScrollable]）。滚动器归宿主页，只有它能判。
  final VoidCallback? onContentChanged;

  @override
  ConsumerState<_TimelineView> createState() => _TimelineViewState();
}

/// 时间线视图 + **游标分页**（V1.1.2 · Story 3.3 遗留项补齐，2026-08-04）。
///
/// 之前只取第一页（20 条），记满 20 条以后的旧记录**在 App 里根本看不到** —— 后端早就支持
/// `cursor`，缺的是前端这一段。顺带让 Story 3.3 写好却「无从触发」的
/// 「增量失败 → 底部重试」有了真实入口。
///
/// **为什么翻页状态放在这里而不是 provider**：第一页仍走 [timelineFirstPageProvider]，
/// 全项目已有 7 处 `invalidate(timelineFirstPageProvider)`（发布成功、删档、编辑档案、切 Tab…）。
/// 若把整条时间线搬进新 provider，那 7 处都要跟着改，且容易漏一处导致「发了新日记但列表不刷」。
/// 现在的做法是：第一页变了 → [_resetPages] 把已翻的页丢掉重来，那 7 处调用点一行都不用改。
class _TimelineViewState extends ConsumerState<_TimelineView> {
  /// 第 2 页起累积的条目（第一页始终来自 provider）。
  final List<TimelineItem> _extra = <TimelineItem>[];

  /// 下一页游标；null = 没有下一页。
  String? _cursor;
  bool _cursorInitialized = false;
  bool _hasMore = false;
  bool _loadingMore = false;

  /// 增量失败态：**不清空已加载内容**（AC7 口径 —— 已经看到的东西不能因为翻页失败而消失），
  /// 只在底部给一个重试入口。
  bool _loadMoreFailed = false;

  /// 上一次见到的第一页实例，用于识别「provider 被 invalidate 了」。
  TimelinePage? _lastFirstPage;

  /// 翻页请求代号（code-review 2026-08-04）。
  ///
  /// **为什么必须有**：`_loadMore` 是异步的，而第一页随时可能被 invalidate（下拉刷新、发布成功、
  /// 切回 Diary Tab、详情页删帖 —— 全项目 9 处）。若只判 `mounted`，一次「翻页在飞时刷新」就会：
  /// 旧响应回来把**刷新前**那一页 `addAll` 进已经清空的列表，并用**过期游标**覆盖新第一页的游标
  /// → 刷新边界处的那条日记从此消失，且后续每一页都基于旧快照取数，再也翻不回来。
  /// `_resetPages` 会自增本代号，resolve 时代号不匹配即整份丢弃。
  int _loadGeneration = 0;

  /// 「内容撑不满视口 → 自动多取一页」已经连续做了几轮。
  ///
  /// 有上限是必须的：若某页返回的条目高度很小、或后端一直 `hasMore=true`，
  /// 无上限的自动填充会变成一串连锁请求。超过上限就交回给用户滚动触发。
  int _autoFillRounds = 0;
  static const int _kMaxAutoFillRounds = 5;

  @override
  void initState() {
    super.initState();
    widget.loadMoreTick.addListener(_onNearBottom);
  }

  @override
  void dispose() {
    widget.loadMoreTick.removeListener(_onNearBottom);
    super.dispose();
  }

  void _onNearBottom() {
    // 失败态下不自动重试：否则用户停在底部会被无限重试刷屏，且看不到那行提示。
    if (_loadMoreFailed) return;
    _loadMore();
  }

  /// 第一页发生变化（发布/删除/刷新）→ 已翻的页全部作废。
  ///
  /// ⚠️ 本方法在 build 路径里被调用，而此刻可能有一个翻页请求**还在飞**。在飞请求的收尾靠
  /// [_loadGeneration] 判废（这里自增，旧响应回来整份丢弃），**不能**指望清 `_loadingMore`。
  void _resetPages(TimelinePage first) {
    _loadGeneration++;
    _autoFillRounds = 0;
    _extra.clear();
    _cursor = first.nextCursor;
    _hasMore = first.hasMore;
    _cursorInitialized = true;
    // 旧请求已被上面的自增判废，这里放行的是「基于新第一页」的翻页。
    _loadingMore = false;
    _loadMoreFailed = false;
  }

  Future<void> _loadMore() async {
    if (_loadingMore || !_hasMore || _cursor == null) return;
    final int generation = _loadGeneration;
    setState(() {
      _loadingMore = true;
      _loadMoreFailed = false;
    });
    try {
      final page = await ref.read(timelineRepositoryProvider).getTimeline(cursor: _cursor);
      if (!mounted) return;
      // 期间第一页被 invalidate 过 → 这份响应是**旧快照**的下一页。接上去会漏掉刷新边界那条日记，
      // 覆盖游标还会让它再也翻不回来。整份丢弃，一个字段都不改。
      if (generation != _loadGeneration) return;
      setState(() {
        _extra.addAll(page.items);
        _cursor = page.nextCursor;
        _hasMore = page.hasMore;
        _loadingMore = false;
      });
      // 这一页真加了东西、且还没到自动填充上限 → 请宿主页再判一次视口是否已可滚动。
      // 空页不续（否则「hasMore 恒真 + 空页」会变成无休止的连锁请求）。
      if (page.items.isNotEmpty && _autoFillRounds < _kMaxAutoFillRounds) {
        _autoFillRounds++;
        widget.onContentChanged?.call();
      }
    } catch (_) {
      if (!mounted) return;
      if (generation != _loadGeneration) return; // 旧代号的失败同样不该污染新状态
      // 失败只记状态，不弹 toast、不清列表（AC7）。
      setState(() {
        _loadingMore = false;
        _loadMoreFailed = true;
      });
    }
  }


  /// 真实态点击目标（AC5）：
  /// - 类①/② → 内容详情页（postId 为空不可点，与当天详情一致）
  /// - 类③ → 里程碑列表页
  /// - 类④ → 问诊存档走既有结果页深链；结构化健康记录 → 健康记录列表页
  /// - 类⑤ → 身份证页
  /// `a` 是否比 `b` 更早：先比展示用的事件日期，同日再比创建时间。
  /// 「第一条快乐时刻」认的是**最早那条**，而同日内后端是正序下发的，所以不能靠列表位置推。
  static bool _isEarlier(TimelineItem a, TimelineItem b) {
    final byDate = a.displayDate.compareTo(b.displayDate);
    return byDate != 0 ? byDate < 0 : a.date.isBefore(b.date);
  }

  /// T-10 `diary_timeline_item_tapped`（Story 6.1 · AC4）。
  ///
  /// `item_type` **直取后端下发的 `itemType`**，前端不自行推断（AD-2）。老后端不下发该字段时
  /// 报 `UNSPECIFIED` 而不是 `resolvedType` 的兜底推断值 —— 送推断值会让「后端真的这么分类」
  /// 与「前端猜的」在看板上完全无法区分（code-review 2026-08-04）。
  static void _reportItemTap(TimelineItem item) => Analytics.capture(
      'diary_timeline_item_tapped', {'item_type': item.itemType?.wire ?? 'UNSPECIFIED'});

  static VoidCallback? _realTapFor(BuildContext context, TimelineItem item) {
    void report() => _reportItemTap(item);
    switch (item.resolvedType) {
      case TimelineItemType.happyMoment:
      case TimelineItemType.happyMomentMilestone:
        final id = item.postId;
        return id == null
            ? null
            : () {
                report();
                context.push('/content/$id');
              };
      case TimelineItemType.milestoneBanner:
        return () {
          report();
          context.push('/profile/milestones');
        };
      case TimelineItemType.healthRecord:
        // 问诊存档：跳对应问诊/分诊结果页（bug 20260706-259 的既有行为，不回退）。
        final route = item.healthEventRoute;
        if (route != null) {
          return () {
            report();
            context.push(route);
          };
        }
        // 结构化健康记录：跳健康记录列表页（FR-45B）。时间线内**不提供编辑入口**。
        // 带 `?focus=<id>` 定位到点的那一条（Story 3.3 遗留项，2026-08-04 补齐）——
        // 只跳列表顶部等于让用户自己再找一遍，记录一多就找不到。
        // 老后端不下发 healthRecordId 时退化为「跳列表顶部」，不报错。
        final recordId = item.healthRecordId;
        return () {
          report();
          context.push(
              recordId == null ? '/profile/health' : '/profile/health?focus=$recordId');
        };
      case TimelineItemType.idCardIssued:
        return () {
          report();
          context.push('/profile/id-card');
        };
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final timelineAsync = ref.watch(timelineFirstPageProvider);
    // 第一页的身份变了就重置分页状态（provider 被 invalidate → 新的 TimelinePage 实例）。
    final firstPage = timelineAsync.asData?.value;
    if (firstPage != null && (!_cursorInitialized || !identical(firstPage, _lastFirstPage))) {
      _lastFirstPage = firstPage;
      _resetPages(firstPage);
    }
    return timelineAsync.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(AppSpacing.xl),
        child: Center(child: CircularProgressIndicator()),
      ),
      // AC7：内容区加载失败 → 「加载失败，下拉重试」+ 重试入口（信息卡/统计栏不受影响）。
      error: (err, stack) => Container(
        key: const ValueKey('timelineError'),
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(12)),
        child: Column(
          children: [
            Text(l10n.growthLoadFailed, style: const TextStyle(color: AppColors.muted)),
            const SizedBox(height: 8),
            TextButton(
              key: const ValueKey('timelineRetry'),
              onPressed: () => ref.invalidate(timelineFirstPageProvider),
              child: Text(l10n.growthLoadRetry),
            ),
          ],
        ),
      ),
      data: (page) {
        if (page.items.isEmpty && _extra.isEmpty) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(0, 18, 0, AppSpacing.xl),
            child: _FirstMomentGuideCard(petName: widget.petName),
          );
        }
        final items = <TimelineItem>[...page.items, ..._extra];
        // 「Pertama」🌟 标在最旧的那条（debut，列表为倒序故取最后出现的索引）；
        // 仅在已无更多分页时可断定为真正第一条。
        // 只标「第一条快乐时刻」：debut 标记只在照片卡（类①/②）上渲染，
        // 类④ 的「第一条健康事件」标记随共用组件一并取消（A6 稿的胶囊/粉底条都没有该标记）。
        // ⚠️ 不能简单「取最后一次命中的索引」（code-review 2026-08-04）：整条时间线是按日期倒序，
        // **但后端刻意把同一天内改成创建时间正序下发**（TimelineService.withinDayAscending）。
        // 于是最旧那天若有 2 条以上快乐时刻，取最后一个会把 🌟 标在那天**最晚**的一条上。
        // 正确做法是显式比较：先比事件日期，同日再比创建时间，取最小的那条。
        int debutHappy = -1;
        if (!_hasMore) {
          for (var i = 0; i < items.length; i++) {
            final item = items[i];
            final t = item.resolvedType;
            if (t != TimelineItemType.happyMoment &&
                t != TimelineItemType.happyMomentMilestone) {
              continue;
            }
            if (debutHappy < 0 || _isEarlier(item, items[debutHappy])) {
              debutHappy = i;
            }
          }
        }
        // 按月分组：月份变化插入 "Juni 2026" 区标题。
        final tiles = <Widget>[];
        String? lastMonthKey;
        for (var i = 0; i < items.length; i++) {
          final item = items[i];
          final d = item.displayDate;
          final monthKey = '${d.year}-${d.month}';
          if (monthKey != lastMonthKey) {
            tiles.add(Padding(
              padding: EdgeInsets.only(top: lastMonthKey == null ? 0 : 6, bottom: 10),
              child: Text(formatMonthYear(context, d),
                  style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.ink)),
            ));
            lastMonthKey = monthKey;
          }
          final bool isHappy = item.resolvedType == TimelineItemType.happyMoment ||
              item.resolvedType == TimelineItemType.happyMomentMilestone;
          // ⚠️ 与游客态**同一个** TimelineItemTile（NFR-7）；差异只在这里注入的回调。
          tiles.add(TimelineItemTile(
            item: item,
            petName: widget.petName,
            thumbIndex: i,
            firstLabel: isHappy && i == debutHappy ? l10n.growthFirstHappyMoment : null,
            onTap: _realTapFor(context, item),
            // 类② 金徽章：真实态点它跳里程碑列表（游客态是建档引导，见 diary_guest_page）。
            // 徽章是**独立可点区域**，也算「点了时间线上的一条」→ 同样上报 T-10
            // （原先漏报，看板上徽章点击等于不存在；code-review 2026-08-04）。
            onBadgeTap: item.resolvedType == TimelineItemType.happyMomentMilestone
                ? () {
                    _reportItemTap(item);
                    context.push('/profile/milestones');
                  }
                : null,
          ));
        }
        // A4 近空态（2026-08-04 用户实机反馈）：刚建档的时间线并**不是空的** ——
        // 建档完成里程碑（D-S1）自动占着一条 banner，于是上面那个「整条为空」的分支
        // 永远进不去，引导卡在真机上从未出现过，banner 下方是一大片空白。
        // 判定改为「还没有任何快乐时刻」：banner 照常显示，引导卡追加在它下面。
        // `!_hasMore` 保证只在确实翻到底时才断言「没有」，否则旧照片可能还在后面几页。
        if (!_hasMore && debutHappy < 0) {
          tiles.add(Padding(
            padding: const EdgeInsets.only(top: 8),
            child: _FirstMomentGuideCard(petName: widget.petName),
          ));
        }
        // 底部翻页页脚：加载中转圈 / 失败给重试 / 没有更多则什么都不加。
        if (_loadMoreFailed) {
          tiles.add(_loadMoreRetry(l10n));
        } else if (_loadingMore) {
          tiles.add(const Padding(
            key: ValueKey('timelineLoadMoreSpinner'),
            padding: EdgeInsets.symmetric(vertical: AppSpacing.lg),
            child: Center(child: SizedBox(
                width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))),
          ));
        }
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: tiles);
      },
    );
  }

  /// 增量加载失败的底部重试行（Story 3.3 AC7 写了、此前「无从触发」的那一条）。
  ///
  /// ⚠️ 与整页失败态（key `timelineError`）**刻意不同**：那个会替换掉整个内容区，
  /// 这个只在已加载内容的末尾加一行 —— 翻页失败不该把用户已经看到的日记抹掉。
  Widget _loadMoreRetry(AppLocalizations l10n) => Padding(
        key: const ValueKey('timelineLoadMoreError'),
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Flexible(
              child: Text(l10n.growthLoadFailed,
                  style: const TextStyle(color: AppColors.muted, fontSize: 12)),
            ),
            const SizedBox(width: 8),
            TextButton(
              key: const ValueKey('timelineLoadMoreRetry'),
              onPressed: () {
                setState(() => _loadMoreFailed = false);
                _loadMore();
              },
              child: Text(l10n.growthLoadRetry),
            ),
          ],
        ),
      );
}

/// 「记下第一条快乐时刻」引导卡（UI 稿 **A4 近空态** 的 `diary-guide-card`）。
///
/// 出现条件是「**还没有任何快乐时刻**」，不是「时间线为空」—— 建档完成里程碑 banner
/// 会一直占着一条，按「为空」判定这张卡永远不出现（2026-08-04 实机确认）。
/// 已建档真实态与整条为空两种情形共用本卡，不再各写一套。
class _FirstMomentGuideCard extends StatelessWidget {
  const _FirstMomentGuideCard({required this.petName});

  final String petName;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('timelineFirstMomentCard'),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: 16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [
          BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
        ],
      ),
      child: Column(
        children: [
          const Text('📸', style: TextStyle(fontSize: 26)),
          const SizedBox(height: 8),
          Text(l10n.growthArchiveTimelineEmpty(petName),
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 14.5, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 5),
          Text(l10n.growthArchiveTimelineEmptyBody,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, height: 1.5, color: AppColors.ink2)),
          const SizedBox(height: 14),
          FilledButton(
            key: const ValueKey('timelineEmptyCta'),
            onPressed: () => context.push('/publish?preset=growth-calendar'),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 12),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            ),
            child: Text('+ ${l10n.growthArchiveRecordFirstMoment}',
                style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }
}

/// 状态 A（已回答「我有宠物」）但未建档的**建档引导态**（Story 2.3 · FR-81 · UI 稿 A2）。
///
/// 复用 V1.0.0 既有建档引导（FR-0G，可跳过），**刻意不展示 FR-80 的游客种草内容** ——
/// 这类用户已经过了注册决策点，再给他种一次草只会造成体验割裂（AC1）。
///
/// 与状态 B/C（[_NonOwnerView]）是两种完全不同的人：对 A 催建档是对的（他说了有宠物、只是没填），
/// 对 B/C 催建档等于无视他刚在 onboarding 里给出的回答（UX-DR6）。
class _EmptyProfileView extends StatelessWidget {
  const _EmptyProfileView({required this.onCreate, this.onChangeStatus});

  final VoidCallback onCreate;

  /// 「改宠物状态」逃生入口：删档后仍留在状态 A 的用户靠它回 B/C（bug 20260702-237）。
  /// 档案加载失败态不给该入口（无从判断用户想改什么），故为可空。
  final VoidCallback? onChangeStatus;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          // A2 稿：🐾 + 标题 + 一句副文案（说明「为什么先建档」），再往下才是 CTA。
          EmptyState(
            title: l10n.growthArchiveEmptyTitle,
            message: l10n.growthArchiveEmptyBody,
          ),
          FilledButton(
            key: const ValueKey('growthCreateButton'),
            onPressed: onCreate,
            child: Text(l10n.growthArchiveEmptyCreate),
          ),
          if (onChangeStatus != null)
            TextButton(
              key: const ValueKey('growthChangeStatusButton'),
              onPressed: onChangeStatus,
              child: Text(l10n.growthArchiveChangeStatus),
            ),
        ],
      ),
    );
  }
}

class _NonOwnerView extends StatelessWidget {
  const _NonOwnerView({required this.onChangeStatus});

  final VoidCallback onChangeStatus;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabProfile), backgroundColor: AppColors.base),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(l10n.growthArchiveNonOwnerTitle, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.lg),
              FilledButton(
                key: const ValueKey('changeStatusButton'),
                onPressed: onChangeStatus,
                child: Text(l10n.growthArchiveChangeStatus),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
