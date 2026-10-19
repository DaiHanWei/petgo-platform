import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/gift_repository.dart';
import '../domain/gift_showcase.dart';

/// 粉丝榜分页状态（V1.4.0 gift Story 2.2 · AC3.2 / E7）。
class GiftSupportersState {
  const GiftSupportersState({
    required this.items,
    this.nextCursor,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final List<GiftSupporter> items;
  final String? nextCursor;
  final bool loadingMore;

  /// 下一页失败：已加载的行保留，底部行内「Gagal memuat · Coba lagi」只重试下一页（E7）。
  final bool loadMoreFailed;

  bool get hasMore => nextCursor != null;

  GiftSupportersState copyWith({bool? loadingMore, bool? loadMoreFailed}) => GiftSupportersState(
        items: items,
        nextCursor: nextCursor,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// 粉丝榜 controller（照 `PawCoinController` / `PostLikersController` 的加载更多写法）。
/// 首页失败 = provider 错误态（榜区块内行内重试，[retry]）；下一页失败 = [GiftSupportersState.loadMoreFailed]。
class GiftSupportersController extends AsyncNotifier<GiftSupportersState> {
  GiftSupportersController(this.petId);

  final int petId;

  /// build 代际：build 重跑后，旧的 loadMore 结果据此丢弃。
  int _gen = 0;

  @override
  Future<GiftSupportersState> build() async {
    _gen++;
    final page = await ref.read(giftRepositoryProvider).getSupporters(petId);
    return GiftSupportersState(items: page.items, nextCursor: page.nextCursor);
  }

  Future<void> loadMore() async {
    // 刷新（invalidate，如送达后）进行中：state 是带旧值的 AsyncLoading —— 此时用旧游标续页会与新首页并发、
    // 旧页结果可能晚到盖掉新首页（重复 / 过期行），且 AsyncData 覆盖会吞掉加载态。等新首页落定再续。
    if (state.isLoading) return;
    final cur = state.value;
    if (cur == null || !cur.hasMore || cur.loadingMore) return;
    state = AsyncData(cur.copyWith(loadingMore: true, loadMoreFailed: false));
    final gen = _gen;
    try {
      final page = await ref.read(giftRepositoryProvider).getSupporters(petId, cursor: cur.nextCursor);
      if (!ref.mounted || gen != _gen) return;
      state = AsyncData(GiftSupportersState(
        items: [...cur.items, ...page.items],
        nextCursor: page.nextCursor,
      ));
    } catch (_) {
      if (!ref.mounted || gen != _gen) return;
      state = AsyncData(cur.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 首页失败后的重试（重新 build）。失败由 provider 的 error 态承载，这里吞掉。
  Future<void> retry() async {
    if (!ref.mounted) return;
    ref.invalidateSelf();
    try {
      await future;
    } catch (_) {}
  }
}

/// autoDispose（同 [petGiftShowcaseProvider]：换账号不吃旧缓存）；`retry: null` 关掉 Riverpod 3 自动重试。
final giftSupportersProvider =
    AsyncNotifierProvider.autoDispose.family<GiftSupportersController, GiftSupportersState, int>(
  GiftSupportersController.new,
  retry: (_, _) => null,
);
