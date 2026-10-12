import 'dart:io';

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

  /// T-15 / AC1.4：六个失效点都在（删任一行这里就红 —— code-review 2026-10-12 #4）。
  test('meCountsProvider 的失效点齐全', () {
    const sites = {
      'lib/app.dart': '换账号 resetUserScopedCaches',
      'lib/shared/widgets/app_shell.dart': '切到 Saya Tab',
      'lib/features/content/presentation/publish_compose_page.dart': '发帖',
      'lib/features/content/presentation/content_detail_page.dart': '删帖',
      'lib/features/social/presentation/widgets/follow_button.dart': '关注 / 取关成功',
      'lib/features/user_profile/presentation/public_profile_page.dart': '主页拉黑成功',
      'lib/shared/widgets/mini_profile_sheet.dart': '迷你卡拉黑成功',
    };
    sites.forEach((file, why) {
      expect(File(file).readAsStringSync(), contains('invalidate(meCountsProvider)'), reason: '$why：$file');
    });
  });
}
