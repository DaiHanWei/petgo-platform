import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/domain/consult_pay_result.dart';
import 'package:tailtopia/features/consult/domain/consult_request.dart';
import 'package:tailtopia/features/consult/presentation/vet_request_confirm_page.dart';

/// L0（纯逻辑，无 widget）。Story 3.5 下单链路模型 fromJson + IDR 格式化。
void main() {
  group('ConsultRequest.fromJson', () {
    test('parses queueing create response', () {
      final r = ConsultRequest.fromJson({
        'requestToken': 'req-abc',
        'state': 'QUEUEING',
        'queueDeadlineAt': '2026-07-13T10:01:00Z',
        'alreadyActive': false,
      });
      expect(r.requestToken, 'req-abc');
      expect(r.state, ConsultRequestState.queueing);
      expect(r.queueDeadlineAt, isNotNull);
      expect(r.alreadyActive, false);
    });

    test('alreadyActive accepted maps state', () {
      final r = ConsultRequest.fromJson(
          {'requestToken': 'req-x', 'state': 'ACCEPTED_AWAIT_PAY', 'alreadyActive': true});
      expect(r.state, ConsultRequestState.acceptedAwaitPay);
      expect(r.alreadyActive, true);
    });
  });

  group('ConsultRequestStatus.fromJson', () {
    test('accepted carries payDeadline + pausedAt', () {
      final s = ConsultRequestStatus.fromJson({
        'state': 'ACCEPTED_AWAIT_PAY',
        'payDeadlineAt': '2026-07-13T10:01:30Z',
        'pausedAt': '2026-07-13T10:00:45Z',
      });
      expect(s.state, ConsultRequestState.acceptedAwaitPay);
      expect(s.payDeadlineAt, isNotNull);
      expect(s.isPaused, true);
    });

    test('queueing not paused', () {
      final s = ConsultRequestStatus.fromJson(
          {'state': 'QUEUEING', 'queueDeadlineAt': '2026-07-13T10:01:00Z'});
      expect(s.isPaused, false);
      expect(s.payDeadlineAt, isNull);
    });
  });

  group('ConsultPayResult.fromJson', () {
    test('DONE carries order, no payment', () {
      final r = ConsultPayResult.fromJson({
        'mode': 'DONE',
        'order': {'orderToken': 'ord-1', 'status': 'IN_PROGRESS'},
      });
      expect(r.isDone, true);
      expect(r.isPaymentRequired, false);
      expect(r.order!.orderToken, 'ord-1');
      expect(r.payment, isNull);
    });

    test('PAYMENT_REQUIRED carries payment, no order', () {
      final r = ConsultPayResult.fromJson({
        'mode': 'PAYMENT_REQUIRED',
        'payment': {'token': 'pay-1', 'channel': 'QRIS', 'amount': 50000, 'currency': 'IDR', 'status': 'PENDING'},
      });
      expect(r.isPaymentRequired, true);
      expect(r.payment!.channel, 'QRIS');
      expect(r.payment!.amount, 50000);
      expect(r.order, isNull);
    });
  });

  group('formatVetConsultIdr', () {
    test('thousands separators with dot', () {
      expect(formatVetConsultIdr(50000), 'Rp50.000');
      expect(formatVetConsultIdr(1000000), 'Rp1.000.000');
      expect(formatVetConsultIdr(500), 'Rp500');
    });
  });
}
