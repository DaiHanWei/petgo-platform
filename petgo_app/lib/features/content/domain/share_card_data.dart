import 'content_detail.dart';

/// 一张分享卡需要的全部内容（Story 9.2）。
///
/// 刻意与 `ContentDetail` 解耦：卡面模板只认这几个字段，不认仓库、不认网络、
/// 也不认点赞评论那些跟卡片无关的东西。9-1 的出图管线更是完全不认识本类型。
class ShareCardData {
  const ShareCardData({
    required this.authorName,
    required this.type,
    required this.shareUrl,
    this.authorAvatarUrl,
    this.authorDeleted = false,
    this.body,
    this.imageUrl,
  });

  final String authorName;

  /// 后端类型枚举线格式（`DAILY` / `GROWTH_MOMENT` / `KNOWLEDGE`）。
  final String type;

  /// 🔴 该条内容的**分享链接**（AD-15 Rule 4 第 1 条）——不是通用下载页。
  /// 链接怎么拼由 9-3 决定，本层只负责把它印到码里。
  final String shareUrl;

  final String? authorAvatarUrl;
  final bool authorDeleted;
  final String? body;

  /// 首图。为空 ⇒ 走纯文字模板。
  final String? imageUrl;

  /// 🛡 **有没有图，是选模板的唯一判据**（AD-15 Rule 3）。
  bool get hasImage => (imageUrl ?? '').isNotEmpty;

  /// 印进二维码的那一份 URL：比 [shareUrl] 多一个 `?src=qr`（Story 10.1 · 埋点 E-14）。
  ///
  /// 🔴 **只有码里带标记，才分得出"扫码进来"和"点链接进来"**。
  /// 已带查询串的 URL 用 `&` 续接 —— 现在的链接形状没有查询串，
  /// 但把这个判断写在这里，将来加了参数也不会拼出两个 `?`。
  String get qrUrl => shareUrl.contains('?') ? '$shareUrl&src=qr' : '$shareUrl?src=qr';

  factory ShareCardData.fromDetail(
    ContentDetail detail, {
    required String shareUrl,
    required String fallbackAuthorName,
  }) {
    return ShareCardData(
      authorName: detail.authorDeleted
          ? fallbackAuthorName
          : (detail.authorNickname ?? fallbackAuthorName),
      authorAvatarUrl: detail.authorDeleted ? null : detail.authorAvatarUrl,
      authorDeleted: detail.authorDeleted,
      type: detail.type,
      body: detail.body,
      // 只取首图：分享卡是一张静态图，多图轮播在卡上没有意义。
      imageUrl: detail.imageUrls.isEmpty ? null : detail.imageUrls.first,
      shareUrl: shareUrl,
    );
  }
}
