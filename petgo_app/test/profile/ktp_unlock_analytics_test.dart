import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_unlock_analytics.dart';

/// KTP（身份证高清图）付费漏斗 · App 端埋点（2026-09-25）。
///
/// 断言挂在 [Analytics.debugCaptureSink]：看到的是经过 scrub 之后**端上真正发出**的形态。
void main() {
  final sent = <(String, Map<String, Object>?)>[];

  setUp(() {
    sent.clear();
    Analytics.debugCaptureSink = (e, p) => sent.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  DioException dio({int? status}) => DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: status == null
            ? null
            : Response(requestOptions: RequestOptions(path: '/x'), statusCode: status),
      );

  test('弹付费弹窗 → ktp_unlock_paywall_shown（entry + price_idr）', () {
    KtpUnlockAnalytics.paywallShown(entry: KtpUnlockAnalytics.entryDetail, priceIdr: 10000);

    expect(sent.single.$1, 'ktp_unlock_paywall_shown');
    expect(sent.single.$2, {'entry': 'detail', 'price_idr': 10000},
        reason: 'scrub 不得误删这几个键');
  });

  test('价格还没拿到时不编一个，直接不带 price_idr', () {
    KtpUnlockAnalytics.paywallShown(entry: KtpUnlockAnalytics.entryCreate);

    expect(sent.single.$2, {'entry': 'create'});
  });

  test('选定支付方式 → ktp_unlock_started（method 用后端枚举名）', () {
    KtpUnlockAnalytics.started(
        entry: KtpUnlockAnalytics.entryCreate, method: HdPayChannel.pawcoin, priceIdr: 10000);

    expect(sent.single.$1, 'ktp_unlock_started');
    expect(sent.single.$2, {'entry': 'create', 'method': 'PAWCOIN', 'price_idr': 10000});
  });

  group('失败只报服务端看不到的两类', () {
    test('409 → INSUFFICIENT_BALANCE', () {
      KtpUnlockAnalytics.failedFromError(
          entry: 'detail', method: HdPayChannel.pawcoin, error: dio(status: 409));

      expect(sent.single.$1, 'ktp_unlock_failed');
      expect(sent.single.$2!['failure_reason'], 'INSUFFICIENT_BALANCE');
      expect(sent.single.$2!['method'], 'PAWCOIN');
    });

    test('没拿到响应 → NETWORK_ERROR', () {
      KtpUnlockAnalytics.failedFromError(
          entry: 'detail', method: HdPayChannel.qris, error: dio());

      expect(sent.single.$2!['failure_reason'], 'NETWORK_ERROR');
    });

    test('🔴 5xx（网关出码失败等）不报 —— 服务端已报 GATEWAY_DECLINED，再报就是重复计数', () {
      KtpUnlockAnalytics.failedFromError(
          entry: 'detail', method: HdPayChannel.qris, error: dio(status: 500));
      KtpUnlockAnalytics.failedFromError(
          entry: 'detail', method: HdPayChannel.qris, error: StateError('x'));

      expect(sent, isEmpty);
    });
  });

  test('🔴 App 端没有 ktp_unlock_succeeded —— 成功只由服务端报（关掉二维码后再付款 App 不知道）', () {
    final code = File('lib/features/profile/presentation/id_card/ktp_unlock_analytics.dart')
        .readAsLinesSync()
        .where((l) => !l.trim().startsWith('//'))
        .join('\n');
    expect(code.contains("'ktp_unlock_succeeded'"), isFalse);
  });

  test('两个付费入口（生成页 / 卡详情页）都接了 shown / started / failed，且 entry 不串', () {
    for (final (path, entry) in [
      ('lib/features/profile/presentation/id_card_create_page.dart', 'entryCreate'),
      ('lib/features/profile/presentation/id_card_detail_page.dart', 'entryDetail'),
    ]) {
      final code = File(path).readAsStringSync();
      for (final call in ['paywallShown(', 'started(', 'failedFromError(']) {
        expect(code.contains('KtpUnlockAnalytics.$call'), isTrue, reason: '$path 漏了 $call');
      }
      expect('KtpUnlockAnalytics.$entry'.allMatches(code).length, 3, reason: '$path 的 entry 应全是 $entry');
    }
  });
}
