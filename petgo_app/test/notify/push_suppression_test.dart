import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/notify/domain/push_suppression.dart';

/// Story 6.2 AC1：前台正在查看同一会话 → 抑制 in-app 重复推送；其它情形不抑制。
void main() {
  test('正在查看同一会话的 VET_REPLY → 抑制', () {
    expect(PushSuppression.shouldSuppressInApp(9, 'VET_REPLY', '9'), isTrue);
    expect(PushSuppression.shouldSuppressInApp(9, 'CONSULT_CLOSED', '9'), isTrue);
  });

  test('不同会话 / 非会话类 / 未在任何会话 → 不抑制', () {
    expect(PushSuppression.shouldSuppressInApp(9, 'VET_REPLY', '10'), isFalse);
    expect(PushSuppression.shouldSuppressInApp(9, 'CONTENT_LIKED', '9'), isFalse);
    expect(PushSuppression.shouldSuppressInApp(null, 'VET_REPLY', '9'), isFalse);
    expect(PushSuppression.shouldSuppressInApp(9, null, '9'), isFalse);
    expect(PushSuppression.shouldSuppressInApp(9, 'VET_REPLY', null), isFalse);
  });
}
