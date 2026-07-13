import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/vet/domain/vet_queue.dart';

/// Story 3.6 兽医计费队列 model fromJson。
void main() {
  test('VetQueue.fromJson: available 列表 + 身份富化 + 时间戳解析', () {
    final q = VetQueue.fromJson({
      'available': [
        {
          'requestToken': 'req-1',
          'petName': '旺财',
          'petSpecies': 'DOG',
          'petAgeMonths': 18,
          'ownerHandle': 'rani',
          'waitingSeconds': 45,
          'queueDeadlineAt': '2026-07-13T10:01:00Z',
        },
      ],
    });

    expect(q.awaitingPay, isNull); // 缺省 → null
    expect(q.available, hasLength(1));
    final item = q.available.first;
    expect(item.requestToken, 'req-1');
    expect(item.petName, '旺财');
    expect(item.petSpecies, 'DOG');
    expect(item.petAgeMonths, 18);
    expect(item.ownerHandle, 'rani');
    expect(item.waitingSeconds, 45);
    expect(item.queueDeadlineAt, DateTime.utc(2026, 7, 13, 10, 1));
  });

  test('VetQueue.fromJson: 身份字段缺失优雅降级 null', () {
    final q = VetQueue.fromJson({
      'available': [
        {'requestToken': 'req-2'}
      ],
    });
    final item = q.available.first;
    expect(item.requestToken, 'req-2');
    expect(item.petName, isNull);
    expect(item.petSpecies, isNull);
    expect(item.petAgeMonths, isNull);
    expect(item.ownerHandle, isNull);
    expect(item.waitingSeconds, 0);
    expect(item.queueDeadlineAt, isNull);
  });

  test('VetQueue.fromJson: awaitingPay 待支付项 + 服务端权威 payDeadline', () {
    final q = VetQueue.fromJson({
      'awaitingPay': {
        'requestToken': 'req-pay',
        'petName': '阿黄',
        'payDeadlineAt': '2026-07-13T10:02:30Z',
      },
      'available': const [],
    });

    expect(q.available, isEmpty);
    final a = q.awaitingPay!;
    expect(a.requestToken, 'req-pay');
    expect(a.petName, '阿黄');
    expect(a.payDeadlineAt, DateTime.utc(2026, 7, 13, 10, 2, 30));
    expect(a.isPaused, isFalse); // pausedAt 缺 → 未暂停
  });

  test('VetAwaitingPay.isPaused: pausedAt 非空 → 暂停中（A-4）', () {
    final q = VetQueue.fromJson({
      'awaitingPay': {
        'requestToken': 'req-paused',
        'payDeadlineAt': '2026-07-13T10:02:30Z',
        'pausedAt': '2026-07-13T10:01:40Z',
      },
    });
    expect(q.awaitingPay!.isPaused, isTrue);
    expect(q.available, isEmpty); // available 缺省 → 空列表
  });
}
