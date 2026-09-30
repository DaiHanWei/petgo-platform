import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../keepsake/domain/keepsake_purchase_result.dart';
import '../../profile/domain/id_card.dart';
import '../domain/passport_snapshot.dart';
import '../domain/pet_passport.dart';

/// 宠物护照数据层（V1.3.2 Story 1.2）。
///
/// 服务端在这个 GET 里首次签发护照（幂等）—— 客户端不需要、也不该先调别的接口。
/// 错误以 [DioException] 抛给页面（整页错误 + 重试）；无宠物档案服务端回 404。
class PetPassportRepository {
  PetPassportRepository({required this.dio});

  final Dio dio;

  Future<PetPassport> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petPassport);
    return PetPassport.fromJson(resp.data ?? const {});
  }

  /// 发起「当前版本」快照购买（Story 3.4）。无章 422、已买 409 `keepsake-already-unlocked`。
  Future<KeepsakePurchaseResult> startSnapshot(HdPayChannel channel) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.petPassportSnapshots, data: {'channel': channel.wire});
    return KeepsakePurchaseResult.fromJson(resp.data ?? const {});
  }

  /// 已买版本（只含已付，新 → 旧）。
  Future<List<PassportSnapshotItem>> snapshots() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petPassportSnapshots);
    return PassportSnapshotItem.listFromJson(resp.data ?? const {});
  }

  Future<PassportSnapshotDetail> snapshot(String token) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petPassportSnapshot(token));
    return PassportSnapshotDetail.fromJson(resp.data ?? const {});
  }
}

final petPassportRepositoryProvider =
    Provider<PetPassportRepository>((ref) => PetPassportRepository(dio: ref.read(dioProvider)));

/// 本人宠物护照。`autoDispose`：护照页是 push 进来的一次性页面，每次进入重新拉（新章要看得见）。
final petPassportProvider = FutureProvider.autoDispose<PetPassport>(
  (ref) => ref.read(petPassportRepositoryProvider).fetch(),
);

/// 已买版本列表（Story 3.4）。
final passportSnapshotsProvider = FutureProvider.autoDispose<List<PassportSnapshotItem>>(
  (ref) => ref.read(petPassportRepositoryProvider).snapshots(),
);

/// 单个已买版本（回看）。
final passportSnapshotProvider = FutureProvider.autoDispose.family<PassportSnapshotDetail, String>(
  (ref, token) => ref.read(petPassportRepositoryProvider).snapshot(token),
);
