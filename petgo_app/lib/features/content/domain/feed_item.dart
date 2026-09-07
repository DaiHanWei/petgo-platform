import '../../auth/domain/user_tag.dart';
import 'content_tag.dart';
import 'feed_image_layout.dart';

/// Feed 分类 Tab（Story 3.2，AC3）。`all` 是浏览态语义，对应后端 category=ALL。
enum FeedCategory {
  all('ALL'),
  daily('DAILY'),
  growthMoment('GROWTH_MOMENT'),
  knowledge('KNOWLEDGE');

  const FeedCategory(this.wire);

  /// 后端 category 查询参（UPPER_SNAKE）。
  final String wire;

  /// 埋点里的 `feed_tab` 取值（V1.1.6 Story 16.5）。
  ///
  /// 🛡 与 [wire] **刻意分开**：前者是接口契约（改了要动后端），后者是埋点词表
  /// （改了要动看板）。共用一个值意味着任何一侧想改都得动另一侧。
  /// ⚠️ 取值用小写短名，与清单 v116 的既有属性词表一致（`all` / `daily` / `moment` / `tips`）。
  String get analyticsTab => switch (this) {
        FeedCategory.all => 'all',
        FeedCategory.daily => 'daily',
        FeedCategory.growthMoment => 'moment',
        FeedCategory.knowledge => 'tips',
      };
}

/// 排序路径（V1.1.6 Story 16.5）—— 埋点里的 `rank_mode` 取值。
///
/// 🔴 **由服务端下发，客户端推不出来**：降级链级别 4 会让 ALL Tab 也走时间倒序，
/// 而那对客户端完全无感。按"是不是 ALL Tab"自己判断的话，降级期间的数据会被算进
/// 推荐序的效果里 —— 而那正是 FR-95 参数校准要看的数。
class RankMode {
  RankMode._();

  static const String recommend = 'recommend';
  static const String chrono = 'chrono';

  /// 同一次刷新里各页路径不一致（例如首屏推荐序、第二页恰好降级到级别 4）。
  ///
  /// 🔴 **不挑一边冒充**：那种会话的归因本来就是含混的，
  /// 硬记成 recommend 会把降级期间的数据算进推荐序的效果里，
  /// 硬记成 chrono 又把推荐序的首屏效果丢掉。`mixed` 在看板上一眼就能筛掉。
  static const String mixed = 'mixed';

  /// 服务端没下发（老后端 / 非 Feed 出口）→ `unknown`。
  ///
  /// 🛡 **不默认成 chrono 也不默认成 recommend**：猜错哪一边都会污染效果归因，
  /// 而 `unknown` 在看板上一眼就能看出"这批数据不能用"。
  static const String unknown = 'unknown';

  /// Feed 事件要附加的两个属性（V1.1.6 Story 16.5）。
  ///
  /// 🛡 **缺任一个就都不带** —— 半套属性比没有更糟：看板上会出现一批
  /// 「有 feed_tab 没有 rank_mode」的记录，那些既不能算进推荐序也不能算进时间倒序，
  /// 只能整批扔掉，而扔的时候没人知道它们本来属于哪边。
  ///
  /// ⚠️ 做成公开函数（而不是留在某个 State 里）是为了让这条规则**可被直接测**，
  /// 也让后续新增的 Feed 事件照抄同一处。
  static Map<String, Object> eventProps(String? feedTab, String? rankMode) =>
      (feedTab == null || rankMode == null)
          ? const {}
          : {'feed_tab': feedTab, 'rank_mode': rankMode};

  /// 合并同一次刷新里两页的路径。
  ///
  /// ⚠️ `unknown` 与任何值合并仍是 `unknown`（有一页说不清，整段就说不清）。
  static String merge(String a, String b) {
    if (a == b) return a;
    if (a == unknown || b == unknown) return unknown;
    return mixed;
  }

  static String parse(Object? raw) =>
      (raw == recommend || raw == chrono) ? raw! as String : unknown;
}

/// Feed 卡片条目（对应后端 `FeedItemResponse`）。
///
/// ⚠️ V1.1.6 Story 3.2 起**含点赞态与评论数** —— FR-93 明确推翻了 V1.0.0 FR-17
/// 「点赞/评论数不在卡片展示」那条限制（首页要能直接点赞、看到有多少条评论）。
/// 本注释此前写的是「不含点赞/评论数（FR-17）」，别照着旧注释判断。
class FeedItem {
  const FeedItem({
    required this.id,
    required this.authorId,
    required this.authorDeleted,
    required this.type,
    this.authorNickname,
    this.authorAvatarUrl,
    this.body,
    this.firstImageUrl,
    required this.createdAt,
    this.visibility = kVisibilityPublic,
    this.likeCount = 0,
    this.liked = false,
    this.commentCount = 0,
    this.imageSizes = const [],
    this.imageUrls = const [],
    this.authorTags = const [],
    this.decorationTags = const [],
  });

  final int id;
  final int authorId;

  /// 作者已注销（NFR-8）：前端渲染本地化「已注销用户」+ 默认头像，且头像不可点（Story 3.8）。
  final bool authorDeleted;
  final String? authorNickname;
  final String? authorAvatarUrl;

  /// 作者的运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销作者恒为空。
  final List<UserTag> authorTags;

  /// 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。挂在图片区**左下角位**。
  final List<ContentTag> decorationTags;

