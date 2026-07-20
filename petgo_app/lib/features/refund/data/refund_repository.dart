import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/refund_request.dart';

/// 用户端退款数据层（Story 4.5）。列表 GET + PawCoin 即时退 POST + QRIS 填收款 POST。
/// JWT/续期由 `AuthInterceptor` 处理，repository 不碰 token/401。
class RefundRepository {
  RefundRepository({required this.dio});

  final Dio dio;

  /// 我的退款列表（仅本人，零 PII）。
  Future<List<MyRefund>> fetchMine() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.meRefundRequests);
    return (resp.data ?? const [])
        .map((e) => MyRefund.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// 即时退币到 PawCoin（原路 or QRIS/DANA 转币+bonus；后端幂等）。返回金额明细供成功页。
  Future<RefundPawcoinResult> refundToPawCoin(String refundToken) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.refundPawcoin(refundToken));
    return RefundPawcoinResult.fromJson(resp.data ?? const {});
  }

  /// QRIS 订单填真钱收款账户（不可逆；净额后端权威，只传渠道/账号/户名）。
  Future<void> submitPayoutInfo({
    required String refundToken,
    required String channel,
    required String payoutAccount,
    required String accountHolderName,
  }) async {
    await dio.post<void>(
      ApiPaths.refundPayoutInfo(refundToken),
      data: {
        'channel': channel,
        'payoutAccount': payoutAccount,
        'accountHolderName': accountHolderName,
      },
    );
  }
}

final refundRepositoryProvider =
    Provider<RefundRepository>((ref) => RefundRepository(dio: ref.read(dioProvider)));

/// 我的退款列表（可 refresh：`ref.invalidate(myRefundsProvider)`）。
final myRefundsProvider = FutureProvider.autoDispose<List<MyRefund>>(
    (ref) => ref.read(refundRepositoryProvider).fetchMine());
