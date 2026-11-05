/// 发帖成功响应（V1.4.2 social-topic · Story 4.4 · AC6.3 · R-2）：只取 App 用得到的两项。
///
/// 后端 `ContentPostResponse.topics`（`TopicRef{name, official, created}`）：本帖写入的话题；
/// 无话题 / 私密帖整键省略 → 空表。App 发布成功后按它上报 `topic_inserted`（R-2）。
class PublishedPost {
  const PublishedPost({required this.id, this.topics = const <PublishedTopic>[]});

  final int id;
  final List<PublishedTopic> topics;

  factory PublishedPost.fromJson(Map<String, dynamic> json) => PublishedPost(
        id: json['id'] as int,
        topics: PublishedTopic.listFromJson(json['topics']),
      );
}

/// 发帖响应里的一个话题。
class PublishedTopic {
  const PublishedTopic({required this.name, this.official = false, this.created = false});

  /// 标准名（埋点 `topic_id`，R-2）。
  final String name;

  /// 是否官方话题（埋点 `is_official`）。
  final bool official;

  /// 本次发帖新建了该话题（埋点 `is_new`）。
  final bool created;

  static List<PublishedTopic> listFromJson(Object? raw) {
    if (raw is! List) return const <PublishedTopic>[];
    final out = <PublishedTopic>[];
    for (final e in raw) {
      if (e is! Map || e['name'] is! String) continue;
      out.add(PublishedTopic(
        name: e['name'] as String,
        official: e['official'] == true,
        created: e['created'] == true,
      ));
    }
    return List<PublishedTopic>.unmodifiable(out);
  }
}
