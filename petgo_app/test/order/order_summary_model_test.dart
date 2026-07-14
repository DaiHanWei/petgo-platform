import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_l10n.dart';

void main() {
  group('OrderSummary/OrderPage fromJson', () {
    test('解析 3 类订单 + 可空 amount + 游标/余额', () {
      final page = OrderPage.fromJson({
        'items': [
          {
            'orderType': 'VET_CONSULT',
            'orderToken': 'tok-v',
            'statusCode': 'REFUNDING',
            'statusColor': 'INFO',
            'amount': 50000,
            'payChannel': 'QRIS',
            'createdAt': '2026-07-14T03:00:00Z',
          },
          {
            'orderType': 'AI_UNLOCK',
            'orderToken': 'tok-a',
            'statusCode': 'COMPLETED',
            'statusColor': 'SUCCESS',
            'amount': 5000,
          },
          {
            'orderType': 'PAWCOIN_TOPUP',
            'orderToken': 'tok-t',
            'statusCode': 'PAID',
            'statusColor': 'SUCCESS',
            'amount': null, // 泛化预留：amount 可空
          },
        ],
        'nextCursor': '1752462000000',
        'hasMore': true,
        'pawcoinBalance': 12345,
      });

      expect(page.items, hasLength(3));
      expect(page.hasMore, isTrue);
      expect(page.nextCursor, '1752462000000');
      expect(page.pawcoinBalance, 12345);

      expect(page.items[0].orderType, OrderType.vetConsult);
      expect(page.items[0].statusColor, OrderStatusColor.info); // 退款中 → INFO
      expect(page.items[0].amount, 50000);
      expect(page.items[2].amount, isNull); // 可空 amount 兜住
    });

    test('未知 orderType/statusColor 优雅回退', () {
      final o = OrderSummary.fromJson({'orderToken': 't', 'orderType': 'FOO', 'statusColor': 'BAR'});
      expect(o.orderType, OrderType.unknown);
      expect(o.statusColor, OrderStatusColor.unknown);
    });
  });

  group('statusColor → 徽章色（退款中 INFO 非红）', () {
    test('SUCCESS 绿 / WARN 黄 / INFO 紫柔底 / unknown 中性', () {
      expect(orderStatusColors(OrderStatusColor.success).bg, AppColors.momenBadgeBg);
      expect(orderStatusColors(OrderStatusColor.warn).bg, AppColors.goldTint);
      // 退款中 INFO：紫柔底，绝非 error 红（UX-DR2）
      final info = orderStatusColors(OrderStatusColor.info);
      expect(info.bg, AppColors.mintTint);
      expect(info.bg, isNot(AppColors.popRed));
      expect(info.bg, isNot(AppColors.danger));
      expect(orderStatusColors(OrderStatusColor.unknown).bg, AppColors.line2);
    });
  });

  group('OrderType.toApi 筛选值', () {
    test('全部(null)/HD/unknown', () {
      expect(OrderType.vetConsult.toApi(), 'VET_CONSULT');
      expect(OrderType.pawcoinTopup.toApi(), 'PAWCOIN_TOPUP');
      expect(OrderType.unknown.toApi(), isNull);
    });
  });

  // orderStatusColors 已覆盖；amount 格式（含 null 分支）在 widget 层随 l10n 验证。
  test('color pairs 有对比（fg 非透明占位）', () {
    for (final c in OrderStatusColor.values) {
      expect(orderStatusColors(c).fg, isA<Color>());
    }
  });
}
