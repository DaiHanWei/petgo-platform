import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/location_service.dart';
import '../data/place_repository.dart';
import '../domain/place_summary.dart';
import 'place_distance_format.dart';
import 'place_labels.dart';
import 'place_location_controller.dart';

/// 场所列表页（V1.3.0 batch-b1 Story 1.1 AC5/AC6 + Story 1.2 AC1/AC5 · UI 稿 A1/A2/A3）。
///
/// 🔒 **游客可看**：对应地 `_controlledLocations` 里**没有** `/places`（Story 1.1 Dev Notes），
/// 后端 GET 也已对游客放行。本页不发任何 `/me` 请求。
///
/// <h2>本 story 的范围边界（别顺手加）</h2>
/// <ul>
///   <li>**类型 / 标签筛选 chips**（UI 稿 A1 顶部那两个 `Jenis ▾ / Tag ▾`）不在 1.1/1.2 的任何 AC 里；</li>
///   <li>**AppBar 的「+ 标记场所」与空态的 CTA 按钮**归 Story 1.3（表单页还不存在）。
///       空态这里只给引导**文案**：挂一个点了跳不到任何地方的按钮比没有按钮更糟。</li>
///   <li>**点列表项进详情**归 Story 1.5 —— 详情页还不存在，所以列表项现在不可点。</li>
/// </ul>
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
      return _scaffold(l10n, const Center(child: CircularProgressIndicator()));
    }
    // 定位这条链路失败也不能让列表打不开 —— 退回按最新。
    final location = locationAsync.value ??
        const PlaceLocationState(permission: LocationPermissionOutcome.denied);

    final coords = location.coordinates;
    // 坐标按 ~110 m 归一后才做族键：GPS 米级抖动不该把页面打回 loading（见 placeListQueryFor）。
    final query = placeListQueryFor(coords?.latitude, coords?.longitude);
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
            Expanded(child: _body(context, ref, l10n, query, listAsync)),
          ],
        ),
      ),
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
    PlaceListQuery next = placeListRecentQuery;
    try {
      final loc = await ref.read(placeLocationProvider.future);
      next = placeListQueryFor(loc.coordinates?.latitude, loc.coordinates?.longitude);
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

  Widget _scaffold(AppLocalizations l10n, Widget body) => Scaffold(
        backgroundColor: AppColors.cream,
        appBar: AppBar(
          backgroundColor: AppColors.cream,
          scrolledUnderElevation: 0,
          title: Text(l10n.placeListTitle, style: AppTypography.title),
        ),
        body: body,
      );

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
      return _list(previous);
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
    if (previous.items.isEmpty) {
      // AC6 空态：复用既有 EmptyState，文案引导「标记一个场所」。
      return _scrollable(EmptyState(
        title: l10n.placeEmptyTitle,
        message: l10n.placeEmptyBody,
        icon: Icons.place_outlined,
      ));
    }
    return _list(previous);
  }

  Widget _list(PlaceListResult page) => ListView.separated(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
        itemCount: page.items.length,
        separatorBuilder: (_, _) => const Divider(
            height: 1, thickness: 1, indent: AppSpacing.lg, endIndent: AppSpacing.lg,
            color: AppColors.line2),
        itemBuilder: (context, i) => _PlaceRow(place: page.items[i]),
      );

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
class _PlaceRow extends StatelessWidget {
  const _PlaceRow({required this.place});

  final PlaceSummary place;

  /// 缩略图边长（逻辑像素）。
  static const double _thumbSize = 72;

  /// 标签最多显示 2 个，其余折成 `+N`（UX-DR2）。
  ///
  /// 🔴 截断在**客户端**做 —— 服务端全量下发。服务端截断会让详情页与列表页显示两套标签集。
  static const int _visibleTags = 2;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hidden = place.tags.length - _visibleTags;
    // 类型 · 距离 合成一行（UI 稿 A1「Kafe · 1.2 km」）。
    // 🔴 距离为 null（按最新分支）时整段省掉 —— 不显示「0 m」也不显示占位横线。
    final subtitle = [
      if (place.type != null) place.type!.label(l10n),
      if (place.distanceMeters != null)
        formatPlaceDistance(l10n, place.distanceMeters!),
    ].join(' · ');

    return Padding(
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
                  const SizedBox(height: AppSpacing.xs),
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
                const SizedBox(height: AppSpacing.xs),
                _Counts(place: place),
              ],
            ),
          ),
        ],
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
      child: const Icon(Icons.place_outlined, size: 26, color: AppColors.textTertiary),
    );
    final src = url;
    return ClipRRect(
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
    );
  }
}

/// 计数行：📷 照片数 · 💬 评论数 · 👍 推荐 · 👎 不推荐。
///
/// 推荐 / 不推荐**在列表页就出数**（B1-D11）。⚠️ Story 1.1/1.2 期间后端恒为 0，
/// 真实计数由 Story 1.8 接上 —— 所以这里**不做「为 0 就隐藏」**：隐藏的话 1.8 上线前
/// 没人能验证这两位渲染对不对，上线后又会突然多出两个图标。
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
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: 3),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.lineViolet),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(label, style: AppTypography.micro.copyWith(color: AppColors.mint700)),
    );
  }
}
