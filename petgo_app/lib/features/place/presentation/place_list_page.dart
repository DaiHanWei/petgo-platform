import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/place_repository.dart';
import '../domain/place_summary.dart';
import 'place_labels.dart';

/// 场所列表页（V1.3.0 batch-b1 Story 1.1 · AC5/AC6 · UI 稿 A1/A3）。
///
/// 🔒 **游客可看**：对应地 `_controlledLocations` 里**没有** `/places`（Story 1.1 Dev Notes），
/// 后端 GET 也已对游客放行。本页不发任何 `/me` 请求。
///
/// <h2>本 story 的范围边界（别顺手加）</h2>
/// <ul>
///   <li>**类型 / 标签筛选 chips**（UI 稿 A1 顶部那两个 `Jenis ▾ / Tag ▾`）不在 1.1 的任何 AC 里；</li>
///   <li>**距离位与「开启定位」提示条**（A2 变体）归 Story 1.2 —— 本页 `distanceMeters` 恒为 null；</li>
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
    final async = ref.watch(placeListProvider);

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.placeListTitle, style: AppTypography.title),
      ),
      // 🔴 **下拉刷新包住全部三态**（空态 / 错误态 / 列表），不只包列表：
      // 冷启动播种前进来的用户看到的是空态，如果空态不能下拉，他只能退出重进
      // （空态的 CTA 要等 Story 1.3）。既有 `feed_view` / `refund_list_page` 也是这个约定。
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(placeListProvider.future),
        child: _body(context, ref, l10n, async),
      ),
    );
  }

  Widget _body(BuildContext context, WidgetRef ref, AppLocalizations l10n,
      AsyncValue<PlaceListResult> async) {
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
        onAction: () => ref.invalidate(placeListProvider),
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

/// 列表项（UX-DR2）：首图 + 名称 + 类型 + 标签（≤2 个 +N）+ 照片数/评论数/推荐计数。
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
                // 类型认不出来（后端加了新值而客户端没跟上）→ 整行省掉，不显示空占位。
                if (place.type != null) ...[
                  const SizedBox(height: AppSpacing.xxs),
                  Text(place.type!.label(l10n), style: AppTypography.caption),
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
/// 推荐 / 不推荐**在列表页就出数**（B1-D11）。⚠️ Story 1.1 期间后端恒为 0，
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
