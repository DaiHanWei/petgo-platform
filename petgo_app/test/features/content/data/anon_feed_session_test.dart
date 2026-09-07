import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/anon_feed_session.dart';

/// 游客匿名会话 id（Story 16.1 AC4 / Story 16.3）。
void main() {
  test('同一进程内恒定 —— 否则每次翻页都换缓存键，序列快照等于没有', () {
    expect(AnonFeedSession.id, AnonFeedSession.id);
  });

  test('🛡 只含 [a-z0-9]：冒号能在服务端伪造别人的键空间，别送不合法的串过去', () {
    expect(AnonFeedSession.id, matches(RegExp(r'^[a-z0-9]+$')));
  });

  test('长度足够，不至于撞键', () {
    expect(AnonFeedSession.id.length, greaterThanOrEqualTo(16));
  });
}
