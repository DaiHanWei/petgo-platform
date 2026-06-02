/// 通知中心条目（对应后端 `NotificationItem`，Story 6.6）。对外只有 token + deepLinkType（无顺序 id）。
class NotificationItem {
  const NotificationItem({
    required this.type,
    this.title,
    this.body,
    this.deepLinkType,
    this.deepLinkToken,
    required this.read,
    this.createdAt,
  });

  final String type; // VET_REPLY | CONSULT_CLOSED | CONTENT_LIKED | CONTENT_COMMENTED | NEW_CONSULT_REQUEST
  final String? title;
  final String? body;
  final String? deepLinkType;
  final String? deepLinkToken;
  final bool read;
  final DateTime? createdAt;

  factory NotificationItem.fromJson(Map<String, dynamic> json) => NotificationItem(
        type: (json['type'] ?? '') as String,
        title: json['title'] as String?,
        body: json['body'] as String?,
        deepLinkType: json['deepLinkType'] as String?,
        deepLinkToken: json['deepLinkToken'] as String?,
        read: (json['read'] ?? false) as bool,
        createdAt: json['createdAt'] == null ? null : DateTime.tryParse(json['createdAt'] as String),
      );
}

/// 通知中心分页（对应后端 `NotificationPage`）。
class NotificationPage {
  const NotificationPage({required this.items, this.nextCursor, required this.hasMore});

  final List<NotificationItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory NotificationPage.fromJson(Map<String, dynamic> json) => NotificationPage(
        items: (json['items'] as List? ?? [])
            .map((e) => NotificationItem.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        nextCursor: json['nextCursor'] as String?,
        hasMore: (json['hasMore'] ?? false) as bool,
      );
}
