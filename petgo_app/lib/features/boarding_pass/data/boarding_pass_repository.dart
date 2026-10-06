import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../keepsake/domain/keepsake_purchase_result.dart';
import '../../profile/domain/id_card.dart';
import '../domain/boarding_pass.dart';

/// 登机牌数据层（V1.3.2 Story 3.5）。价格不在这里（`keepsakePricingProvider.boardingPass`）。
class BoardingPassRepository {
  BoardingPassRepository({required this.dio});

  final Dio dio;

  Future<BoardingPassList> list() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petBoardingPasses);
    return BoardingPassList.fromJson(resp.data ?? const {});
  }

  /// 该宠物在该场所无打卡 / 未知 token → 404（以 DioException 抛出）。
  Future<BoardingPassDetail> detail(String placeToken) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petBoardingPass(placeToken));
    return BoardingPassDetail.fromJson(resp.data ?? const {});
  }

  /// 单张解锁：已解锁 409 `keepsake-already-unlocked`、余额不足 409 `pawcoin-insufficient`。
  Future<KeepsakePurchaseResult> unlock(String placeToken, HdPayChannel channel) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.petBoardingPassUnlock(placeToken),
        data: {'channel': channel.wire});
    return KeepsakePurchaseResult.fromJson(resp.data ?? const {});
  }
}

final boardingPassRepositoryProvider =
    Provider<BoardingPassRepository>((ref) => BoardingPassRepository(dio: ref.read(dioProvider)));

final boardingPassListProvider = FutureProvider.autoDispose<BoardingPassList>(
  (ref) => ref.read(boardingPassRepositoryProvider).list(),
);

final boardingPassDetailProvider = FutureProvider.autoDispose.family<BoardingPassDetail, String>(
  (ref, placeToken) => ref.read(boardingPassRepositoryProvider).detail(placeToken),
);
