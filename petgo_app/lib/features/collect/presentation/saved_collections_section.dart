import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../place/presentation/place_detail_page.dart';
import '../data/collect_repository.dart';
import '../domain/collect_target.dart';
import '../domain/collected_item.dart';
import 'collected_rows.dart';
import 'collection_list_controller.dart';

/// 「我的 → Disimpan」内容区（V1.4.0 ④ Story 2.4 · UI 稿 E2–E9）：分段胶囊 + 隐私横条 + 列表 / 空态 / 失败态。
///
/// 只 watch 当前子分类的 provider（另一个不预取）。可见条目点开去详情；不可见条目点开弹移除抽屉
/// （调普通取消收藏接口——是否发 `ContentUncollectedEvent` 由后端按 P-7 判断，App 不区分）。
class SavedCollectionsSection extends ConsumerWidget {
  const SavedCollectionsSection({super.key, required this.segment, required this.onSegmentChanged});

  final CollectTarget segment;
  final ValueChanged<CollectTarget> onSegmentChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 12),
        Row(
          children: [
            _SegPill(
              key: const ValueKey('savedSegPost'),
              label: l10n.profilePostsTitle,
              active: segment == CollectTarget.post,
              onTap: () => onSegmentChanged(CollectTarget.post),
            ),
            const SizedBox(width: 8),
            _SegPill(
              key: const ValueKey('savedSegPlace'),
              label: l10n.placeDetailTitle,
              active: segment == CollectTarget.place,
              onTap: () => onSegmentChanged(CollectTarget.place),
            ),
          ],
        ),
        switch (segment) {
          CollectTarget.post => const _PostsList(),
          CollectTarget.place => const _PlacesList(),
        },
      ],
    );
  }
}

/// 分段胶囊（规格照 `FeedTabRow._Tab`；那个组件绑 `FeedCategory`，不能直接复用）。
class _SegPill extends StatelessWidget {
  const _SegPill({super.key, required this.label, required this.active, required this.onTap});

  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      selected: active,
      button: true,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
          decoration: BoxDecoration(
            color: active ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: active ? AppColors.mint : AppColors.line, width: 1.5),
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: active ? Colors.white : AppColors.textSecondary)),
        ),
      ),
    );
  }
}

/// 隐私横条「🔒 Hanya kamu yang bisa melihat」（加载中 / 有数据 / 空态显示，失败态不显示）。
class _PrivacyHint extends StatelessWidget {
  const _PrivacyHint();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 4),
      child: Container(
        key: const ValueKey('savedPrivacyHint'),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(10)),
        child: Row(
          children: [
            const Icon(Icons.lock_outline_rounded, size: 13, color: AppColors.mint700),
            const SizedBox(width: 6),
            Expanded(
              child: Text(AppLocalizations.of(context).collectPrivacyHint,
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: AppColors.mint700)),
            ),
          ],
        ),
      ),
    );
  }
}

const Widget _loading = Padding(
  padding: EdgeInsets.all(AppSpacing.lg),
  child: Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
);

Widget _errorState(AppLocalizations l10n, VoidCallback onRetry) => KeyedSubtree(
      key: const ValueKey('savedError'),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
        child: EmptyState(
          icon: Icons.cloud_off_outlined,
          iconBackground: AppColors.cream2,
          title: l10n.collectLoadFailedTitle,
          message: l10n.collectLoadFailedHint,
          actionLabel: l10n.commonRetry,
          onAction: onRetry,
        ),
      ),
    );

Widget _emptyState(Key key, String title, String hint) => Padding(
      key: key,
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
      child: EmptyState(
        icon: Icons.bookmark_border_rounded,
        iconBackground: AppColors.cream2,
        iconColor: AppColors.mint,
        title: title,
        message: hint,
      ),
    );

/// 加载更多底栏（照 `pawcoin_page._LoadMoreFooter`）：失败保留已加载、底部重试，不回顶。
Widget _footer(AppLocalizations l10n, CollectionListState<Object?> s, VoidCallback loadMore) {
  if (s.loadingMore) return _loading;
  if (s.loadMoreFailed) {
    return Center(
      child: TextButton(key: const ValueKey('savedLoadMoreRetry'), onPressed: loadMore, child: Text(l10n.commonRetry)),
    );
  }
  if (s.hasMore) {
    return Center(
      child: TextButton(key: const ValueKey('savedLoadMore'), onPressed: loadMore, child: Text(l10n.collectLoadMore)),
    );
  }
  return const SizedBox.shrink();
}

/// 404 = 收藏行已不存在，结果等同移除（留着这一行只会让用户点了又报错）。
/// 后端取消接口本身幂等、正常不回 404；这里是防御（网关 / 旧后端），照 story AC3.4 口径按成功处理。
bool _isGone(Object e) => e is DioException && e.response?.statusCode == 404;

