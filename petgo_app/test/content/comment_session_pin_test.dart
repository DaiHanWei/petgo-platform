import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';

/// V1.3.0 批次 A · Story 2.5 · AC6（L0）：**自己刚发的评论必须可见**（AD-A8.8）。
///
/// Story 2.4 把一级评论默认序改成了热度序，于是 0 赞的新评论排在所有有赞评论之后。
/// 在一个有 10+ 条热门评论的帖子里，用户发完**看不到自己刚发的东西** ——
/// 这是改热度序必然带出来的副作用，不是偶发 bug。
///
/// 置顶本身的视觉属 L2；这里钉住三条约束里 L0 能钉的部分。
void main() {
  Comment comment(int id, {int likeCount = 0}) => Comment(
        id: id,
        authorId: 7,
        authorDeleted: false,
        body: '评论 $id',
        createdAt: DateTime.utc(2026, 9, 11),
        replyCount: 0,
        likeCount: likeCount,
      );

  ProviderContainer container() {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    return c;
  }

  group('置顶集合的生命周期', () {
    test('初始为空 —— 没发过评论就不该有任何置顶', () {
      expect(container().read(sessionPinnedCommentsProvider), isEmpty);
    });

    test('发一条 → 记下整条（不只是 id）', () {
      final c = container();
      c.read(sessionPinnedCommentsProvider.notifier).add(comment(42));

      final pinned = c.read(sessionPinnedCommentsProvider);
      expect(pinned.keys, [42]);
      // 🔴 存整条而不只是 id：热度序下 0 赞的新评论很可能**根本不在第一页**，
      // 只记 id 就只能"把已拿到的那条挪前面"，拿不到时依然看不见。
      expect(pinned[42]!.body, '评论 42');
    });

    test('发多条 → 保持发出顺序', () {
      final c = container();
      c.read(sessionPinnedCommentsProvider.notifier)
        ..add(comment(1))
        ..add(comment(2));

      expect(c.read(sessionPinnedCommentsProvider).keys, [1, 2]);
    });

    /// AC6：离开详情页后置顶失效 —— `CommentSection.dispose` 调的就是这个。
    /// 不清的话它会变成「永久置顶自己的评论」，那是另一个功能。
    test('clear → 置顶失效', () {
      final c = container();
      c.read(sessionPinnedCommentsProvider.notifier)
        ..add(comment(1))
        ..clear();

      expect(c.read(sessionPinnedCommentsProvider), isEmpty);
    });
  });

  group('🔴 排序与游标：置顶只动展示顺序', () {
    /// 这段逻辑与 `CommentSection._orderedTopLevel` 同构 —— 在这里独立验一遍算术，
    /// widget 层只剩渲染。
    List<Comment> ordered(List<Comment> fetched, Map<int, Comment> pinned) {
      if (pinned.isEmpty) return fetched;
      final byId = {for (final c in fetched) c.id: c};
      return [
        for (final id in pinned.keys) byId[id] ?? pinned[id]!,
        for (final c in fetched)
          if (!pinned.containsKey(c.id)) c,
      ];
    }

    test('服务端这一页有它 → 挪到首位，且用服务端那份（赞数更新）', () {
      final fetched = [comment(1, likeCount: 9), comment(42, likeCount: 3)];
      final out = ordered(fetched, {42: comment(42)});

      expect(out.map((c) => c.id), [42, 1]);
      expect(out.first.likeCount, 3, reason: '服务端那份更新，本地存的是发出时的 0 赞');
    });

    /// 🔴 本条是 AC6 的核心：服务端第一页**没有**它时，也必须看得见。
    test('服务端这一页没有它 → 用本地那份插到首位', () {
      final fetched = [comment(1, likeCount: 9), comment(2, likeCount: 8)];
      final out = ordered(fetched, {42: comment(42)});

      expect(out.map((c) => c.id), [42, 1, 2]);
    });

    test('没有置顶项时顺序原样不动（服务端热度序说了算）', () {
      final fetched = [comment(1, likeCount: 9), comment(2, likeCount: 8)];
      expect(ordered(fetched, const {}).map((c) => c.id), [1, 2]);
    });

    test('不产生重复：置顶项不会既在首位又留在原位', () {
      final fetched = [comment(1), comment(42), comment(2)];
      final out = ordered(fetched, {42: comment(42)});

      expect(out.map((c) => c.id), [42, 1, 2]);
      expect(out.where((c) => c.id == 42), hasLength(1));
    });

    /// 游标取的是**服务端返回那一页的最后一条**，与置顶后的展示顺序无关。
    /// 把插进来的项算进游标会制造重复/漏条 —— 这条断言把两者的独立性钉住。
    test('置顶不改变「服务端这一页的最后一条」是谁', () {
      final fetched = [comment(1), comment(2), comment(3)];
      final out = ordered(fetched, {42: comment(42)});

      expect(fetched.last.id, 3, reason: '游标来源是 fetched，不是 out');
      expect(out.last.id, 3);
      expect(out.first.id, 42, reason: '置顶项在展示顺序的最前，但它不在 fetched 里');
    });
  });
}
