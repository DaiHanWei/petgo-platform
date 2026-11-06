import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_log_interceptor.dart';

/// v1.4.2 shop Story 9.2 复审：收款账号与户名是 PII，debug 日志里同样要打码。
class _Handler extends RequestInterceptorHandler {}

void main() {
  List<String> capture() {
    final lines = <String>[];
    final original = debugPrint;
    debugPrint = (String? m, {int? wrapWidth}) => lines.add(m ?? '');
    addTearDown(() => debugPrint = original);
    return lines;
  }

  test('取消退款选银行：account / accountHolderName 打码，渠道照常', () {
    final lines = capture();
    ApiLogInterceptor().onRequest(
      RequestOptions(path: '/api/v1/me/shop-orders/o/refunds/r/destination', method: 'POST', data: {
        'destination': 'TO_BANK',
        'channel': 'BCA',
        'account': '2119090695',
        'accountHolderName': 'Budi Santoso',
      }),
      _Handler(),
    );
    final out = lines.join('\n');
    expect(out, isNot(contains('2119090695')));
    expect(out, isNot(contains('Budi Santoso')));
    expect(out, contains('BCA'));
  });

  test('退货 / 虚拟单退款：payoutAccount 打码（大小写无关，嵌套也打码）', () {
    final lines = capture();
    ApiLogInterceptor().onRequest(
      RequestOptions(path: '/api/v1/me/shop-returns/t/cash-destination', method: 'POST', data: {
        'PayoutAccount': '081234567890',
        'nested': {'accountHolderName': 'Siti Aminah', 'payoutAccount': '5550001112'},
      }),
      _Handler(),
    );
    final out = lines.join('\n');
    expect(out, isNot(contains('081234567890')));
    expect(out, isNot(contains('Siti Aminah')));
    expect(out, isNot(contains('5550001112')));
  });

  test('v1.4.2 shop Story 9.3：兑换码 code 打码', () {
    final lines = capture();
    ApiLogInterceptor().onRequest(
      RequestOptions(path: '/api/v1/me/coupons/redeem', method: 'POST', data: {'code': 'KUCING2026'}),
      _Handler(),
    );
    final out = lines.join('\n');
    expect(out, isNot(contains('KUCING2026')));
    expect(out, contains('/api/v1/me/coupons/redeem'));
  });
}
