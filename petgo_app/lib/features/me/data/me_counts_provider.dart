import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/data/me_repository.dart';
import '../../auth/domain/login_response.dart';

/// 我的页四格数据（V1.3.4 follow · Story 1.4 · AC1）：Postingan / Suka / Mengikuti / Pengikut。
///
/// 值为 null = 还不知道（T-14：数字位显示「–」，不闪 0）。
class MeCounts {
  const MeCounts({this.postCount, this.likeCount, this.followingCount, this.followerCount});

  /// 我的**全部**帖子（含私密 / 仅自己可见、本人待审帖，D-5 / T-5），与下方网格同源。
  final int? postCount;

  /// 我全部帖子的获赞数（D-5）。
  final int? likeCount;
  final int? followingCount;
  final int? followerCount;

  factory MeCounts.fromProfile(UserProfile? p) => MeCounts(
        postCount: p?.postCount,
        likeCount: p?.likeCount,
        followingCount: p?.followingCount,
        followerCount: p?.followerCount,
      );
}

/// 四格的数据源：**以 `GET /me` 为准**（T-6；登录响应不带帖子统计）。
///
/// ⚠️ **不**调用 `authController.applyProfile` —— 只为四格取数，不顺带改动全局登录态。
///
/// 失效时机（T-15，都 `ref.invalidate(meCountsProvider)`）：换账号（`resetUserScopedCaches`）、
/// 发帖 / 删帖、切到「Saya」Tab、关注 / 取关成功、拉黑成功。
///
/// 🔴 `resetUserScopedCaches` 必须登记：MePage 在 `IndexedStack` 里常驻，autoDispose 不会回收，
/// 不登记的话换账号会串数据（B 看到 A 的粉丝数）。
///
/// `retry: (_, _) => null`：关掉 Riverpod 3 的自动重试 —— 失败时四格回落到 `auth.profile` 的值，
/// 不需要它在后台反复横跳。
///
/// 🔴 **按当前用户 id 分 family**（code-review 2026-10-12）：Riverpod 3 在 invalidate 后的加载中 / 出错态
/// 都会保留上一份数据（`value` 不为 null）—— 若是同一个实例，换账号后 B 会先看到、甚至（B 的 `/me`
/// 失败时）一直看到 A 的计数。按 id 分实例，新账号就没有旧值可带。`invalidate(meCountsProvider)`
/// 仍可整族失效。id 为 null（未登录 / 兽医）→ 不发请求，全「–」。
final meCountsProvider = FutureProvider.autoDispose.family<MeCounts, int?>(
  (ref, userId) async {
    if (userId == null) return const MeCounts();
    return MeCounts.fromProfile(await ref.read(meRepositoryProvider).getMe());
  },
  retry: (_, _) => null,
);
