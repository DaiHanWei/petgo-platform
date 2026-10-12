import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/me/data/me_counts_provider.dart';

/// V1.3.4 follow · Story 1.4 · AC1.1 / AC1.5：`/me` 新字段解析与缺键默认。
void main() {
  test('/me 完整响应：token 与四个计数逐字段解析', () {
    final p = UserProfile.fromJson(const {
      'id': 5,
      'userToken': 'tok5tok5tok5tok5tok5tok5tok5tok5',
      'postCount': 15,
      'likeCount': 3,
      'followingCount': 50,
      'followerCount': 30,
    });
    expect(p.userToken, 'tok5tok5tok5tok5tok5tok5tok5tok5');
    expect(p.postCount, 15);
    expect(p.likeCount, 3);
    expect(p.followingCount, 50);
    expect(p.followerCount, 30);
    final c = MeCounts.fromProfile(p);
    expect([c.postCount, c.likeCount, c.followingCount, c.followerCount], [15, 3, 50, 30]);
  });

  /// 登录响应（`/auth/*`）不带帖子统计（T-6）：缺键 → null（= 不知道，四格显示「–」，T-14），不崩。
  test('登录响应缺键 → 计数为 null，不崩', () {
    final p = UserProfile.fromJson(const {'id': 5, 'nickname': 'A'});
    expect(p.userToken, isNull);
    expect(p.postCount, isNull);
    expect(p.followerCount, isNull);
    expect(MeCounts.fromProfile(null).postCount, isNull);
  });

  test('copyWith 保留与覆盖新字段', () {
    const p = UserProfile(id: 1, postCount: 2, followerCount: 3);
    final q = p.copyWith(followerCount: 4);
    expect(q.postCount, 2);
    expect(q.followerCount, 4);
  });
}
