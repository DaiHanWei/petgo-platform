import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/growth/data/growth_action_interceptor.dart';

/// V1.4.0 ① Story 2.3：动作后拉取的判定（只认成功的 POST 发帖 / 评论 / 回复 / 点赞 / 打卡）。
void main() {
  const cases = <(String, String, int?, bool)>[
    ('POST', '/api/v1/content-posts', 201, true),
    ('POST', '/api/v1/content-posts/', 200, true),
    ('POST', '/api/v1/content-posts/42/comments', 201, true),
    ('POST', '/api/v1/comments/9/replies', 201, true),
    ('POST', '/api/v1/content-posts/42/like', 200, true),
    ('post', '/api/v1/content-posts/42/like', 204, true),
    ('POST', '/api/v1/places/tok-abc/checkins', 201, true),
    ('POST', '/api/v1/content-posts?x=1', 201, true),
    // 不触发
    ('DELETE', '/api/v1/content-posts/42/like', 204, false),
    ('GET', '/api/v1/content-posts', 200, false),
    ('POST', '/api/v1/content-posts', 400, false),
    ('POST', '/api/v1/content-posts', 500, false),
    ('POST', '/api/v1/content-posts', null, false),
    ('POST', '/api/v1/content-posts/42/report', 201, false),
    ('POST', '/api/v1/content-posts/42/comments/extra', 201, false),
    ('POST', '/api/v1/me/growth/popups/celebration/ack', 204, false),
  ];

  for (final (method, path, status, expected) in cases) {
    test('$method $path $status → $expected', () {
      expect(GrowthActionInterceptor.isAction(method, path, status), expected);
    });
  }
}
