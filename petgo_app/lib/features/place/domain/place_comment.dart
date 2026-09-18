/// 场所评论域模型（V1.3.0 batch-b1 Story 1.7，消费 `/api/v1/places/{token}/comments`）。
///
/// 字段与后端 `PlaceCommentResponse` 一一对应（CROSS-STORY C4）。
/// 线上契约由 `test/place/place_comment_wire_contract_test.dart` 守着。
///
/// <h2>🔴 这里**没有**的字段（AC2 反向验收）</h2>
/// 没有 `parentId` / `replyCount` / `replies` —— 场所评论**只有一级**（PRD ③：攻略提示性质，
/// 无对话需求）。这不是"还没做"：后端 DTO、表结构、端点三层都没有回复的位置。
library;

/// 评论自带的二元态度（AC3）。**没表态 = null**，不是第三个枚举值。
enum PlaceCommentAttitude {
  recommend('RECOMMEND'),
  notRecommend('NOT_RECOMMEND');

  const PlaceCommentAttitude(this.api);

  /// 线格式（UPPER_SNAKE，与后端 `PlaceCommentAttitude` 同源）。
  final String api;

  /// 认不出的值 → null（不兜底成某一边：兜底会让一个未知态度被算成推荐）。
  static PlaceCommentAttitude? fromApi(String? raw) {
    for (final v in values) {
      if (v.api == raw) return v;
    }
    return null;
  }
}

/// 审核态。非 `visible` 的行**只会下发给作者本人**（读路径已按 viewer 过滤）。
enum PlaceCommentModeration {
  visible('VISIBLE'),
  underReview('UNDER_REVIEW'),
  takenDown('TAKEN_DOWN'),
  rejected('REJECTED');

  const PlaceCommentModeration(this.api);

  final String api;

  /// 认不出的值一律当 [visible]：那是**最不会误导**的方向 ——
  /// 当成"仅你可见"会给一条正常评论挂上莫须有的灰标签。
  static PlaceCommentModeration fromApi(String? raw) {
    for (final v in values) {
      if (v.api == raw) return v;
    }
    return visible;
  }

  /// 要不要挂「仅你可见」灰标签。
  bool get onlyVisibleToMe => this != visible;
}

class PlaceComment {
  const PlaceComment({
    required this.id,
    required this.authorId,
    required this.authorDeleted,
    required this.body,
    required this.mine,
    required this.moderation,
    this.authorNickname,
    this.authorAvatarUrl,
    this.attitude,
    this.createdAt,
  });

  final int id;
  final int authorId;

  /// 注销 → 昵称/头像为 null，前端渲染本地化「已注销用户」+ 默认头像且**头像不可点**（NFR-8）。
  final String? authorNickname;
  final String? authorAvatarUrl;
  final bool authorDeleted;

  final String body;

  /// null = 这条评论没表态（AC3）。
  final PlaceCommentAttitude? attitude;

  final DateTime? createdAt;

  final PlaceCommentModeration moderation;

  /// 是不是本人发的 —— 决定要不要画删除入口（AC7）。
  ///
  /// 🔴 **服务端算给的**，不在客户端拿 authorId 自己比：删除本身的权限校验在服务端，
  /// 这个标记只管"画不画按钮"。两边都做的话，哪天口径变了会有一边忘记改。
  final bool mine;

  /// 能不能点开这个人（注销 / 拿不到 id → 不可点）。
  bool get authorTappable => !authorDeleted && authorId > 0;

  factory PlaceComment.fromJson(Map<String, dynamic> json) {
    final id = json['id'];
    final authorId = json['authorId'];
    return PlaceComment(
      id: id is num ? id.toInt() : 0,
      authorId: authorId is num ? authorId.toInt() : 0,
      authorNickname: _blank(json['authorNickname']?.toString()),
      authorAvatarUrl: _blank(json['authorAvatarUrl']?.toString()),
      authorDeleted: json['authorDeleted'] == true,
      body: json['body']?.toString() ?? '',
      attitude: PlaceCommentAttitude.fromApi(json['attitude']?.toString()),
      createdAt: DateTime.tryParse(json['createdAt']?.toString() ?? ''),
      moderation: PlaceCommentModeration.fromApi(json['moderationStatus']?.toString()),
      mine: json['mine'] == true,
    );
  }

  static String? _blank(String? s) => (s == null || s.isEmpty) ? null : s;
}

/// 游标分页信封（形状与内容评论一致，分页处理可以照抄）。
///
/// [total] 是**对当前查看者可见的**条数（拉黑过滤会让它因人而异）——
/// 详情页标题「评论 (3)」用的就是它。
class PlaceCommentPage {
  const PlaceCommentPage({
    required this.items,
    required this.hasMore,
    required this.total,
    this.nextCursor,
  });

  final List<PlaceComment> items;
  final String? nextCursor;
  final bool hasMore;
  final int total;

  static const PlaceCommentPage empty =
      PlaceCommentPage(items: [], hasMore: false, total: 0);

  factory PlaceCommentPage.fromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    final items = raw is! List
        ? const <PlaceComment>[]
        : raw
            .whereType<Map>()
            .map((e) => PlaceComment.fromJson(Map<String, dynamic>.from(e)))
            .toList(growable: false);
    final total = json['total'];
    return PlaceCommentPage(
      items: items,
      nextCursor: _blank(json['nextCursor']?.toString()),
      hasMore: json['hasMore'] == true,
      // 拿不到 total 就退回"这一页有几条"——比显示 0 诚实（0 会和空态混淆）。
      total: total is num ? total.toInt() : items.length,
    );
  }

  /// 追加下一页（游标分页的累积）。
  PlaceCommentPage append(PlaceCommentPage next) => PlaceCommentPage(
        items: [...items, ...next.items],
        nextCursor: next.nextCursor,
        hasMore: next.hasMore,
        total: next.total,
      );

  static String? _blank(String? s) => (s == null || s.isEmpty) ? null : s;
}
