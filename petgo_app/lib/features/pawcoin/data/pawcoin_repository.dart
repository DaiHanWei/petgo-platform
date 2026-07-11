import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/pawcoin_transaction.dart';

/// PawCoin 余额与流水数据层（Story 1.4）。只读 GET，JWT/续期由 `AuthInterceptor` 自动处理，
/// repository 不碰 token/401。
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
}

final pawCoinRepositoryProvider =
    Provider<PawCoinRepository>((ref) => PawCoinRepository(dio: ref.read(dioProvider)));
