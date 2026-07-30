import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/order/domain/order_detail.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';

void main() {
  group('OrderDetail.fromJson', () {
    test('兽医退款中：refundStage + pet 富化', () {
      final d = OrderDetail.fromJson({
        'orderType': 'VET_CONSULT',
        'orderToken': 'tok',
        'statusCode': 'REFUNDING',
        'statusColor': 'INFO',
        'amount': 50000,
        'payChannel': 'QRIS',
        'petName': '旺财',
        'petType': 'DOG',
        'petAvatarUrl': 'http://a/x.jpg',
        'petDeleted': false,
        'refundStage': 'AWAITING_APPROVAL',
        'refundNetAmount': 47500,
      });
      expect(d.orderType, OrderType.vetConsult);
      expect(d.statusColor, OrderStatusColor.info);
      expect(d.petName, '旺财');
      expect(d.petDeleted, isFalse);
      expect(d.refundStage, RefundStage.awaitingApproval);
      expect(d.refundNetAmount, 47500);
    });

    test('宠物已删（FR-54D）：petDeleted=true + pet null', () {
      final d = OrderDetail.fromJson({
        'orderType': 'VET_CONSULT',
        'orderToken': 'tok',
        'statusCode': 'COMPLETED',
        'statusColor': 'SUCCESS',
        'amount': 50000,
        'petDeleted': true,
      });
      expect(d.petDeleted, isTrue);
      expect(d.petName, isNull);
      expect(d.refundStage, isNull);
    });

    test('充值：coins；AI：triageTaskId', () {
      final topup = OrderDetail.fromJson({
        'orderType': 'PAWCOIN_TOPUP',
        'orderToken': 't',
        'statusCode': 'PAID',
        'statusColor': 'SUCCESS',
        'amount': 25000,
        'coins': 25000,
      });
      expect(topup.coins, 25000);

      final ai = OrderDetail.fromJson({
        'orderType': 'AI_UNLOCK',
        'orderToken': 'a',
        'statusCode': 'COMPLETED',
        'statusColor': 'SUCCESS',
        'triageTaskId': 777,
      });
      expect(ai.triageTaskId, 777);
    });

    test('RefundStage.fromCode null/未知', () {
      expect(RefundStage.fromCode(null), isNull);
      expect(RefundStage.fromCode('WEIRD'), RefundStage.unknown);
      expect(RefundStage.fromCode('REJECTED'), RefundStage.rejected);
    });
  });
}
