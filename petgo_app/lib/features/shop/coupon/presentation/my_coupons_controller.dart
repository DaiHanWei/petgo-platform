/// 我的券列表状态（v1.4.2 shop Story 9.3 · AC2.8）：按页签分族、游标分页累积、下拉强刷、兑换成功本地插顶。
///
/// 🔴 为什么不是 `FutureProvider` + `invalidate`：游标分页是**累积**的，invalidate 会把已翻的页全丢掉
/// 并打回 loading（同 `place_comments_controller.dart` 范式）。
///
/// 🔴 App **不按 `status` 过滤**：可用页签里的 LOCKED（`lockedByPendingOrder`）、已过期隐藏等口径全在服务端，
/// 列表原样渲染（C-3）。
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/coupon_repository.dart';
import '../domain/user_coupon.dart';

class MyCouponsState {
  const MyCouponsState({
    required this.items,
    this.nextCursor,
    this.availableCount,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final List<UserCoupon> items;
  final String? nextCursor;

  /// 最近一次响应的可用张数（页签「Bisa dipakai ({n})」）。
  final int? availableCount;
  final bool loadingMore;
  final bool loadMoreFailed;

  bool get hasMore => nextCursor != null && nextCursor!.isNotEmpty;

  MyCouponsState copyWith({
    List<UserCoupon>? items,
    String? nextCursor,
    bool clearCursor = false,
    int? availableCount,
    bool? loadingMore,
    bool? loadMoreFailed,
  }) =>
      MyCouponsState(
        items: items ?? this.items,
        nextCursor: clearCursor ? null : (nextCursor ?? this.nextCursor),
        availableCount: availableCount ?? this.availableCount,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );

  static MyCouponsState fromPage(MyCouponPage p) =>
      MyCouponsState(items: p.items, nextCursor: p.nextCursor, availableCount: p.availableCount);
}

class MyCouponsController extends AsyncNotifier<MyCouponsState> {
  MyCouponsController(this.tab);

  final CouponTab tab;

  @override
  Future<MyCouponsState> build() async =>
      MyCouponsState.fromPage(await ref.read(couponRepositoryProvider).myCoupons(tab));

  /// 在途的「加载更多」：连触发两次复用同一请求，不把同一页追加两遍。
  Future<void>? _loadingMore;

  Future<void> loadMore() => _loadingMore ??= _loadMore().whenComplete(() => _loadingMore = null);

  Future<void> _loadMore() async {
    final current = state.value;
    if (current == null || !current.hasMore || current.loadingMore) return;
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
    final marker = state.value;
    try {
      final next =
          await ref.read(couponRepositoryProvider).myCoupons(tab, cursor: current.nextCursor);
      if (!ref.mounted) return;
      if (!identical(state.value, marker)) {
        _resetLoadingMore();
        return;
      }
      final seen = current.items.map((e) => e.userCouponToken).toSet();
      state = AsyncData(MyCouponsState(
        items: [...current.items, ...next.items.where((e) => !seen.contains(e.userCouponToken))],
        nextCursor: next.nextCursor,
        availableCount: next.availableCount ?? current.availableCount,
      ));
    } catch (_) {
      if (!ref.mounted) return;
      if (!identical(state.value, marker)) {
        _resetLoadingMore();
        return;
      }
      state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 在途期间状态被别处替换（插顶 / 刷新）后，这一页作废；若新状态仍挂着 loadingMore 则复位，
  /// 否则底部转圈会永远卡住（复审 #1）。
  void _resetLoadingMore() {
    final now = state.value;
    if (now != null && now.loadingMore) state = AsyncData(now.copyWith(loadingMore: false));
  }

  /// 重拉第一页。有数据时**不先置 loading**（下拉 / 兑换后台刷新不闪屏）；失败保留现有列表
  /// （只有首屏本就失败时才停在错误态）。
  Future<void> refresh() async {
    final hadData = state.hasValue;
    try {
      final page = await ref.read(couponRepositoryProvider).myCoupons(tab);
      if (!ref.mounted) return;
      state = AsyncData(MyCouponsState.fromPage(page));
    } catch (e, st) {
      if (!ref.mounted) return;
      if (!hadData) state = AsyncError(e, st);
    }
  }

  /// 首屏失败后重试（G5「Coba lagi」）：此时可以转圈。
  Future<void> retry() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () async => MyCouponsState.fromPage(await ref.read(couponRepositoryProvider).myCoupons(tab)));
  }

  /// 兑换成功（G6）：新券插到顶部（同 token 去重）。列表尚未加载成功时不动（随后的刷新会带上它）。
  void insertTop(UserCoupon c) {
    final current = state.value;
    if (current == null) return;
    // 🔴 复审 #1：插顶会让在途「加载更多」作废，这里必须同时清掉 loadingMore，否则底部转圈卡死。
    state = AsyncData(current.copyWith(loadingMore: false, items: [
      c,
      ...current.items.where((e) => e.userCouponToken != c.userCouponToken),
    ]));
  }
}

/// 我的券（按页签分族）。🔴 已登记 `resetUserScopedCaches`：换账号不能看到上个账号的券。
final myCouponsProvider =
    AsyncNotifierProvider.family<MyCouponsController, MyCouponsState, CouponTab>(
  MyCouponsController.new,
  isAutoDispose: true,
);