/// 不可见条目移除：抽屉的异步钩子里调取消收藏；成功关抽屉 + 本地删行 + Toast，失败抽屉不关可直接重试。
Future<void> _confirmRemove(
  BuildContext context, {
  required String title,
  required String message,
  required Future<void> Function() request,
  required VoidCallback onRemoved,
}) async {
  final l10n = AppLocalizations.of(context);
  // 抽屉关闭后 context 可能已失效 —— Toast 用提前取好的 root Overlay。
  final overlay = Overlay.of(context, rootOverlay: true);
  final removed = await showConfirmSheet(
    context,
    title: title,
    message: message,
    confirmLabel: l10n.collectRemoveConfirm,
    cancelLabel: l10n.commonCancel,
    icon: Icons.bookmark_remove_outlined,
    danger: true,
    confirmKey: const ValueKey('savedRemoveConfirm'),
    cancelKey: const ValueKey('savedRemoveCancel'),
    onConfirm: () async {
      try {
        await request();
        return true;
      } catch (e) {
        if (_isGone(e)) return true;
        // 抽屉开着：Toast 放顶部，免得压在按钮上。
        showAppToastOnOverlay(overlay, l10n.collectRemoveFailed, top: true);
        return false;
      }
    },
  );
  if (!removed || !context.mounted) return; // 期间 Disimpan 被拆（换账号 / 登录失效）→ 什么都不做
  onRemoved();
  showAppToastOnOverlay(overlay, l10n.collectRemovedToast);
}

class _PostsList extends ConsumerWidget {
  const _PostsList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(collectedPostsProvider);
    final ctrl = ref.read(collectedPostsProvider.notifier);
    // 信号重拉 / 重试期间保留已加载内容（不塌成转圈、不丢滚动位置）；只有从没拿到过数据才转圈 / 报错。
    final value = async.hasValue ? async.requireValue : null;
    return switch (async) {
      _ when value != null => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _PrivacyHint(),
            if (value.items.isEmpty && !value.hasMore)
              _emptyState(const ValueKey('savedPostsEmpty'), l10n.collectEmptyPostsTitle, l10n.collectEmptyPostsHint)
            else ...[
              for (final item in value.items)
                CollectedPostRowView(item: item, onTap: () => _open(context, ref, item)),
              _footer(l10n, value, ctrl.loadMore),
            ],
          ],
        ),
      _ when async.isLoading =>
        const Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [_PrivacyHint(), _loading]),
      _ => _errorState(l10n, ctrl.retry),
    };
  }

  void _open(BuildContext context, WidgetRef ref, CollectedPost item) {
    final available = item.showsAsAvailable;
    Analytics.capture('collection_item_opened', {'tab': 'post', 'is_unavailable': !available});
    if (available) {
      context.push('/content/${item.postId}');
      return;
    }
    final l10n = AppLocalizations.of(context);
    _confirmRemove(
      context,
      title: l10n.collectUnavailablePost,
      message: l10n.collectRemovePostMessage,
      request: () => ref.read(collectRepositoryProvider).uncollectPost(item.postId),
      onRemoved: () => ref.read(collectedPostsProvider.notifier).removeLocal(item.postId),
    );
  }
}

class _PlacesList extends ConsumerWidget {
  const _PlacesList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(collectedPlacesProvider);
    final ctrl = ref.read(collectedPlacesProvider.notifier);
    // 信号重拉 / 重试期间保留已加载内容（不塌成转圈、不丢滚动位置）；只有从没拿到过数据才转圈 / 报错。
    final value = async.hasValue ? async.requireValue : null;
    return switch (async) {
      _ when value != null => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _PrivacyHint(),
            if (value.items.isEmpty && !value.hasMore)
              _emptyState(
                  const ValueKey('savedPlacesEmpty'), l10n.collectEmptyPlacesTitle, l10n.collectEmptyPlacesHint)
            else ...[
              for (final item in value.items)
                CollectedPlaceRowView(item: item, onTap: () => _open(context, ref, item)),
              _footer(l10n, value, ctrl.loadMore),
            ],
          ],
        ),
      _ when async.isLoading =>
        const Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [_PrivacyHint(), _loading]),
      _ => _errorState(l10n, ctrl.retry),
    };
  }

  void _open(BuildContext context, WidgetRef ref, CollectedPlace item) {
    Analytics.capture('collection_item_opened', {'tab': 'place', 'is_unavailable': !item.available});
    if (item.available) {
      // 不带 from：入口归因由 collection_item_opened 承载（场所详情 from 白名单不动）。
      context.push(PlaceDetailPage.routeFor(item.placeToken));
      return;
    }
    final l10n = AppLocalizations.of(context);
    _confirmRemove(
      context,
      title: l10n.collectUnavailablePlace,
      message: l10n.collectRemovePlaceMessage,
      request: () => ref.read(collectRepositoryProvider).uncollectPlace(item.placeToken),
      onRemoved: () => ref.read(collectedPlacesProvider.notifier).removeLocal(item.placeToken),
    );
  }
}
