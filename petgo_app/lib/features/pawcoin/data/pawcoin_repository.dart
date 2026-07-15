import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/pawcoin_transaction.dart';
import '../domain/topup.dart';

/// PawCoin 余额/流水（1.4）+ 充值下单/选项/状态轮询（1.5）数据层。只读/下单 GET/POST，JWT/续期由
/// `AuthInterceptor` 自动处理，repository 不碰 token/401。
class PawCoinRepository {
  PawCoinRepository({required this.dio});

  final Dio dio;

  Future<PawCoinWalletPage> fetch({String? cursor, int limit = 20}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.mePawcoin,
      queryParameters: {'cursor': ?cursor, 'limit': limit},
    );
    return PawCoinWalletPage.fromJson(resp.data!);
  }

  /// 充值选项：档位 + 是否暂停（Story 1.5）。
  Future<TopupOptions> fetchTopupOptions() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.mePawcoinTopupOptions);
    return TopupOptions.fromJson(resp.data!);
  }

  /// 充值下单（Story 1.5）。强制 `Idempotency-Key`（后端 1.3 缺失即 422）。
  Future<TopupResult> createTopup({
    required String tierId,
    required String channel,
    required String idemKey,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.mePawcoinTopups,
      data: {'tierId': tierId, 'channel': channel},
      options: Options(headers: {'Idempotency-Key': idemKey}),
    );
    return TopupResult.fromJson(resp.data!);
  }

  /// 支付状态轮询（Story 1.5）：PENDING/PAID/FAILED/EXPIRED。
  Future<String> pollStatus(String intentToken) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.mePawcoinTopupStatus(intentToken));
    return (resp.data!['status'] ?? '') as String;
  }
}

final pawCoinRepositoryProvider =
    Provider<PawCoinRepository>((ref) => PawCoinRepository(dio: ref.read(dioProvider)));
