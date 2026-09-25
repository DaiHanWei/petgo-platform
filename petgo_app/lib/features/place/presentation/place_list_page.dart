import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../auth/domain/auth_guard.dart';
import '../data/location_service.dart';
import '../data/place_repository.dart';
import '../domain/place_list_filter.dart';
import '../domain/place_summary.dart';
import 'place_detail_page.dart';
import 'place_distance_format.dart';
import 'place_labels.dart';
import 'place_location_controller.dart';
import 'place_mark_page.dart';

/// 场所列表页（V1.3.0 batch-b1 Story 1.1 AC5/AC6 + Story 1.2 AC1/AC5 · UI 稿 A1/A2/A3）。
///
/// 🔒 **游客可看**：对应地 `_controlledLocations` 里**没有** `/places`（Story 1.1 Dev Notes），
/// 后端 GET 也已对游客放行。本页不发任何 `/me` 请求。
///
/// <h2>本 story 的范围边界（别顺手加）</h2>
/// <ul>
///   <li>**类型 / 标签筛选**（UI 稿 A1 顶部 `Jenis ▾ / Tag ▾`）由 Story 1.11 补上（决策 B1-D14）：
///       筛选在**服务端 SQL** 里做（截断 200 之前），客户端只送可重复的 `type` / `tag` 参数；
///       筛选只活在本次页面生命周期（[placeListFilterProvider] 是 autoDispose），**不持久化**。
///       稿里没有的排序切换、搜索框、按城市筛**不做**；</li>
///   <li>**AppBar 的「+ 标记场所」与空态的 CTA 按钮**归 Story 1.3（表单页还不存在）。
///       空态这里只给引导**文案**：挂一个点了跳不到任何地方的按钮比没有按钮更糟。</li>
///   <li>**点列表项进详情**（Story 1.5）已接上 —— `context.push` 到 `/places/{token}`。
///       🔴 用 `push` 而不是 `go`：详情是压在列表上的一层，要能返回列表（同 `_pushMarkForm` 的理由）。</li>
/// </ul>
/// 列表筛选的页面级状态（Story 1.11 · AC10）。
///
/// 🔴 `autoDispose`：退出列表页即清空（AC10「只在本次页面生命周期有效，不持久化」）。
/// 从列表 push 进详情时列表页仍挂着、仍在 watch，所以返回列表筛选还在 —— 这是期望行为。
class PlaceListFilterController extends Notifier<PlaceListFilter> {
  @override
  PlaceListFilter build() => PlaceListFilter.none;

  void setTypes(Set<PlaceType> v) => state = state.withTypes(v);
  void setTags(Set<PlaceTag> v) => state = state.withTags(v);
  void clear() => state = PlaceListFilter.none;
}

final placeListFilterProvider =
    NotifierProvider.autoDispose<PlaceListFilterController, PlaceListFilter>(
        PlaceListFilterController.new);

class PlaceListPage extends ConsumerWidget {
  const PlaceListPage({super.key});

  /// 路由路径。⚠️ 与 `app_router.dart` 的注册值同源，别在别处写字面量。
  static const String routePath = '/places';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final locationAsync = ref.watch(placeLocationProvider);

    // 定位态还在读（只是一次不弹窗的权限状态查询 + 可能一次取缓存定点）→ 先转圈，
    // 不要用「没有坐标」去发一次请求再用「有坐标」发第二次：那是两次网络请求换一次排序。
    if (locationAsync.isLoading && !locationAsync.hasValue) {
      return _scaffold(l10n, const Center(child: CircularProgressIndicator()),
          onMark: () => _openMarkForm(context, ref));
    }
    // 定位这条链路失败也不能让列表打不开 —— 退回按最新。
    final location = locationAsync.value ??
        const PlaceLocationState(permission: LocationPermissionOutcome.denied);

