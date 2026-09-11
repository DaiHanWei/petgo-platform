import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/comment.dart';

/// V1.3.0 批次 A · Story 2.4（L0，客户端侧）：评论点赞的**乐观更新**（FR-114 · AD-A7）。
///
/// 服务端点赞端点**不返回赞数**（那是实时聚合值，回来时可能已经变了），
/// 所以按钮的即时反馈只能靠本地 ±1。这里钉住那一步算得对，且能原样翻回来（失败回滚用）。
///
/// 排序、幂等、批量取数都在服务端，需要 DB（L1），见 story 的待验收清单。
void main() {
  Comment c({int likeCount = 0, bool liked = false}) => Comment(
        id: 1,
        authorId: 7,
        authorDeleted: false,
        body: '正文',
        createdAt: DateTime.utc(2026, 9, 11),
        likeCount: likeCount,
        liked: liked,
      );

  group('乐观更新：本地翻转', () {
    test('未赞 → 已赞，数字 +1', () {
      final after = c(likeCount: 3).toggleLikedLocally();
      expect(after.liked, isTrue);
      expect(after.likeCount, 4);
    });

    test('已赞 → 取消，数字 -1', () {
      final after = c(likeCount: 3, liked: true).toggleLikedLocally();
      expect(after.liked, isFalse);
      expect(after.likeCount, 2);
    });

    /// 失败回滚就是再翻一次 —— 必须能原样回到起点，否则回滚会把数字越滚越偏。
    test('翻两次回到原状（失败回滚靠这条成立）', () {
      final before = c(likeCount: 5, liked: false);
      final back = before.toggleLikedLocally().toggleLikedLocally();
      expect(back.liked, before.liked);
      expect(back.likeCount, before.likeCount);
    });

    /// 服务端才是权威：本地态与服务端不同步时（别处已取消、这里还记着已赞），
    /// 取消不能把数字带成负数。
    test('赞数 0 时取消不产生负数', () {
      final after = c(likeCount: 0, liked: true).toggleLikedLocally();
      expect(after.likeCount, 0);
      expect(after.liked, isFalse);
    });

    test('翻转不动其它字段', () {
      final before = c(likeCount: 2);
      final after = before.toggleLikedLocally();
      expect(after.id, before.id);
      expect(after.authorId, before.authorId);
      expect(after.body, before.body);
      expect(after.createdAt, before.createdAt);
    });
  });

  group('线格式向后兼容', () {
    /// 老后端不下发这两个字段 —— 必须按「0 赞、未赞」兜底，而不是崩。
    test('缺 likeCount / liked → 0 / false', () {
      final parsed = Comment.fromJson({
        'id': 1,
        'authorId': 7,
        'body': '正文',
        'createdAt': '2026-09-11T00:00:00Z',
      });
      expect(parsed.likeCount, 0);
      expect(parsed.liked, isFalse);
    });

    test('有字段时按值读', () {
      final parsed = Comment.fromJson({
        'id': 1,
        'authorId': 7,
        'body': '正文',
        'createdAt': '2026-09-11T00:00:00Z',
        'likeCount': 12,
        'liked': true,
      });
      expect(parsed.likeCount, 12);
      expect(parsed.liked, isTrue);
    });

    /// 二级回复同样可被点赞（一级二级共用 comment_likes，层级与点赞无关）。
    test('内嵌的二级回复也带点赞字段', () {
      final parsed = Comment.fromJson({
        'id': 1,
        'authorId': 7,
        'body': '一级',
        'createdAt': '2026-09-11T00:00:00Z',
        'replyCount': 1,
        'replies': [
          {
            'id': 2,
            'authorId': 8,
            'body': '二级',
            'createdAt': '2026-09-11T00:01:00Z',
            'likeCount': 4,
            'liked': true,
          }
        ],
      });
      expect(parsed.replies!.single.likeCount, 4);
      expect(parsed.replies!.single.liked, isTrue);
    });
  });
}
