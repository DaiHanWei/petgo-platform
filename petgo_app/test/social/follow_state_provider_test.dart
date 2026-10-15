import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';

/// V1.3.4 follow · Story 2.1 · AC3.8：全局关注覆盖表的版本号 + pinnedSurface 规则（AD-7）。
class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'A', onboardingCompleted: true),
      );
}

const _author = 9;
const _none = FollowState.none;

void main() {
  late ProviderContainer c;
  late FollowStateNotifier n;

  setUp(() {
    c = ProviderContainer(overrides: [authControllerProvider.overrideWith(_Auth.new)]);
    n = c.read(followStateProvider.notifier);
  });
  tearDown(() => c.dispose());

  InlineFollowDisplay show(String surface, {required int snapshotEpoch, FollowState server = _none}) =>
      resolveInlineFollow(
        override: c.read(followStateProvider)[_author],
        server: server,
        snapshotEpoch: snapshotEpoch,
        surfaceKey: surface,
      );

  test('没有覆盖：按快照 —— 未关注显示 Ikuti、对方关注我显示 Ikuti Balik、已关注隐藏', () {
    final e = n.currentEpoch;
    expect(show('feed:item:1', snapshotEpoch: e), InlineFollowDisplay.follow);
    expect(show('feed:item:1', snapshotEpoch: e, server: const FollowState(iFollow: false, followsMe: true)),
        InlineFollowDisplay.followBack);
    expect(show('feed:item:1', snapshotEpoch: e, server: const FollowState(iFollow: true, followsMe: false)),
        InlineFollowDisplay.hidden);
  });

  test('在 A 卡上关注：A 卡显示 Mengikuti，同一用户其他位置立即隐藏', () {
    final snap = n.currentEpoch; // Feed 与详情的快照都早于这次关注
    n.set(_author, const FollowState(iFollow: true, followsMe: false), pinnedSurface: 'feed:item:1');

    expect(show('feed:item:1', snapshotEpoch: snap), InlineFollowDisplay.following);
    expect(show('feed:item:2', snapshotEpoch: snap), InlineFollowDisplay.hidden);
    expect(show('feed:pinned:1', snapshotEpoch: snap), InlineFollowDisplay.hidden);
    expect(show('detail:5', snapshotEpoch: snap), InlineFollowDisplay.hidden);
  });

  test('在 Mengikuti 上取关：本按钮变回 Ikuti / Ikuti Balik，其他位置重新显示', () {
    final snap = n.currentEpoch;
    n.set(_author, const FollowState(iFollow: true, followsMe: true), pinnedSurface: 'detail:5');
    n.set(_author, const FollowState(iFollow: false, followsMe: true), pinnedSurface: 'detail:5');

    expect(show('detail:5', snapshotEpoch: snap), InlineFollowDisplay.followBack);
    expect(show('feed:item:2', snapshotEpoch: snap), InlineFollowDisplay.followBack);
  });

  test('快照版本号更新（本位置刷新）后，本按钮也隐藏；未刷新的位置仍隐藏', () {
    final old = n.currentEpoch;
    n.set(_author, const FollowState(iFollow: true, followsMe: false), pinnedSurface: 'feed:item:1');
    // Feed 下拉刷新：发请求前读到的新版本号，服务端返回已关注。
    final refreshed = n.currentEpoch;
    const server = FollowState(iFollow: true, followsMe: false);

    expect(show('feed:item:1', snapshotEpoch: refreshed, server: server), InlineFollowDisplay.hidden);
    // 详情页还没刷新（旧快照 = 未关注）：覆盖对它仍生效 → 隐藏，不会冒出 Ikuti。
    expect(show('detail:5', snapshotEpoch: old), InlineFollowDisplay.hidden);
  });

  test('请求在路上时用户点了关注：发请求前读的版本号让覆盖继续生效', () {
    final before = n.currentEpoch; // Feed 发请求前读
    n.set(_author, const FollowState(iFollow: true, followsMe: false), pinnedSurface: 'feed:item:1');
    // 响应回来时服务端可能还没包含这次关注 → 快照仍是未关注，但覆盖更新 → 以用户刚才的操作为准。
    expect(show('feed:item:2', snapshotEpoch: before), InlineFollowDisplay.hidden);
  });

  test('restore：回滚到点击前那一条（原样，含版本号）；之前没有就删除', () {
    final snap = n.currentEpoch;
    n.restore(_author, null);
    expect(c.read(followStateProvider), isEmpty);

    n.set(_author, const FollowState(iFollow: true, followsMe: false), pinnedSurface: 'a');
    final prev = c.read(followStateProvider)[_author];
    n.set(_author, _none, pinnedSurface: 'a');
    n.restore(_author, prev);
    expect(identical(c.read(followStateProvider)[_author], prev), isTrue);
    expect(show('b', snapshotEpoch: snap), InlineFollowDisplay.hidden);
  });

  test('常驻位置 effective：覆盖新于快照才生效', () {
    n.set(_author, const FollowState(iFollow: true, followsMe: false));
    final after = n.currentEpoch;
    expect(n.effective(_author, _none, snapshotEpoch: after - 1).iFollow, isTrue);
    expect(n.effective(_author, _none, snapshotEpoch: after).iFollow, isFalse);
  });

  test('换账号：覆盖表清空，版本号不归零', () {
    n.set(_author, const FollowState(iFollow: true, followsMe: false));
    final epoch = n.currentEpoch;
    expect(epoch, greaterThan(0));

    c.read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(id: 2, nickname: 'B', onboardingCompleted: true));

    expect(c.read(followStateProvider), isEmpty);
    expect(c.read(followStateProvider.notifier).currentEpoch, epoch);
  });

  test('FollowState.fromJson：缺失 / null / 非 Map → none', () {
    expect(FollowState.fromJson(null), _none);
    expect(FollowState.fromJson('x'), _none);
    expect(FollowState.fromJson({'iFollow': true}), const FollowState(iFollow: true, followsMe: false));
  });
}