    final coords = location.coordinates;
    final filter = ref.watch(placeListFilterProvider);
    // 坐标按 ~110 m 归一后才做族键：GPS 米级抖动不该把页面打回 loading（见 placeListQueryFor）。
    // 筛选也进族键（Story 1.11 · AC10）：改筛选 = 新请求，同一组筛选命中缓存。
    final query = placeListQueryFor(coords?.latitude, coords?.longitude, filter: filter);
    final listAsync = ref.watch(placeListProvider(query));

    return _scaffold(
      l10n,
      // 🔴 **下拉刷新包住全部三态**（空态 / 错误态 / 列表），不只包列表：
      // 冷启动播种前进来的用户看到的是空态，如果空态不能下拉，他只能退出重进
      // （空态的 CTA 要等 Story 1.3）。既有 `feed_view` / `refund_list_page` 也是这个约定。
      RefreshIndicator(
        onRefresh: () => _refresh(ref),
        child: Column(
          children: [
            if (location.needsPermissionBanner)
              _LocationBanner(
                onEnable: () => _onEnableLocation(context, ref, location),
              ),
            // Story 1.11 · AC7：筛选条在「开启定位」提示条**之下**，有无定位两态都显示。
            _FilterBar(
              filter: filter,
              onTapType: () => _openTypeSheet(context, ref, filter),
              onTapTag: () => _openTagSheet(context, ref, filter),
            ),
            // 通栏分隔线（与列表分隔线同色，UI 稿 A1）。
            const Divider(height: 1, thickness: 1, color: AppColors.line),
            Expanded(child: _body(context, ref, l10n, query, listAsync)),
          ],
        ),
      ),
      onMark: () => _openMarkForm(context, ref),
    );
  }

  /// 下拉刷新：**定位先刷，再按新坐标刷列表**。
  ///
  /// 🔴 顺序是关键。定位与列表一起 invalidate、然后 await 旧族键的话：
  /// ① 用户在系统设置里开了权限回来下拉，排序仍然是按最新的（而他刚做的动作就是为了按距离排）；
  /// ② 新定点一旦落到另一个族键，await 的是**过期那个** —— 刷新动画绑在一个没人看的请求上，
  ///    而真正在显示的那个族键还是 loading，整屏列表被换成转圈。
  /// 所以这里先把定位结果拿到手，再算出新族键去刷它。
  Future<void> _refresh(WidgetRef ref) async {
    ref.invalidate(placeLocationProvider);
    // 筛选跟着族键走（Story 1.11）：刷新的是**当前筛选下**的那个族键，不是无筛选的。
    final filter = ref.read(placeListFilterProvider);
    PlaceListQuery next = placeListQueryFor(null, null, filter: filter);
    try {
      final loc = await ref.read(placeLocationProvider.future);
      next = placeListQueryFor(loc.coordinates?.latitude, loc.coordinates?.longitude,
          filter: filter);
    } catch (_) {
      // 定位链路失败不该让下拉刷新整个失败 —— 退回按最新照样刷。
    }
    ref.invalidate(placeListProvider(next));
    try {
      await ref.read(placeListProvider(next).future);
    } catch (_) {
      // 列表失败的提示由 build 里的 F13 分支给（保留旧数据 + SnackBar），
      // 这里吞掉是为了让 RefreshIndicator 正常收起动画而不是抛到 framework。
    }
  }

  Widget _scaffold(AppLocalizations l10n, Widget body, {VoidCallback? onMark}) => Scaffold(
        backgroundColor: AppColors.cream,
        appBar: AppBar(
          backgroundColor: AppColors.cream,
          scrolledUnderElevation: 0,
          title: Text(l10n.placeListTitle),
          actions: [
            if (onMark != null)
              IconButton(
                key: const ValueKey('placeListMarkAction'),
                tooltip: l10n.placeMarkEntry,
                onPressed: onMark,
                icon: const Icon(Icons.add),
              ),
          ],
        ),
        body: body,
      );

  /// 「标记场所」入口（Story 1.3）。
  ///
  /// 🔒 **游客走登录引导**：列表是只读的、对游客开放，但标记是写动作、后端要 JWT。
  /// 门控放在入口这一刻（`requireLogin`），而不是让游客进到表单填完才发现要登录。
  Future<void> _openMarkForm(BuildContext context, WidgetRef ref) async {
    requireLogin(
      ref,
      context,
      // 🔴 **用 onResume（命令式 push）而不是 location（声明式 go）**：
      // `RouteIntent.location` 走的是 `context.go`，而 `/places/new` 是 shell 之外的顶层路由 ——
      // `go` 会把整个栈换成它：没有返回按钮、没有底部导航、安卓返回键直接退出 App，
      // 表单成功后的 `pushReplacement` 替换掉的也是唯一一页（code-review 2026-09-15 抓到，
      // 与 V1.1.6 Story 2.4 名片深链踩过的是同一个坑）。
      pendingAction: RouteIntent(onResume: () {
        if (!context.mounted) return;
        _pushMarkForm(context);
      }),
      onAllowed: () => _pushMarkForm(context),
    );
  }

  /// 🔴 **不等返回值**（bug 514）：表单提交成功后是 `pushReplacement` 到新场所详情页，
  /// 这里 push 的 future 永远不会完成（go_router 不完成被替换页的 completer）。
  /// 列表 / 定位的刷新由表单页在成功那一刻 invalidate（列表页仍挂在栈底，会立即重拉），
  /// 用户从详情返回时看到的就是最新列表。
  void _pushMarkForm(BuildContext context) {
    context.push(PlaceMarkPage.routePath);
  }

  /// 「开启定位」（AC5）。
  ///
  /// 🔴 **只有这里会弹系统权限窗**（控制器的 `build` 只读状态不弹窗）——「拒绝后不反复弹」
  /// 因此是结构保证的，不靠任何标记位。永久拒绝 → 引导去系统设置，再 `request()`
  /// 系统也不会弹，那才是真正的死路。
  Future<void> _onEnableLocation(
      BuildContext context, WidgetRef ref, PlaceLocationState current) async {
    final controller = ref.read(placeLocationProvider.notifier);
    if (current.mustGoToSettings) {
      await controller.openSettings();
      return;
    }
    final outcome = await controller.requestPermission();
    if (!context.mounted) return;
    if (outcome == LocationPermissionOutcome.permanentlyDenied) {
      // 提示条保留（needsPermissionBanner 仍为 true），按钮下次点就是「去设置」。
      final l10n = AppLocalizations.of(context);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(l10n.placeLocationDeniedHint)));
    }
  }

  Widget _body(BuildContext context, WidgetRef ref, AppLocalizations l10n,
      PlaceListQuery query, AsyncValue<PlaceListResult> async) {
    // 🔴 F13「已加载内容保留」：刷新失败时**先看有没有旧数据**。
    // 直接用 `async.when` 会让下拉刷新失败把已经渲染好的一整屏列表换成错误态 ——
    // 那正是 F13 要避免的事。有旧数据就继续显示旧数据，失败只用 SnackBar 说一声。
    final previous = async.value;
    if (async.hasError && previous != null) {
      _toastRefreshFailure(context, l10n);
      return _list(previous, sortedByRecent: query.lat == null);
    }
    if (async.hasError) {
      // 首次加载就失败（没有任何旧数据）：明确文案 + 重试入口，不是一个空白页。
      return _scrollable(EmptyState(
        title: l10n.placeErrorTitle,
        message: l10n.placeErrorBody,
        icon: Icons.cloud_off_rounded,
        actionLabel: l10n.placeRetry,
        onAction: () => ref.invalidate(placeListProvider(query)),
      ));
    }
    if (previous == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (previous.items.isEmpty && query.filter.isNotEmpty) {
      // Story 1.11 · AC11：**有筛选且为空** ≠ 「一个场所都没有」。
      // 🔴 不显示「标记一个场所」引导（那是全库为空的语义），给「清空筛选」出口。
      return _scrollable(EmptyState(
        key: const ValueKey('placeFilterEmpty'),
        title: l10n.placeFilterEmptyTitle,
        icon: Icons.filter_alt_off_outlined,
        actionLabel: l10n.placeFilterClearAll,
        onAction: () => ref.read(placeListFilterProvider.notifier).clear(),
      ));
    }
    if (previous.items.isEmpty) {
      // Story 1.1 AC6 空态：复用既有 EmptyState，文案引导「标记一个场所」。
      // Story 1.3 起 CTA 真的能点了（表单页已存在）—— 1.1 交付时刻意没挂按钮，
      // 挂一个点了跳不到任何地方的按钮比没有按钮更糟。
      // UI 稿 A3：设计稿是一枚红色 📍（无圆底）。EmptyState 的图标恒为灰色，
      // 所以这里隐藏它的图标、在上方自己放 emoji，标题/副文/CTA 仍复用 EmptyState。
      return _scrollable(Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('📍',
                key: ValueKey('placeEmptyPin'),
                style: TextStyle(fontSize: 48, height: 1.1)),
            const SizedBox(height: AppSpacing.md),
            EmptyState(
              title: l10n.placeEmptyTitle,
              message: l10n.placeEmptyBody,
              hideIcon: true,
              actionLabel: l10n.placeMarkEntry,
              onAction: () => _openMarkForm(context, ref),
            ),
          ],
        ),
      ));
    }
    return _list(previous, sortedByRecent: query.lat == null);
  }

  /// [sortedByRecent]：本次是按最新排序（无坐标）—— 行副标题的距离位改写「最新」（UI 稿 A2）。
  Widget _list(PlaceListResult page, {required bool sortedByRecent}) => ListView.separated(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
        itemCount: page.items.length,
        // UI 稿 A1：分隔线通栏（不缩进）+ 中性 line 色。
        separatorBuilder: (_, _) =>
            const Divider(height: 1, thickness: 1, color: AppColors.line),
        itemBuilder: (context, i) {
          final place = page.items[i];
          return _PlaceRow(
            place: place,
            sortedByRecent: sortedByRecent,
            // 🔒 **详情对游客开放**（后端 GET 已放行）→ 这里不套 requireLogin。
            onTap: () => context.push(
                PlaceDetailPage.routeFor(place.token, from: kPlaceDetailFromList)),
          );
        },
      );

  /// 类型筛选弹层（Story 1.11 · AC8）。全部 7 类，多选。
  Future<void> _openTypeSheet(
      BuildContext context, WidgetRef ref, PlaceListFilter current) async {
    final l10n = AppLocalizations.of(context);
    final picked = await _showFilterSheet<PlaceType>(
      context,
      sheetKey: const ValueKey('placeFilterTypeSheet'),
      title: l10n.placeFilterType,
      options: PlaceType.values,
      labelOf: (t) => t.label(l10n),
      initial: current.types,
    );
    if (picked == null) return; // 下拉 / 点遮罩关掉 = 不改
    ref.read(placeListFilterProvider.notifier).setTypes(picked);
  }

  /// 标签筛选弹层（Story 1.11 · AC8）。全部 6 个，多选（服务端按「且」筛）。
  Future<void> _openTagSheet(
      BuildContext context, WidgetRef ref, PlaceListFilter current) async {
    final l10n = AppLocalizations.of(context);
    final picked = await _showFilterSheet<PlaceTag>(
      context,
      sheetKey: const ValueKey('placeFilterTagSheet'),
      title: l10n.placeFilterTag,
      options: PlaceTag.values,
      labelOf: (t) => t.label(l10n),
      initial: current.tags,
    );
    if (picked == null) return;
    ref.read(placeListFilterProvider.notifier).setTags(picked);
  }

  /// 把不满屏的空态 / 错误态变成可滚动内容 —— 否则 [RefreshIndicator] 收不到下拉手势。
  Widget _scrollable(Widget child) => LayoutBuilder(
        builder: (context, c) => SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: c.maxHeight),
            child: child,
          ),
        ),
      );

  /// 刷新失败只提示一声，不动已渲染的列表（F13）。
  void _toastRefreshFailure(BuildContext context, AppLocalizations l10n) {
    // build 期间不能直接开 SnackBar。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(l10n.placeErrorTitle)));
    });
  }
}

