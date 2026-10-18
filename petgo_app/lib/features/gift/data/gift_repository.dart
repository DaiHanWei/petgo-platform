import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/gift_catalog.dart';
import '../domain/gift_send_result.dart';

/// 送礼数据层（V1.4.0 gift Story 1.3 · AC8；接口由 Story 1.1 提供）。
class GiftRepository {
  GiftRepository({required this.dio});

  final Dio dio;

  /// 礼物目录：`petId` / `postId` 二选一。
  Future<GiftCatalog> fetchCatalog({int? petId, int? postId}) async {
    final res = await dio.get<Map<String, dynamic>>(ApiPaths.giftCatalog, queryParameters: {
      'petId': ?petId,
      'postId': ?postId,
    });
    return GiftCatalog.fromJson(res.data ?? const {});
  }

  /// 发起送礼。[requestId] 是发起幂等键的一部分（同渠道重试复用，见 `gift_send_flow.dart`）。
  Future<GiftSendResult> send({
    required int petId,
    required String giftToken,
    required int quantity,
    required String channel,
    required String requestId,
    required String source,
    int? postId,
  }) async {
    final res = await dio.post<Map<String, dynamic>>(ApiPaths.petGifts(petId), data: {
      'giftToken': giftToken,
      'quantity': quantity,
      'channel': channel,
      'requestId': requestId,
      'source': source,
      'postId': ?postId,
    });
    return GiftSendResult.fromJson(res.data ?? const {});
  }

  /// 单笔订单状态（QRIS `pollPaid` 用）。
  Future<GiftOrderStatus> orderStatus(String orderToken) async {
    final res = await dio.get<Map<String, dynamic>>(ApiPaths.meGiftOrder(orderToken));
    return GiftOrderStatus.fromWire(res.data?['status'] as String?);
  }
}

final giftRepositoryProvider =
    Provider<GiftRepository>((ref) => GiftRepository(dio: ref.read(dioProvider)));

/// 按收礼方取目录。**无本地兜底**：失败为 AsyncError，面板显示 B7 重试。
///
/// ⚠️ `retry: (_, _) => null`（照 `boarding_pass_repository.dart`，bug 20261006-583）：Riverpod 3 默认对抛错的
/// provider 指数退避重试，期间是 AsyncLoading —— 断网时面板会一直转圈，B7「Coba lagi」要 40 多秒后才出来。
/// 另：`gift-pet-gone` / `gift-own-pet` / `gift-blocked` 是确定性拒绝，重试只会白打三次后端。
final giftCatalogProvider = FutureProvider.autoDispose.family<GiftCatalog, GiftRecipientRef>(
  (ref, recipient) => ref
      .read(giftRepositoryProvider)
      .fetchCatalog(petId: recipient.petId, postId: recipient.postId),
  retry: (_, _) => null,
);
