/// 黑名单里的一行（Story 1.5，FR-94）。对应后端 `BlockedUserItem`。
///
/// ⚠️ [deleted] 是「对方**自己注销**了」，**不是「被平台封号」**。
/// 后端根本不下发封号信息——被封号的人在这份数据里跟正常人一模一样，这是刻意的：
/// 封号是平台侧处置，不该通过黑名单页透露给用户。**别自作主张给他们加标记。**
class BlockedUser {
  const BlockedUser({
    required this.userId,
    required this.deleted,
    required this.reported,
    required this.blockedAt,
    this.nickname,
    this.avatarUrl,
  });

  final int userId;

  /// 昵称 —— 注销时后端**整个键都不下发**（`default-property-inclusion: non_null`），故必须可空解析。
  final String? nickname;
  final String? avatarUrl;

  /// 对方已自行注销（渲染匿名态 + 头像昵称去点击态，但「解除拉黑」按钮**仍要可用**）。
  final bool deleted;

  /// 这个人也被我举报过 —— 决定「已举报」标签、解除确认正文与解除成功 Toast 的变体。
  final bool reported;

  /// 拉黑时间（取 BLOCK 行的创建时间，列表按它倒序；事后举报不会改变它）。
  final DateTime blockedAt;

  factory BlockedUser.fromJson(Map<String, dynamic> json) => BlockedUser(
        userId: json['userId'] as int,
        nickname: json['nickname'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        deleted: (json['deleted'] ?? false) as bool,
        reported: (json['reported'] ?? false) as bool,
        // 后端 Instant 是 UTC，必须 toLocal 再展示（评审三轮 #10）——否则 WIB 用户凌晨拉黑会
        // 显示成前一天。与 App 内其它绝对日期路径（订单/客服/身份证）一致，格式化前先转本地。
        blockedAt: DateTime.parse(json['blockedAt'] as String).toLocal(),
      );
}