/// 筛选条（Story 1.11 · AC7/AC9 · UI 稿 A1）：`Jenis ▾` + `Tag ▾` 两个 chip。
class _FilterBar extends StatelessWidget {
  const _FilterBar(
      {required this.filter, required this.onTapType, required this.onTapTag});

  final PlaceListFilter filter;
  final VoidCallback onTapType;
  final VoidCallback onTapTag;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, AppSpacing.sm),
      child: Row(
        children: [
          _FilterChip(
            key: const ValueKey('placeFilterTypeChip'),
            label: l10n.placeFilterType,
            count: filter.types.length,
            onTap: onTapType,
          ),
          const SizedBox(width: AppSpacing.sm),
          _FilterChip(
            key: const ValueKey('placeFilterTagChip'),
            label: l10n.placeFilterTag,
            count: filter.tags.length,
            onTap: onTapTag,
          ),
        ],
      ),
    );
  }
}

/// 单个筛选 chip（AC9）：未选 = 白底 + line 描边 + 次级字色；
/// 已选 = mintTint 底 + 品牌紫字 + 数量（`Jenis · 2 ▾`）。
class _FilterChip extends StatelessWidget {
  const _FilterChip(
      {super.key, required this.label, required this.count, required this.onTap});

  final String label;
  final int count;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final selected = count > 0;
    final color = selected ? AppColors.mint700 : AppColors.textSecondary;
    return Semantics(
      button: true,
      selected: selected,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(999),
        child: ConstrainedBox(
          // 44 高热区（UX-DR16）。
          constraints: const BoxConstraints(minHeight: 44),
          child: Center(
            widthFactor: 1,
            child: Container(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.md, vertical: 6),
              decoration: BoxDecoration(
                color: selected ? AppColors.mintTint : AppColors.surface,
                border: Border.all(color: selected ? AppColors.mint : AppColors.line),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(selected ? '$label · $count' : label,
                      style: AppTypography.caption.copyWith(
                          color: color,
                          fontWeight: selected ? FontWeight.w700 : FontWeight.w400)),
                  const SizedBox(width: 2),
                  Icon(Icons.arrow_drop_down_rounded, size: 18, color: color),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 筛选底部弹层（AC8）：多选勾选 + 底部「Reset」「Terapkan」。
///
/// 返回值：`null` = 用户下拉 / 点遮罩关掉（不改筛选）；否则为新选择（Reset 返回空集）。
/// 🔴 「Reset」**清空该维度并立即应用**（关弹层）—— 只清勾选不应用的话，用户还得再点一次
/// 「Terapkan」，而「清空」这个意图本身已经很明确。
/// 样式对齐现有 `showModalBottomSheet` 用法（顶部圆角 24 + 手柄）。无输入框，不涉及键盘避让。
Future<Set<T>?> _showFilterSheet<T>(
  BuildContext context, {
  required Key sheetKey,
  required String title,
  required List<T> options,
  required String Function(T) labelOf,
  required Set<T> initial,
}) {
  return showModalBottomSheet<Set<T>>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
    builder: (ctx) => _FilterSheet<T>(
      key: sheetKey,
      title: title,
      options: options,
      labelOf: labelOf,
      initial: initial,
    ),
  );
}

class _FilterSheet<T> extends StatefulWidget {
  const _FilterSheet({
    super.key,
    required this.title,
    required this.options,
    required this.labelOf,
    required this.initial,
  });

  final String title;
  final List<T> options;
  final String Function(T) labelOf;
  final Set<T> initial;

  @override
  State<_FilterSheet<T>> createState() => _FilterSheetState<T>();
}

class _FilterSheetState<T> extends State<_FilterSheet<T>> {
  late final Set<T> _selected = {...widget.initial};

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      top: false,
      child: ConstrainedBox(
        // 选项不多（≤7），但给个上限防小屏 + 大字号溢出。
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: AppSpacing.sm),
            Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.line,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            Text(widget.title,
                style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: AppSpacing.sm),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final o in widget.options)
                    CheckboxListTile(
                      key: ValueKey('placeFilterOption-$o'),
                      value: _selected.contains(o),
                      onChanged: (v) => setState(() {
                        if (v == true) {
                          _selected.add(o);
                        } else {
                          _selected.remove(o);
                        }
                      }),
                      title: Text(widget.labelOf(o), style: AppTypography.body),
                      activeColor: AppColors.mint,
                      controlAffinity: ListTileControlAffinity.trailing,
                      contentPadding:
                          const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, AppSpacing.md),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const ValueKey('placeFilterReset'),
                      onPressed: () => Navigator.of(context).pop(<T>{}),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(44),
                        foregroundColor: AppColors.textSecondary,
                        side: const BorderSide(color: AppColors.line),
                      ),
                      child: Text(l10n.placeFilterReset),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: FilledButton(
                      key: const ValueKey('placeFilterApply'),
                      onPressed: () => Navigator.of(context).pop({..._selected}),
                      style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(44),
                        backgroundColor: AppColors.mint,
                      ),
                      child: Text(l10n.placeFilterApply),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 无定位权限时的顶部提示条（Story 1.2 AC5 · UI 稿 A2）。
///
/// ⚠️ **拒绝之后提示条要保留**（不是消失）：它同时是「为什么这个列表不是按距离排的」的解释。
class _LocationBanner extends StatelessWidget {
  const _LocationBanner({required this.onEnable});

  final VoidCallback onEnable;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      margin: const EdgeInsets.fromLTRB(
          AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, 0),
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md, vertical: AppSpacing.sm),
      decoration: BoxDecoration(
        color: AppColors.goldTint,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          const Icon(Icons.place_outlined, size: 16, color: AppColors.tipsBadgeText),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(l10n.placeLocationBanner,
                style: AppTypography.caption
                    .copyWith(color: AppColors.tipsBadgeText)),
          ),
          // 44×44 热区（UX-DR16）：不裸露文字当按钮。
          TextButton(
            key: const ValueKey('placeEnableLocation'),
            onPressed: onEnable,
            style: TextButton.styleFrom(
              minimumSize: const Size(44, 44),
              foregroundColor: AppColors.tipsBadgeText,
            ),
            child: Text(l10n.placeLocationEnable,
                style: AppTypography.caption.copyWith(
                    color: AppColors.tipsBadgeText, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }
}

/// 列表项（UX-DR2）：首图 + 名称 + 类型 + 距离 + 标签（≤2 个 +N）+ 照片数/评论数/推荐计数。
///
/// 整行可点进详情（Story 1.5）—— 热区是整行而不是名称文字（UX-DR16）。
class _PlaceRow extends StatelessWidget {
  const _PlaceRow(
      {required this.place, required this.sortedByRecent, required this.onTap});

  final PlaceSummary place;

  /// 列表按最新排序（无定位）—— 距离位显示「最新」而不是留空（UI 稿 A2「Kafe · Terbaru」）。
  final bool sortedByRecent;
  final VoidCallback onTap;

  /// 缩略图边长（逻辑像素）。UI 稿 A1 为 56。
  static const double _thumbSize = 56;

  /// 标签最多显示 2 个，其余折成 `+N`（UX-DR2）。
  ///
  /// 🔴 截断在**客户端**做 —— 服务端全量下发。服务端截断会让详情页与列表页显示两套标签集。
  static const int _visibleTags = 2;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hidden = place.tags.length - _visibleTags;
    // 类型 · 距离 合成一行（UI 稿 A1「Kafe · 1.2 km」）。
    // 🔴 距离为 null 时不显示「0 m」也不显示占位横线：按最新分支写「最新」
    // （同时解释了这个列表为什么不是按远近排的），其它情况整段省掉。
    final subtitle = [
      if (place.type != null) place.type!.label(l10n),
      if (place.distanceMeters != null)
        formatPlaceDistance(l10n, place.distanceMeters!)
      else if (sortedByRecent)
        l10n.placeSortRecent,
    ].join(' · ');

    return InkWell(
      key: ValueKey('placeRow-${place.token}'),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg, vertical: AppSpacing.md),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Thumb(url: place.firstPhotoUrl, size: _thumbSize),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(place.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                  if (subtitle.isNotEmpty) ...[
                    const SizedBox(height: AppSpacing.xxs),
                    Text(subtitle, style: AppTypography.caption),
                  ],
                  if (place.tags.isNotEmpty) ...[
                    const SizedBox(height: AppSpacing.sm),
                    Wrap(
                      spacing: AppSpacing.xs,
                      runSpacing: AppSpacing.xs,
                      children: [
                        for (final t in place.tags.take(_visibleTags))
                          _TagChip(label: t.label(l10n)),
                        if (hidden > 0) _TagChip(label: l10n.placeTagOverflow(hidden)),
                      ],
                    ),
                  ],
                  const SizedBox(height: AppSpacing.sm),
                  _Counts(place: place),
                ],
              ),
            ),
            // UI 稿 A1 行尾无 chevron：整行可点本身已足够表达"可进入"。
          ],
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url, required this.size});

  final String? url;
  final double size;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      width: size,
      height: size,
      color: AppColors.cream2,
      alignment: Alignment.center,
      child: const Icon(Icons.place_outlined, size: 22, color: AppColors.textTertiary),
    );
    final src = url;
    // UI 稿 A1：缩略图 1px 浅紫描边，画在前景层（盖在图片边缘上），不改变 56 的外尺寸。
    return Container(
      foregroundDecoration: BoxDecoration(
        border: Border.all(color: AppColors.lineViolet),
        borderRadius: BorderRadius.circular(10),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: src == null
            ? placeholder
            // 死链 / 网络失败一律回落占位，不留白块（同三卡面照片框的 errorBuilder 处理）。
            : AppImage.widget(
                src,
                width: size,
                height: size,
                thumbWidth: (size * MediaQuery.devicePixelRatioOf(context)).round(),
                errorBuilder: (_, _, _) => placeholder,
              ),
      ),
    );
  }
}

