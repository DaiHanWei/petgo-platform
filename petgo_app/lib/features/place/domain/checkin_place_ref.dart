/// 帖子 / Diary 条目里引用的「打卡场所」（V1.3.2 Story 1.5；Story 1.6 的时间线打卡条目复用）。
///
/// 对应后端 `CheckinPlaceView {token, name, status}`。
/// 🔴 `status` 缺键 / 未知值一律 [available] = false（fail-closed：点了不跳一个不存在的场所）。
class CheckinPlaceRef {
  const CheckinPlaceRef({required this.token, required this.name, required this.available});

  final String token;
  final String name;

  /// `status == ACTIVE` → 可进场所详情；否则点击只出「Tempat tidak ditemukan」。
  final bool available;

  /// 非 Map / 缺 token → null（不渲染场所条）。
  static CheckinPlaceRef? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final token = raw['token']?.toString() ?? '';
    if (token.isEmpty) return null;
    return CheckinPlaceRef(
      token: token,
      name: raw['name']?.toString() ?? '',
      available: raw['status'] == 'ACTIVE',
    );
  }

  /// 时间线打卡条目的 `checkinPlace`（Story 1.6）：wire 键是 `placeToken`，与帖子详情的 `token` 不同，各按各自契约解析。
  static CheckinPlaceRef? fromTimelineJson(Object? raw) {
    if (raw is! Map) return null;
    return fromJson({'token': raw['placeToken'], 'name': raw['name'], 'status': raw['status']});
  }
}
