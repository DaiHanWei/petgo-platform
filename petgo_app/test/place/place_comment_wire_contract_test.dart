import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';

/// V1.3.0 batch-b1 Story 1.7 · L0：场所评论接口的**线上契约**（CROSS-STORY C5）。
///
/// ⚠️ 用例里的 JSON 是**后端真实响应的字段名**（`PlaceCommentResponse`，
/// 由 `PlaceCommentResponseContractTest` 同步钉死）。改后端字段名而不改这里，这些用例会红。
void main() {
  const wire = {
    'id': 7,
    'authorId': 4021,
    'authorNickname': 'Budi',
    'authorAvatarUrl': 'https://cdn.example/oss/avatar-4021.jpg',
    'authorDeleted': false,
    'body': 'Enak buat kerja sambil bawa anjing!',
    'attitude': 'RECOMMEND',
    'createdAt': '2026-09-15T08:00:00Z',
    'moderationStatus': 'VISIBLE',
    'mine': false,
  };

  group('评论条目的字段名必须与客户端解析的一致', () {
    test('真实响应能解析出全部非默认值', () {
      final c = PlaceComment.fromJson(Map<String, dynamic>.from(wire));

      expect(c.id, 7, reason: '🔴 解析出 0 的话删除按钮会去删一条 id=0 的评论');
      expect(c.authorId, 4021);
      expect(c.authorNickname, 'Budi');
      expect(c.authorAvatarUrl, 'https://cdn.example/oss/avatar-4021.jpg');
      expect(c.authorDeleted, isFalse);
      expect(c.body, 'Enak buat kerja sambil bawa anjing!');
      expect(c.attitude, PlaceCommentAttitude.recommend);
      expect(c.createdAt, isNotNull);
      expect(c.moderation, PlaceCommentModeration.visible);
      expect(c.mine, isFalse);
    });

    /// 🔴 AC3：没表态时服务端**省略** attitude 键 → 必须是 null，不能兜底成某一边。
    test('缺 attitude 解析为 null，不兜底成推荐', () {
      final json = Map<String, dynamic>.from(wire)..remove('attitude');
      expect(PlaceComment.fromJson(json).attitude, isNull);
    });

    test('不认识的 attitude 值也解析为 null（而不是猜一边）', () {
      final c = PlaceComment.fromJson(
          {...wire, 'attitude': 'SOMEWHAT_OK'}.cast<String, dynamic>());
      expect(c.attitude, isNull,
          reason: '猜一边会把一个未知立场算进推荐数里，而那个数字是要给运营看的');
    });

    test('NOT_RECOMMEND 能解析出来', () {
      final c = PlaceComment.fromJson(
          {...wire, 'attitude': 'NOT_RECOMMEND'}.cast<String, dynamic>());
      expect(c.attitude, PlaceCommentAttitude.notRecommend);
    });

    /// 🔴 审核态：非 VISIBLE 只会下发给作者本人 → 前端挂「仅你可见」。
    test('挂起 / 下架 / 被拒都要标为仅本人可见', () {
      for (final s in ['UNDER_REVIEW', 'TAKEN_DOWN', 'REJECTED']) {
        final c = PlaceComment.fromJson({...wire, 'moderationStatus': s}
            .cast<String, dynamic>());
        expect(c.moderation.onlyVisibleToMe, isTrue, reason: '$s 应挂「仅你可见」');
      }
    });

    /// 认不出的审核态一律当 VISIBLE —— 那是**最不会误导**的方向：
    /// 当成"仅你可见"会给一条正常评论挂上莫须有的灰标签。
    test('未知审核态回落到 visible（不挂莫须有的灰标签）', () {
      final c = PlaceComment.fromJson(
          {...wire, 'moderationStatus': 'BRAND_NEW'}.cast<String, dynamic>());
      expect(c.moderation, PlaceCommentModeration.visible);
      expect(c.moderation.onlyVisibleToMe, isFalse);
    });

    /// 🛡 NFR-8：注销作者 → 昵称/头像为 null，且**头像不可点**。
    test('注销作者：匿名化且不可点', () {
      final json = Map<String, dynamic>.from(wire)
        ..remove('authorNickname')
        ..remove('authorAvatarUrl')
        ..['authorDeleted'] = true;
      final c = PlaceComment.fromJson(json);
      expect(c.authorNickname, isNull);
      expect(c.authorAvatarUrl, isNull);
      expect(c.authorTappable, isFalse,
          reason: '🔴 可点的话会跳到一个已注销用户的主页');
    });

    test('mine=true 时才认为是本人的评论（删除入口据此显示）', () {
      final c = PlaceComment.fromJson({...wire, 'mine': true}.cast<String, dynamic>());
      expect(c.mine, isTrue);
    });
  });

  group('分页信封', () {
    test('items / nextCursor / hasMore / total 都能解析', () {
      final page = PlaceCommentPage.fromJson({
        'items': [Map<String, dynamic>.from(wire)],
        'nextCursor': 'abc',
        'hasMore': true,
        'total': 12,
      });
      expect(page.items, hasLength(1));
      expect(page.nextCursor, 'abc');
      expect(page.hasMore, isTrue);
      expect(page.total, 12);
    });

    test('空评论区解析成空列表 + total 0（走空态，不是错误态）', () {
      final page = PlaceCommentPage.fromJson(
          const {'items': <Object>[], 'hasMore': false, 'total': 0});
      expect(page.items, isEmpty);
      expect(page.total, 0);
      expect(page.nextCursor, isNull);
    });

    /// 拿不到 total 就退回"这一页有几条"—— 比显示 0 诚实（0 会和空态混淆）。
    test('缺 total 时退回本页条数', () {
      final page = PlaceCommentPage.fromJson({
        'items': [Map<String, dynamic>.from(wire)],
        'hasMore': false,
      });
      expect(page.total, 1);
    });

    test('append 累积前后两页，游标与 total 取新的那一页', () {
      final first = PlaceCommentPage.fromJson({
        'items': [Map<String, dynamic>.from(wire)],
        'nextCursor': 'c1',
        'hasMore': true,
        'total': 2,
      });
      final second = PlaceCommentPage.fromJson({
        'items': [
          {...wire, 'id': 8}
        ],
        'hasMore': false,
        'total': 2,
      });

      final merged = first.append(second);
      expect(merged.items.map((e) => e.id), [7, 8]);
      expect(merged.hasMore, isFalse);
      expect(merged.nextCursor, isNull);
      expect(merged.total, 2);
    });
  });

  /// 🔴 AC2 的客户端一侧：域模型里不许长出回复的概念。
  test('域模型里没有 parentId / replies / replyCount', () {
    final json = Map<String, dynamic>.from(wire)
      ..['parentId'] = 1
      ..['replyCount'] = 3;
    // 多余键被忽略、解析不崩（宽进严出），而且没有任何地方能读到它们。
    final c = PlaceComment.fromJson(json);
    expect(c.id, 7);
    expect(c.body, isNotEmpty);
  });
}