/// 计数行：📷 照片数 · 💬 评论数 · 👍 推荐 · 👎 不推荐。
///
/// 推荐 / 不推荐**在列表页就出数**（B1-D11）—— Story 1.8 起是真实计数
/// （服务端 Redis 计数器 + DB 回算自愈，AD-9）。
///
/// 🔴 **只展示、不参与排序**（AC4 / PRD ③「先积累数据」）：
/// 列表顺序永远只由「距离」或「最新」决定。这里也**没有**差评警示标、没有降权 ——
/// 在数据攒起来之前按评价决定谁被看见，等于用一个没人验证过的阈值做产品决策。
///
/// ⚠️ **不做「为 0 就隐藏」**：一个新场所四个数字都是 0 是正常状态，
/// 隐藏会让它的卡片比别人矮一截，看起来像是加载失败。
class _Counts extends StatelessWidget {
  const _Counts({required this.place});

  final PlaceSummary place;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _CountItem(icon: Icons.photo_library_outlined, value: place.photoCount),
        _CountItem(icon: Icons.chat_bubble_outline_rounded, value: place.commentCount),
        _CountItem(icon: Icons.thumb_up_outlined, value: place.recommendCount),
        _CountItem(icon: Icons.thumb_down_outlined, value: place.notRecommendCount),
      ],
    );
  }
}

class _CountItem extends StatelessWidget {
  const _CountItem({required this.icon, required this.value});

  final IconData icon;
  final int value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: AppSpacing.md),
      child: Row(
        children: [
          Icon(icon, size: 13, color: AppColors.textTertiary),
          const SizedBox(width: AppSpacing.xxs),
          Text('$value', style: AppTypography.micro),
        ],
      ),
    );
  }
}

class _TagChip extends StatelessWidget {
  const _TagChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: 5),
      decoration: BoxDecoration(
        // UI 稿 A1：中性灰描边 + 次级文字色（标签是属性说明，不该抢品牌紫的注意力）。
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(label, style: AppTypography.micro.copyWith(color: AppColors.textSecondary)),
    );
  }
}
