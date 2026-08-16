import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 黑名单数据层（V1.1.4 FR-94）。服务端能力见 Story 1.1 的 `MeBlockedUsersController`。
///
/// **Story 1.2 只需要 [block]**；列表查询与解除拉黑由 **Story 1.5** 在本接口上追加
/// （刻意放同一个 repository：三个动作打的是同一组端点 `/api/v1/me/blocked-users`，
/// 拆成两个 repository 会让「拉黑」和「解除拉黑」分居两处，日后必然漂移）。
abstract class BlockedUsersRepository {
  /// 拉黑某人。服务端幂等：重复拉黑不报错、不刷新拉黑时间（Story 1.1 AC2）。
  Future<void> block(int userId);
}

class DioBlockedUsersRepository implements BlockedUsersRepository {
  DioBlockedUsersRepository(this.dio);

  final Dio dio;

  @override
  Future<void> block(int userId) async {
    // 204 No Content，无响应体。
    await dio.post<void>(ApiPaths.meBlockedUsers, data: <String, dynamic>{'targetUserId': userId});
  }
}

final Provider<BlockedUsersRepository> blockedUsersRepositoryProvider =
    Provider<BlockedUsersRepository>((ref) => DioBlockedUsersRepository(ref.read(dioProvider)));
