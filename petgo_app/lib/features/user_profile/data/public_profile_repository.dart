import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../auth/domain/user_tag.dart';

/// 用户公开主页投影（对应后端 `PublicProfileResponse`）。
/// V1.3.0 batch-b1 Story 2.1 · FR-118。
///
/// ## 它与 `MiniProfile`（迷你卡）的关系
/// 迷你卡是一张「什么都点不动的小卡片」；本 story 之后所有入口**直接进完整主页**。
/// 字段**不是照抄迷你卡**：多了 [joinedAt]（本批次新补）与 [self]（同页两视角）。
///
/// ⚠️ **获赞总数 / 内容网格 / 宠物卡不在这里** —— Story 2.2 与 2.3 各自补，
/// 别提前塞占位字段（前端一旦读了个恒 0 的键，后面接真值时没人记得改）。
class PublicProfile {
  const PublicProfile({
    required this.postCount,
    required this.isDeactivated,
    required this.self,
    this.nickname,
    this.avatarUrl,
    this.signature,
    this.joinedAt,
    this.reported = false,
    this.tags = const [],
  });

  /// 已发布（未软删）内容数。
  final int postCount;

  /// 目标用户是否已注销。为 true 时其余身份字段**服务端一个都不下发**（NFR-3）。
  final bool isDeactivated;

  /// 是不是本人视角。
  ///
  /// 🔴 **服务端算给的**，不是客户端拿本地 id 比出来的 —— 两边各算一次，口径迟早会漂。
  /// Story 2.4 用它切「自己视角」。
  final bool self;

  final String? nickname;
  final String? avatarUrl;

  /// 个性签名（既有字段，直接复用）。未设置 / 已注销 → null。
  final String? signature;

  /// 加入时间（`users.created_at`，UTC）。**本批次新补的字段**；已注销 → null。
  final DateTime? joinedAt;

  /// 当前查看者**是否举报过这个人**（V1.1.4 Story 2.1 AC8 的既有口径）。
  ///
  /// ⚠️ 由服务端的举报隐藏行派生、**不是前端会话态** —— 用户重装 App 也要还看得到
  /// 「已举报」，否则会重复举报同一个人。游客的响应体里**根本没有这个键**
  /// （后端可空布尔 + NON_NULL 省略），故默认 false。
  final bool reported;

  /// 运营标签（最多 3 个）。已注销恒为空。
  final List<UserTag> tags;

  /// 是否有可展示的签名（空串与纯空白按「没设置」处理）。
  bool get hasSignature => signature?.trim().isNotEmpty == true;

  factory PublicProfile.fromJson(Map<String, dynamic> json) => PublicProfile(
        postCount: (json['postCount'] ?? 0) as int,
        isDeactivated: (json['isDeactivated'] ?? false) as bool,
        self: (json['self'] ?? false) as bool,
        nickname: json['nickname'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        signature: json['signature'] as String?,
        // 后端是 UTC Instant（ISO-8601 带 Z）；展示前各页自己 toLocal()。
        joinedAt: DateTime.tryParse((json['joinedAt'] ?? '') as String? ?? ''),
        reported: (json['reported'] ?? false) as bool,
        tags: UserTag.listFromJson(json['tags']),
      );
}

/// 公开主页数据层。只读、游客可调。
abstract class PublicProfileRepository {
  Future<PublicProfile> getPublicProfile(int userId);
}

class DioPublicProfileRepository implements PublicProfileRepository {
  DioPublicProfileRepository(this.dio);

  final Dio dio;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.userPublicProfile(userId));
    return PublicProfile.fromJson(resp.data!);
  }
}

final Provider<PublicProfileRepository> publicProfileRepositoryProvider =
    Provider<PublicProfileRepository>((ref) => DioPublicProfileRepository(ref.read(dioProvider)));

/// 某个用户的公开主页（family：userId）。
///
/// ## 🔴 `isAutoDispose: true` 不是可选项
/// Riverpod 3 的 `FutureProvider.family` **默认 keep-alive**（`isAutoDispose = false`）。
/// 留着默认值的话，这一族缓存会活满整个 App 进程，于是：
/// - 举报完退出、再点进同一个人 → 拿到缓存里的 `reported: false`，「已举报」标记消失，
///   用户会**重复举报**同一个人（而每一次都会真的落一行明细）；
/// - 拉黑完再点进去 → 拿到缓存的 200，看到的是他的主页而不是「你已拉黑该用户」；
/// - 断网时的那次失败会被永久缓存，网络恢复后照样是失败页；
/// - **同设备换账号**时 B 会读到 A 缓存里的 `self: true`（于是 B 连举报 / 拉黑入口都没有）
///   与 A 对第三人的 `reported: true`（A 的举报历史泄漏给 B）——
///   与 bug 20260730-421 / 446 同型。autoDispose 之后这一族随页面关闭一起消失，
///   因此**不需要**也不应登记进 `resetUserScopedCaches`。
///
/// ## ⚠️ `retry: (_, _) => null` 是**关掉 Riverpod 3 的自动重试**，不是漏写
/// （同 `blockedUsersProvider`）两条理由：
/// 1. 放任它的话，失败后页面会在「错误态」与「加载中」之间自己反复横跳，
///    用户点不到那个「重试」按钮；
/// 2. 本端点的失败以 **403 已拉黑** 为主，重试一万次也是同一个结果 ——
///    自动退避只是在替用户反复敲一个永远不会开的门。
final publicProfileProvider = FutureProvider.family<PublicProfile, int>(
  (ref, userId) => ref.read(publicProfileRepositoryProvider).getPublicProfile(userId),
  retry: (_, _) => null,
  isAutoDispose: true,
);
