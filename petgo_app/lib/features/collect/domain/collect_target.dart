/// 收藏对象（V1.4.0 ④ Story 2.3）：帖子 / 场所。
///
/// 「Lihat 跳哪」的**唯一定义**在这里：[savedLocation] 生成 `/me?saved=post|place`，
/// 「我的」页（Story 2.4）用 [fromSavedParam] 解析回来。`saved` 的取值就是 [CollectTarget.name]。
enum CollectTarget {
  post,
  place;

  /// 收藏成功 Toast「Lihat」的落点。`/me` 是 shell Tab 根，只能 `go` 不能 `push`。
  static String savedLocation(CollectTarget t) => '/me?saved=${t.name}';

  /// `?saved=` 参数 → 目标；未知 / null → null。
  static CollectTarget? fromSavedParam(String? raw) {
    for (final t in CollectTarget.values) {
      if (t.name == raw) return t;
    }
    return null;
  }
}
