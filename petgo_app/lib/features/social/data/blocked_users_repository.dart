import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/blocked_user.dart';

/// 黑名单数据层（V1.1.4 FR-94）。服务端能力见 Story 1.1（写）与 Story 1.5（列表）。
///
/// 三个动作打的是同一组端点 `/api/v1/me/blocked-users`，故刻意放同一个 repository ——
/// 拆开会让「拉黑」和「解除拉黑」分居两处，日后必然漂移。
abstract class BlockedUsersRepository {
  /// 拉黑某人。服务端幂等：重复拉黑不报错、不刷新拉黑时间（Story 1.1 AC2）。
  Future<void> block(int userId);

  /// 我的黑名单，按拉黑时间倒序。**全量返回、不分页**（列表长度即设置页要显示的计数）。
  Future<List<BlockedUser>> list();

  /// 解除拉黑。只解除主动拉黑；举报产生的隐藏永不解除（Story 1.1 AC3）。
  Future<void> unblock(int userId);
}

class DioBlockedUsersRepository implements BlockedUsersRepository {
  DioBlockedUsersRepository(this.dio);

  final Dio dio;

  @override
  Future<void> block(int userId) async {
    // 204 No Content，无响应体。
    await dio.post<void>(ApiPaths.meBlockedUsers, data: <String, dynamic>{'targetUserId': userId});
  }

  @override
  Future<List<BlockedUser>> list() async {
    // 裸数组返回（无信封、无游标、无 total）——与 `/me/refund-requests` 同款惯例。
    final resp = await dio.get<List<dynamic>>(ApiPaths.meBlockedUsers);
    return (resp.data ?? const <dynamic>[])
        .map((e) => BlockedUser.fromJson((e as Map).cast<String, dynamic>()))
        .toList(growable: false);
  }

  @override
  Future<void> unblock(int userId) async {
    await dio.delete<void>(ApiPaths.meBlockedUser(userId));
  }
}

final Provider<BlockedUsersRepository> blockedUsersRepositoryProvider =
    Provider<BlockedUsersRepository>((ref) => DioBlockedUsersRepository(ref.read(dioProvider)));

/// 黑名单列表。**无分页故用 `FutureProvider`**（照 `myTicketsProvider`），不上 `AsyncNotifier`。
///
/// 设置页与黑名单页共用它：设置页那一行的计数就是 `valueOrNull?.length ?? 0`，
/// 不需要后端再给一个 `total`。
/// ⚠️ `retry: (_, _) => null` 是**关掉 Riverpod 3 的自动重试**，不是漏写。
///
/// Riverpod 3 默认对抛错的 provider 自动指数退避重试。放任它的话，加载失败后页面会在
/// 「错误态」和「加载中」之间自己反复横跳，用户点不到那个「重试」按钮，
/// 而 AC4 要求的恰恰是**给用户一条明确的恢复路径**。失败就停在失败态，重试由用户按钮触发。
final blockedUsersProvider = FutureProvider.autoDispose<List<BlockedUser>>(
  (ref) => ref.read(blockedUsersRepositoryProvider).list(),
  retry: (_, _) => null,
);
