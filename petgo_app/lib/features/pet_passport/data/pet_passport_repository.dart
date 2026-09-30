import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
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
}

final petPassportRepositoryProvider =
    Provider<PetPassportRepository>((ref) => PetPassportRepository(dio: ref.read(dioProvider)));

/// 本人宠物护照。`autoDispose`：护照页是 push 进来的一次性页面，每次进入重新拉（新章要看得见）。
final petPassportProvider = FutureProvider.autoDispose<PetPassport>(
  (ref) => ref.read(petPassportRepositoryProvider).fetch(),
);