  /// 内容类型线格式（DAILY/GROWTH_MOMENT/KNOWLEDGE）。
  final String type;

  /// 正文全文（前端截前 2 行）；可空。
  final String? body;

  /// 可见范围线格式（V1.1.2 Story 4.1 · FR-83）：`PUBLIC` / `PRIVATE`。
  ///
  /// Feed 里恒为 `PUBLIC`（后端按 `visibility = PUBLIC` 过滤平台分发）；
  /// **「我的发布」复用同一 DTO**，私密内容会带 `PRIVATE` —— 我的页据此打「仅自己可见」标识
  /// （Story 4.2）。缺省按 `PUBLIC`（老后端不下发该字段时不至于把内容误标成私密）。
  final String visibility;

  /// 是否仅作者自己可见。
  bool get isPrivate => visibility == kVisibilityPrivate;

  /// 首图（无图 → 纯文字卡）。
  final String? firstImageUrl;

  final DateTime createdAt;

  /// 点赞数（V1.1.6 Story 3.2）。
  ///
  /// ⚠️ 后端**一直在下发**，只是当年 FR-17 的口径是"卡片不展示"，客户端从没读过。
  final int likeCount;

  /// 当前用户是否已赞（V1.1.6 Story 3.2）。未登录恒为 false。
  final bool liked;

  /// 评论数（V1.1.6 Story 3.2）。
  ///
  /// 🔴 口径与内容详情页**完全一致**（含自己那条尚未对外可见的评论）——
  /// 两处显示的是同一个数字，不一致用户只会以为出 bug（后端 Story 3.1 已对齐）。
  final int commentCount;

  /// 图片原始尺寸数组（V1.1.6 Story 3.3 · AD-5）。
  ///
  /// 🔴 与图片**同序等长**，测不出来的位置为 null；**存量内容整列为空**。
  /// ⚠️ 别假设长度与图片数一致 —— 后端有专门覆盖长度不符的用例，
  /// 取用一律走 [firstImageSize] 这类安全取法。
  final List<ImageSize?> imageSizes;

  /// 整组图片（V1.1.6 Story 3.4）。
  ///
  /// ⚠️ 后端**一直在下发**，只是首页此前每帖只显示一张，客户端从没读过。
  /// [firstImageUrl] 保留不删：后端仍在下发，且"我的发布"等处也在用。
  final List<String> imageUrls;

  bool get hasImage => firstImageUrl != null && firstImageUrl!.isNotEmpty;

  /// 轮播用的图片清单。
  ///
  /// 整组为空时回落到首图 —— 老后端 / 测试夹具只给首图时不至于变成"无图帖"。
  List<String> get images =>
      imageUrls.isNotEmpty ? imageUrls : (hasImage ? [firstImageUrl!] : const []);

  /// 首图尺寸；无图 / 存量 / 那一张测不出来 → null（渲染侧按占位比例预留）。
  ImageSize? get firstImageSize => imageSizes.isEmpty ? null : imageSizes.first;

  factory FeedItem.fromJson(Map<String, dynamic> json) => FeedItem(
        id: json['id'] as int,
        authorId: json['authorId'] as int,
        authorDeleted: (json['authorDeleted'] ?? false) as bool,
        authorNickname: json['authorNickname'] as String?,
        authorAvatarUrl: json['authorAvatarUrl'] as String?,
        type: (json['type'] ?? 'DAILY') as String,
        body: json['body'] as String?,
        firstImageUrl: json['firstImageUrl'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
        visibility: (json['visibility'] ?? kVisibilityPublic) as String,
        // 三项均为原始类型、后端恒下发；给默认值只是防老后端/测试夹具缺字段。
        likeCount: (json['likeCount'] ?? 0) as int,
        liked: (json['liked'] ?? false) as bool,
        commentCount: (json['commentCount'] ?? 0) as int,
        // 无图时后端整个字段都不下发（Jackson NON_NULL）——解析必须容忍缺失。
        imageSizes: ImageSize.listFromJson(json['imageSizes']),
        // 同样在无图时整个字段都不下发。
        authorTags: UserTag.listFromJson(json['authorTags']),
        decorationTags: ContentTag.listFromJson(json['decorationTags']),
        imageUrls: (json['imageUrls'] as List?)?.whereType<String>().toList(growable: false) ??
            const [],
      );
}

/// 可见范围线格式常量（与后端 `ContentVisibility` 同名同值；不新建枚举以免与既有 `type` 字符串风格分叉）。
const String kVisibilityPublic = 'PUBLIC';
const String kVisibilityPrivate = 'PRIVATE';

/// Feed 游标分页（对应后端 `{items, nextCursor, hasMore}`）。
class FeedPage {
  const FeedPage({
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.rankMode = RankMode.unknown,
  });

  final List<FeedItem> items;
  final String? nextCursor;
  final bool hasMore;

  /// 本页实际用的排序路径（V1.1.6 Story 16.5）。见 [RankMode]。
  final String rankMode;

  factory FeedPage.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    return FeedPage(
      items: rawItems is List
          ? rawItems.map((e) => FeedItem.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
      nextCursor: json['nextCursor'] as String?,
      hasMore: (json['hasMore'] ?? false) as bool,
      rankMode: RankMode.parse(json['rankMode']),
    );
  }
}
